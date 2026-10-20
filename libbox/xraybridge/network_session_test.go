package xraybridge

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
)

func sessionCallbacks() (socketProtector, domainResolver) {
	return func(context.Context, string, uintptr) error { return nil },
		func(context.Context, string) ([]netip.Addr, error) {
			return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
		}
}
func testNetworkSession(t *testing.T, ctx context.Context) *networkSession {
	t.Helper()
	protect, resolve := sessionCallbacks()
	s, err := newNetworkSession(ctx, protect, resolve)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestNetworkSessionRetiresTCPAndPreservesNewNetwork(t *testing.T) {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	s := testNetworkSession(t, context.Background())
	destination := xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(l.Addr().(*net.TCPAddr).Port))
	dialCtx, cancel := context.WithCancel(context.Background())
	old, err := s.Dial(dialCtx, nil, destination, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer old.Close()
	peer, err := l.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	cancel() // Established sockets belong to the network generation, not DialContext.
	if _, err = old.Write([]byte("ping")); err != nil {
		t.Fatalf("dial cancellation closed established socket: %v", err)
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 4)
	if _, err = io.ReadFull(peer, b); err != nil || string(b) != "ping" {
		t.Fatalf("old exchange: %v", err)
	}
	read := make(chan error, 1)
	go func() { _, err := old.Read(make([]byte, 1)); read <- err }()
	var newCalls atomic.Int32
	_, resolve := sessionCallbacks()
	if err = s.Update(func(context.Context, string, uintptr) error { newCalls.Add(1); return nil }, resolve); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-read:
		if err == nil {
			t.Fatal("retired blocked read succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("retired read remains blocked")
	}
	if _, err = old.Write([]byte("stale")); !errors.Is(err, errNetworkChanged) {
		t.Fatalf("retired write: %v", err)
	}
	current, err := s.Dial(context.Background(), nil, destination, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer current.Close()
	peer2, err := l.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer peer2.Close()
	if _, err = current.Write([]byte("new!")); err != nil {
		t.Fatal(err)
	}
	peer2.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = io.ReadFull(peer2, b); err != nil || string(b) != "new!" {
		t.Fatalf("new exchange: %v", err)
	}
	if newCalls.Load() != 1 {
		t.Fatalf("new network protection calls: %d", newCalls.Load())
	}
	// A late close from the retired generation must not close the new generation.
	old.Close()
	if _, err = current.Write([]byte("live")); err != nil {
		t.Fatal(err)
	}
}

func TestNetworkSessionCancelsOldDNSBeforeSocket(t *testing.T) {
	protect, resolve := sessionCallbacks()
	var oldControls atomic.Int32
	started := make(chan struct{})
	s, err := newNetworkSession(context.Background(), func(context.Context, string, uintptr) error { oldControls.Add(1); return nil },
		func(ctx context.Context, _ string) ([]netip.Addr, error) {
			close(started)
			<-ctx.Done()
			return nil, ctx.Err()
		})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	result := make(chan error, 1)
	go func() {
		c, err := s.Dial(context.Background(), nil, xnet.UDPDestination(xnet.DomainAddress("fixture.invalid"), 443), nil)
		if c != nil {
			c.Close()
		}
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("DNS did not start")
	}
	if err = s.Update(protect, resolve); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-result:
		if !errors.Is(err, errNetworkChanged) {
			t.Fatalf("old DNS cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("old DNS not cancelled")
	}
	if oldControls.Load() != 0 {
		t.Fatal("old resolver opened a socket after replacement")
	}
}

func TestNetworkSessionCancelsInFlightProtection(t *testing.T) {
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	protect, resolve := sessionCallbacks()
	started := make(chan struct{})
	s, err := newNetworkSession(context.Background(), func(ctx context.Context, _ string, _ uintptr) error { close(started); <-ctx.Done(); return ctx.Err() }, resolve)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	result := make(chan error, 1)
	go func() {
		c, err := s.Dial(context.Background(), nil, xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(l.Addr().(*net.TCPAddr).Port)), nil)
		if c != nil {
			c.Close()
		}
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("protection callback did not start")
	}
	if err = s.Update(protect, resolve); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-result:
		if !errors.Is(err, errNetworkChanged) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("old protection did not cancel")
	}
	l.SetDeadline(time.Now().Add(20 * time.Millisecond))
	if c, err := l.Accept(); err == nil {
		c.Close()
		t.Fatal("retired protection allowed TCP connect")
	}
}

func TestNetworkSessionUDPAndParentCancellation(t *testing.T) {
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := testNetworkSession(t, ctx)
	conn, err := s.Dial(context.Background(), nil, xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(server.LocalAddr().(*net.UDPAddr).Port)), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	packet, ok := conn.(net.PacketConn)
	if !ok {
		t.Fatal("session owner hid UDP PacketConn interface")
	}
	if _, err = packet.WriteTo([]byte("udp"), server.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	server.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 16)
	if n, _, err := server.ReadFrom(b); err != nil || string(b[:n]) != "udp" {
		t.Fatalf("UDP exchange: %v", err)
	}
	result := make(chan error, 1)
	go func() { _, _, err := packet.ReadFrom(b); result <- err }()
	cancel()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("cancelled UDP read succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("parent cancellation did not close UDP")
	}
	if _, err = packet.WriteTo([]byte("stale"), server.LocalAddr()); err == nil {
		t.Fatal("parent cancellation allowed UDP write")
	}
	if _, err = s.Dial(context.Background(), nil, xnet.UDPDestination(xnet.LocalHostIP, 443), nil); err == nil {
		t.Fatal("closed session accepted dial")
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestNetworkSessionConcurrentUpdateDialClose(t *testing.T) {
	protect, resolve := sessionCallbacks()
	s := testNetworkSession(t, context.Background())
	destination := xnet.UDPDestination(xnet.LocalHostIP, 443)
	first, err := s.Dial(context.Background(), nil, destination, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	start := make(chan struct{})
	var workers sync.WaitGroup
	for worker := range 8 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			for range 20 {
				if worker%2 == 0 {
					err := s.Update(protect, resolve)
					if err != nil && !errors.Is(err, errNetworkClosed) {
						t.Errorf("update: %v", err)
					}
				} else {
					c, err := s.Dial(context.Background(), nil, destination, nil)
					if err == nil {
						c.Close()
					} else if !errors.Is(err, errNetworkChanged) && !errors.Is(err, errNetworkClosed) {
						t.Errorf("dial: %v", err)
					}
				}
			}
		}()
	}
	workers.Add(1)
	go func() { defer workers.Done(); <-start; s.Close() }()
	close(start)
	workers.Wait()
	if _, err = s.Dial(context.Background(), nil, destination, nil); !errors.Is(err, errNetworkClosed) {
		t.Fatalf("dial after close: %v", err)
	}
	if _, err = first.Write([]byte("retired")); err == nil {
		t.Fatal("old connection survived session close")
	}
}

func TestNetworkSessionInvalidUpdateKeepsCurrentGeneration(t *testing.T) {
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	s := testNetworkSession(t, context.Background())
	destination := xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(server.LocalAddr().(*net.UDPAddr).Port))
	c, err := s.Dial(context.Background(), nil, destination, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	protect, resolve := sessionCallbacks()
	if err = s.Update(nil, resolve); !errors.Is(err, errProtectionRequired) {
		t.Fatal(err)
	}
	if err = s.Update(protect, nil); !errors.Is(err, errResolverRequired) {
		t.Fatal(err)
	}
	if _, err = c.Write([]byte("still-active")); err != nil {
		t.Fatalf("invalid update retired the active network: %v", err)
	}
	server.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 32)
	if n, _, err := server.ReadFrom(b); err != nil || string(b[:n]) != "still-active" {
		t.Fatalf("active network exchange: %v", err)
	}
}
