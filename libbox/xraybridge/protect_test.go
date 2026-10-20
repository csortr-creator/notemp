package xraybridge

import (
	"context"
	"errors"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"net"
)

func TestAndroidSocketProtection_Positive(t *testing.T) {
	UseAndroidSocketProtection(func(fd uintptr) error {
		return nil // protect success
	})
	defer UseAndroidSocketProtection(nil)

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	go func() {
		c, _ := l.Accept()
		if c != nil {
			c.Close()
		}
	}()

	dialer := &protectDialerAdapter{
		protector: func(fd uintptr) error { return nil },
	}

	dest := xnet.Destination{
		Network: xnet.Network_TCP,
		Address: xnet.LocalHostIP,
		Port:    xnet.Port(l.Addr().(*net.TCPAddr).Port),
	}

	conn, err := dialer.Dial(context.Background(), nil, dest, nil)
	if err != nil {
		t.Fatalf("expected dial to succeed, got %v", err)
	}
	conn.Close()
}

func TestAndroidSocketProtection_NegativeFailClosed(t *testing.T) {
	protectErr := errors.New("VPN_PROTECT_DENIED")

	UseAndroidSocketProtection(func(fd uintptr) error {
		return protectErr // fail protection
	})
	defer UseAndroidSocketProtection(nil)

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()

	go func() {
		c, errAccept := l.Accept()
		if errAccept == nil && c != nil {
			c.Close()
			t.Errorf("connection unexpectedly reached server despite protection failure")
		}
	}()

	dialer := &protectDialerAdapter{
		protector: func(fd uintptr) error { return protectErr },
	}

	dest := xnet.Destination{
		Network: xnet.Network_TCP,
		Address: xnet.LocalHostIP,
		Port:    xnet.Port(l.Addr().(*net.TCPAddr).Port),
	}

	_, err = dialer.Dial(context.Background(), nil, dest, nil)
	if err == nil {
		t.Fatalf("expected dial to fail-closed due to protection error, but it succeeded")
	}

	if err.Error() == "" {
		t.Fatalf("expected an error string, got empty")
	}
}

func TestAndroidSocketProtection_UDPNegativeFailClosed(t *testing.T) {
	protectErr := errors.New("VPN_PROTECT_DENIED")

	dialer := &protectDialerAdapter{
		protector: func(fd uintptr) error { return protectErr },
	}

	dest := xnet.Destination{
		Network: xnet.Network_UDP,
		Address: xnet.LocalHostIP,
		Port:    53,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := dialer.Dial(ctx, nil, dest, nil)
	if err == nil {
		t.Fatalf("expected UDP dial to fail-closed due to protection error, but it succeeded")
	}
}
