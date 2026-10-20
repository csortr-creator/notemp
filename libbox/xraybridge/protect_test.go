package xraybridge

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

func TestProtectedDialerExchange(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		for _, domain := range []bool{false, true} {
			name := network + "/ip"
			if domain {
				name = network + "/domain"
			}
			t.Run(name, func(t *testing.T) {
				ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
				defer cancel()
				var calls, lookups atomic.Int32
				d := &protectedDialer{
					protect: func(ctx context.Context, network string, fd uintptr) error { calls.Add(1); return nil },
					resolve: func(ctx context.Context, name string) ([]netip.Addr, error) {
						lookups.Add(1)
						if name != "fixture.invalid" {
							t.Errorf("wrong resolver target")
						}
						return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
					},
				}
				address := xnet.LocalHostIP
				if domain {
					address = xnet.DomainAddress("fixture.invalid")
				}
				var dest xnet.Destination
				done := make(chan error, 1)
				if network == "tcp" {
					l, err := net.Listen("tcp", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					defer l.Close()
					dest = xnet.TCPDestination(address, xnet.Port(l.Addr().(*net.TCPAddr).Port))
					go func() {
						c, err := l.Accept()
						if err != nil {
							done <- err
							return
						}
						defer c.Close()
						c.SetDeadline(time.Now().Add(time.Second))
						b := make([]byte, 4)
						_, err = io.ReadFull(c, b)
						if err == nil {
							_, err = c.Write(b)
						}
						done <- err
					}()
				} else {
					l, err := net.ListenPacket("udp4", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					defer l.Close()
					dest = xnet.UDPDestination(address, xnet.Port(l.LocalAddr().(*net.UDPAddr).Port))
					go func() {
						l.SetDeadline(time.Now().Add(time.Second))
						b := make([]byte, 16)
						n, a, err := l.ReadFrom(b)
						if err == nil {
							_, err = l.WriteTo(b[:n], a)
						}
						done <- err
					}()
				}
				c, err := d.Dial(ctx, nil, dest, nil)
				if err != nil {
					t.Fatal(err)
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(time.Second))
				if _, err = c.Write([]byte("ping")); err != nil {
					t.Fatal(err)
				}
				b := make([]byte, 4)
				if _, err = io.ReadFull(c, b); err != nil || string(b) != "ping" {
					t.Fatalf("exchange: %q %v", b, err)
				}
				if err = <-done; err != nil {
					t.Fatal(err)
				}
				if calls.Load() != 1 {
					t.Fatalf("protection calls: %d", calls.Load())
				}
				expected := int32(0)
				if domain {
					expected = 1
				}
				if lookups.Load() != expected {
					t.Fatalf("resolver calls: %d", lookups.Load())
				}
			})
		}
	}
}

func TestProtectedDialerDenied(t *testing.T) {
	tcp, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	for _, dest := range []xnet.Destination{
		xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(tcp.Addr().(*net.TCPAddr).Port)),
		xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(udp.LocalAddr().(*net.UDPAddr).Port)),
	} {
		var calls int
		d := &protectedDialer{protect: func(context.Context, string, uintptr) error { calls++; return errors.New("private callback details") }}
		c, err := d.Dial(context.Background(), nil, dest, nil)
		if c != nil {
			c.Close()
			t.Fatal("denied socket returned")
		}
		if !errors.Is(err, errProtectionDenied) || calls != 1 {
			t.Fatalf("denied: %v calls %d", err, calls)
		}
	}
	tcp.SetDeadline(time.Now().Add(20 * time.Millisecond))
	if c, err := tcp.Accept(); err == nil {
		c.Close()
		t.Fatal("denied TCP reached listener")
	}
	udp.SetReadDeadline(time.Now().Add(20 * time.Millisecond))
	if n, _, err := udp.ReadFromUDP(make([]byte, 16)); err == nil || n != 0 {
		t.Fatal("denied UDP reached listener")
	}
}

func TestProtectedDialerResolverPolicy(t *testing.T) {
	for _, network := range []xnet.Network{xnet.Network_TCP, xnet.Network_UDP} {
		dest := xnet.Destination{Network: network, Address: xnet.DomainAddress("fixture.invalid"), Port: 443}
		var controls atomic.Int32
		d := &protectedDialer{protect: func(context.Context, string, uintptr) error { controls.Add(1); return nil }}
		if _, err := d.Dial(context.Background(), nil, dest, nil); !errors.Is(err, errResolverRequired) {
			t.Fatalf("missing resolver: %v", err)
		}
		d.resolve = func(context.Context, string) ([]netip.Addr, error) { return nil, errors.New("private DNS failure") }
		if _, err := d.Dial(context.Background(), nil, dest, nil); !errors.Is(err, errResolutionFailed) {
			t.Fatalf("resolver error: %v", err)
		}
		d.resolve = func(context.Context, string) ([]netip.Addr, error) { return []netip.Addr{{}}, nil }
		if _, err := d.Dial(context.Background(), nil, dest, nil); !errors.Is(err, errResolutionFailed) {
			t.Fatalf("invalid IP: %v", err)
		}
		d.resolve = func(ctx context.Context, _ string) ([]netip.Addr, error) { <-ctx.Done(); return nil, ctx.Err() }
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
		_, err := d.Dial(ctx, nil, dest, nil)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("resolver cancellation: %v", err)
		}
		if controls.Load() != 0 {
			t.Fatal("opened socket before resolution completed")
		}
	}
}

func TestProtectedDialerRejectsUnsupportedOptions(t *testing.T) {
	d := &protectedDialer{}
	dest := xnet.TCPDestination(xnet.LocalHostIP, 443)
	if _, err := d.Dial(context.Background(), nil, dest, nil); !errors.Is(err, errProtectionRequired) {
		t.Fatal(err)
	}
	d.protect = func(context.Context, string, uintptr) error {
		t.Fatal("opened socket for unsupported options")
		return nil
	}
	if _, err := d.Dial(context.Background(), nil, dest, &internet.SocketConfig{}); !errors.Is(err, errSocketOptions) {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := d.Dial(ctx, nil, dest, nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
