package xraybridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"golang.org/x/net/proxy"
)

// Raw server fixtures bypass Bridge's client-only inbound policy, but still
// declare their own host-network owner instead of allowing unidentified dials.
func ownedHostTestContext(t *testing.T, ctx context.Context) context.Context {
	t.Helper()
	s, err := newHostNetworkSession(ctx)
	if err != nil {
		t.Fatal(err)
	}
	globalDispatcher.register(s)
	t.Cleanup(func() { globalDispatcher.retire(s) })
	return context.WithValue(ctx, sessionKey{}, s)
}

func dispatcherSocks(t *testing.T, port int, target string) (net.Conn, error) {
	t.Helper()
	d, err := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)),
		&proxy.Auth{User: "fixture", Password: wirePassword}, &net.Dialer{Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return d.(proxy.ContextDialer).DialContext(ctx, "tcp", target)
}

func dispatcherReply(t *testing.T, port int, target, want string) net.Conn {
	t.Helper()
	c, err := dispatcherSocks(t, port, target)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != want {
		t.Fatalf("owner reply=%q, want %q", got, want)
	}
	return c
}

func ownerReplyServer(t *testing.T, address, reply string) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				c.SetDeadline(time.Now().Add(5 * time.Second))
				buf := make([]byte, 4)
				for {
					if _, err := io.ReadFull(c, buf); err != nil {
						return
					}
					if string(buf) != "ping" {
						return
					}
					if _, err := c.Write([]byte(reply)); err != nil {
						return
					}
				}
			}()
		}
	}()
	return l
}

// The exact same synthetic hostname/port must reach different destinations
// through two simultaneous real Xray SOCKS instances, with separate callbacks.
func TestProtectedBridgeOwnersAndNetworkReplacement(t *testing.T) {
	a := ownerReplyServer(t, "127.0.0.1:0", "owner-A")
	targetPort := a.Addr().(*net.TCPAddr).Port
	ownerReplyServer(t, net.JoinHostPort("127.0.0.2", strconv.Itoa(targetPort)), "owner-B")
	target := net.JoinHostPort("owner-dispatch.invalid", strconv.Itoa(targetPort))
	var protectsA, protectsB, resolvesA, resolvesB atomic.Int32
	protect := func(counter *atomic.Int32) socketProtector {
		return func(ctx context.Context, _ string, _ uintptr) error { counter.Add(1); return ctx.Err() }
	}
	resolve := func(ip string, counter *atomic.Int32) domainResolver {
		return func(ctx context.Context, host string) ([]netip.Addr, error) {
			if host != "owner-dispatch.invalid" {
				return nil, fmt.Errorf("unexpected domain %s", host)
			}
			counter.Add(1)
			return []netip.Addr{netip.MustParseAddr(ip)}, ctx.Err()
		}
	}
	ba, bb := NewBridge(), NewBridge()
	pa, pb := freePort(t), freePort(t)
	if err := ba.StartProtected(context.Background(), fixtureConfig(t, pa), protect(&protectsA), resolve("127.0.0.1", &resolvesA)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ba.Stop() })
	if err := bb.StartProtected(context.Background(), fixtureConfig(t, pb), protect(&protectsB), resolve("127.0.0.2", &resolvesB)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { bb.Stop() })
	old := dispatcherReply(t, pa, target, "owner-A")
	dispatcherReply(t, pb, target, "owner-B")
	if protectsA.Load() == 0 || protectsB.Load() == 0 || resolvesA.Load() == 0 || resolvesB.Load() == 0 {
		t.Fatal("actual SOCKS path missed owner callbacks")
	}
	oldOwner := ba.session
	stale := context.WithValue(context.Background(), sessionKey{}, oldOwner)
	if err := ba.UpdateNetwork(protect(&protectsA), resolve("127.0.0.2", &resolvesA)); err != nil {
		t.Fatal(err)
	}
	old.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := old.Read(make([]byte, 1)); err == nil {
		t.Fatal("old socket survived network replacement")
	} else if e, ok := err.(net.Error); ok && e.Timeout() {
		t.Fatal("old socket was not closed")
	}
	dispatcherReply(t, pa, target, "owner-B")
	if err := ba.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := ba.StartProtected(context.Background(), fixtureConfig(t, pa), protect(&protectsA), resolve("127.0.0.1", &resolvesA)); err != nil {
		t.Fatal(err)
	}
	dest := xnet.TCPDestination(xnet.DomainAddress("owner-dispatch.invalid"), xnet.Port(targetPort))
	for _, ctx := range []context.Context{nil, context.Background(), stale} {
		if c, err := globalDispatcher.Dial(ctx, nil, dest, nil); !errors.Is(err, ErrSessionMissingOrStopped) {
			if c != nil {
				c.Close()
			}
			t.Fatalf("unowned dial=%v", err)
		}
	}
	dispatcherReply(t, pa, target, "owner-A")
	if err := ba.Stop(); err != nil {
		t.Fatal(err)
	}
	// Stopping/restarting A must not close or borrow B's sockets or callbacks.
	dispatcherReply(t, pb, target, "owner-B")
}

func TestProtectedBridgeStopCancelsPendingDNS(t *testing.T) {
	b := NewBridge()
	port := freePort(t)
	entered, cancelled := make(chan struct{}), make(chan struct{})
	if err := b.StartProtected(context.Background(), fixtureConfig(t, port),
		func(context.Context, string, uintptr) error {
			t.Error("socket opened before DNS completed")
			return errors.New("denied")
		},
		func(ctx context.Context, _ string) ([]netip.Addr, error) {
			close(entered)
			<-ctx.Done()
			close(cancelled)
			return nil, ctx.Err()
		}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Stop() })
	dialDone := make(chan struct{})
	go func() {
		defer close(dialDone)
		c, _ := dispatcherSocks(t, port, "pending-dispatch.invalid:80")
		if c != nil {
			c.Close()
		}
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Xray did not call owned DNS")
	}
	stopDone := make(chan error, 1)
	go func() { stopDone <- b.Stop() }()
	select {
	case err := <-stopDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop waited for uncancelled DNS")
	}
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("Stop did not cancel DNS")
	}
	select {
	case <-dialDone:
	case <-time.After(time.Second):
		t.Fatal("SOCKS request survived Stop")
	}
}
