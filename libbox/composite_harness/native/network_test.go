package composite

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

type testProvider struct {
	lookup     func(string, *LookupRequest) error
	protectErr error
	protected  atomic.Int32
}

func (p *testProvider) ProtectAndBind(int64) error { p.protected.Add(1); return p.protectErr }
func (p *testProvider) Lookup(domain string, request *LookupRequest) error {
	return p.lookup(domain, request)
}

type cancelHook struct {
	called atomic.Int32
	done   chan struct{}
}

func (h *cancelHook) Cancel() {
	if h.called.Add(1) == 1 && h.done != nil {
		close(h.done)
	}
}

func TestLookupCancellationAndLateRegistration(t *testing.T) {
	for _, late := range []bool{false, true} {
		t.Run(fmt.Sprint(late), func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			pending := make(chan *LookupRequest, 1)
			hook := &cancelHook{done: make(chan struct{})}
			p := &testProvider{lookup: func(_ string, r *LookupRequest) error {
				if !late {
					if err := r.OnCancel(hook); err != nil {
						return err
					}
				}
				pending <- r
				return nil
			}}
			result := make(chan error, 1)
			go func() { _, err := (providerAdapter{p}).resolve(ctx, "never.system.resolve.invalid"); result <- err }()
			r := <-pending
			cancel()
			select {
			case err := <-result:
				if !errors.Is(err, context.Canceled) {
					t.Fatal(err)
				}
			case <-time.After(time.Second):
				t.Fatal("cancellation blocked")
			}
			if late {
				if err := r.OnCancel(hook); err != nil {
					t.Fatal(err)
				}
			}
			select {
			case <-hook.done:
			case <-time.After(time.Second):
				t.Fatal("platform not cancelled")
			}
			if hook.called.Load() != 1 {
				t.Fatal("duplicate cancellation")
			}
			if r.Success("127.0.0.1") == nil || r.Fail("late") == nil {
				t.Fatal("accepted late completion")
			}
			if r.OnCancel(hook) == nil {
				t.Fatal("accepted duplicate registration")
			}
		})
	}
}

func TestLookupValidationAndDuplicateCompletion(t *testing.T) {
	for _, response := range []string{"", "127.0.0.1\nnot.an.ip", "fe80::1%rmnet_data3", "example.com"} {
		p := &testProvider{lookup: func(_ string, r *LookupRequest) error { _ = r.Success(response); return nil }}
		if addresses, err := (providerAdapter{p}).resolve(context.Background(), "fixture.invalid"); err == nil || len(addresses) != 0 {
			t.Fatalf("accepted %q", response)
		}
	}
	hook := &cancelHook{}
	ctx, cancel := context.WithCancel(context.Background())
	p := &testProvider{lookup: func(_ string, r *LookupRequest) error {
		if err := r.Success("127.0.0.1\n::1"); err != nil {
			return err
		}
		if r.Success("192.0.2.1") == nil {
			t.Error("accepted duplicate success")
		}
		cancel()
		return r.OnCancel(hook)
	}}
	_, _ = (providerAdapter{p}).resolve(ctx, "fixture.invalid")
	if hook.called.Load() != 0 {
		t.Fatal("cancelled already completed platform lookup")
	}
}

func TestProtectedFacadeNetworkLifecycle(t *testing.T) {
	destination, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer destination.Close()
	var accepted atomic.Int32
	var workers sync.WaitGroup
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			c, e := destination.Accept()
			if e != nil {
				return
			}
			accepted.Add(1)
			workers.Add(1)
			go func() { defer workers.Done(); defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	defer workers.Wait()
	defer destination.Close()
	reserve, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserve.Addr().String()
	port := reserve.Addr().(*net.TCPAddr).Port
	reserve.Close()
	config := fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"fixture","pass":"composite_fixture_password_0123456789abcdef"}]}}],"outbounds":[{"protocol":"freedom"}]}`, port)
	facade := NewExecutorFacade()
	defer facade.StopBridge()
	if facade.StartProtectedBridge(config, nil) == nil {
		t.Fatal("accepted nil provider")
	}
	provider := &testProvider{lookup: func(domain string, r *LookupRequest) error {
		if domain != "jni-owner.invalid" {
			return fmt.Errorf("unexpected domain %s", domain)
		}
		return r.Success("127.0.0.1")
	}}
	if err := facade.StartProtectedBridge(config, provider); err != nil {
		t.Fatal(err)
	}
	dialer, err := proxy.SOCKS5("tcp", address, &proxy.Auth{User: "fixture", Password: "composite_fixture_password_0123456789abcdef"}, &net.Dialer{Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	target := fmt.Sprintf("jni-owner.invalid:%d", destination.Addr().(*net.TCPAddr).Port)
	c, err := dialer.Dial("tcp", target)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = c.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2)
	if _, err = io.ReadFull(c, reply); err != nil || string(reply) != "ok" {
		t.Fatalf("reply %q: %v", reply, err)
	}
	if provider.protected.Load() == 0 {
		t.Fatal("socket bypassed provider")
	}
	denied := &testProvider{lookup: provider.lookup, protectErr: errors.New("protect denied")}
	if err := facade.UpdateBridgeNetwork(denied); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Read(make([]byte, 1)); err == nil {
		t.Fatal("old socket survived replacement")
	}
	before := accepted.Load()
	deniedConn, dialErr := dialer.Dial("tcp", target)
	if deniedConn != nil {
		_ = deniedConn.SetDeadline(time.Now().Add(time.Second))
		_, _ = deniedConn.Write([]byte("x"))
		_, dialErr = deniedConn.Read(make([]byte, 1))
		deniedConn.Close()
	}
	if dialErr == nil {
		t.Fatal("denied socket connected")
	}
	if denied.protected.Load() == 0 || accepted.Load() != before {
		t.Fatal("denial bypassed")
	}
	pending := make(chan *LookupRequest, 1)
	hook := &cancelHook{done: make(chan struct{})}
	blocked := &testProvider{lookup: func(_ string, r *LookupRequest) error {
		if err := r.OnCancel(hook); err != nil {
			return err
		}
		pending <- r
		return nil
	}}
	if err := facade.UpdateBridgeNetwork(blocked); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		conn, _ := dialer.Dial("tcp", target)
		if conn != nil {
			conn.SetDeadline(time.Now().Add(time.Second))
			conn.Read(make([]byte, 1))
			conn.Close()
		}
	}()
	var request *LookupRequest
	select {
	case request = <-pending:
	case <-time.After(3 * time.Second):
		t.Fatal("no pending lookup")
	}
	if err := facade.StopBridge(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-hook.done:
	case <-time.After(time.Second):
		t.Fatal("Stop did not cancel platform DNS")
	}
	if request.Success("127.0.0.1") == nil {
		t.Fatal("stopped owner accepted DNS")
	}
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("stopped dial remained blocked")
	}
}
