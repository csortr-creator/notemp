package xraybridge

import (
	"context"
	"syscall"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
	"net"
)

// UseAndroidSocketProtection injects a JNI socket protector function into Xray's global system dialer.
// If the protector fails, dialing stops immediately, maintaining a fail-closed secure state.
func UseAndroidSocketProtection(protector func(fd uintptr) error) {
	if protector == nil {
		internet.UseAlternativeSystemDialer(nil) // reset
		return
	}

	adapter := &protectDialerAdapter{
		protector: protector,
	}

	// Create a new dialer that intercepts the syscall Control to apply protection
	internet.UseAlternativeSystemDialer(adapter)
}

type protectDialerAdapter struct {
	protector func(fd uintptr) error
}

func (p *protectDialerAdapter) DestIpAddress() xnet.IP {
	return nil
}

func (p *protectDialerAdapter) Dial(ctx context.Context, src xnet.Address, dest xnet.Destination, sockopt *internet.SocketConfig) (net.Conn, error) {
	if dest.Network == xnet.Network_UDP {
		var lc net.ListenConfig
		lc.Control = func(network, address string, c syscall.RawConn) error {
			var protectErr error
			err := c.Control(func(fd uintptr) {
				protectErr = p.protector(fd)
			})
			if err != nil {
				return err
			}
			return protectErr
		}

		packetConn, err := lc.ListenPacket(ctx, dest.Network.SystemString(), "")
		if err != nil {
			return nil, err
		}

		destAddr, err := net.ResolveUDPAddr("udp", dest.NetAddr())
		if err != nil {
			return nil, err
		}

		return &internet.PacketConnWrapper{
			PacketConn: packetConn,
			Dest:       destAddr,
		}, nil
	}

	dialer := &net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			var protectErr error
			err := c.Control(func(fd uintptr) {
				protectErr = p.protector(fd)
			})
			if err != nil {
				return err
			}
			return protectErr
		},
	}

	if src != nil && src != xnet.AnyIP {
		dialer.LocalAddr = &net.TCPAddr{
			IP:   src.IP(),
			Port: 0,
		}
	}

	return dialer.DialContext(ctx, dest.Network.SystemString(), dest.NetAddr())
}
