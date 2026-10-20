package xraybridge

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"syscall"

	"google.golang.org/protobuf/proto"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	errProtectionRequired = errors.New("XRAY_SOCKET_PROTECTION_REQUIRED")
	errProtectionDenied   = errors.New("XRAY_SOCKET_PROTECTION_DENIED")
	errResolverRequired   = errors.New("XRAY_PROTECTED_RESOLVER_REQUIRED")
	errResolutionFailed   = errors.New("XRAY_PROTECTED_RESOLUTION_FAILED")
	errSocketOptions      = errors.New("XRAY_SOCKET_OPTIONS_UNSUPPORTED")
)

// protectedDialer is selected by the permanent process dispatcher through its
// session owner. Android integration must supply session-owned callbacks
// which protect AND bind each socket to the current underlying network, and route
// DNS over that protected network. There is deliberately no global reset API.
// Only already-applied DNS strategy options are accepted; other flags fail closed.
type protectedDialer struct {
	protect func(context.Context, string, uintptr) error
	resolve func(context.Context, string) ([]netip.Addr, error)
}

var _ internet.SystemDialer = (*protectedDialer)(nil)

func (p *protectedDialer) DestIpAddress() xnet.IP { return nil }

func (p *protectedDialer) Dial(ctx context.Context, src xnet.Address, dest xnet.Destination, sockopt *internet.SocketConfig) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if p.protect == nil {
		return nil, errProtectionRequired
	}
	if sockopt != nil {
		if _, known := internet.DomainStrategy_name[int32(sockopt.DomainStrategy)]; !known {
			return nil, errSocketOptions
		}
		remaining := proto.Clone(sockopt).(*internet.SocketConfig)
		remaining.DomainStrategy = internet.DomainStrategy_AS_IS
		// DialSystem/TcpRaceDial already consumed these scheduling settings for
		// concrete IP destinations. They are not OS socket options.
		if dest.Address != nil && dest.Address.Family().IsIP() {
			remaining.HappyEyeballs = nil
		}
		if !proto.Equal(remaining, &internet.SocketConfig{}) {
			return nil, errSocketOptions
		}
	}
	if dest.Address == nil || (src != nil && !src.Family().IsIP()) {
		return nil, errResolutionFailed
	}
	if dest.Network != xnet.Network_TCP && dest.Network != xnet.Network_UDP {
		return nil, errors.New("XRAY_NETWORK_UNSUPPORTED")
	}
	var addresses []netip.Addr
	if dest.Address.Family().IsDomain() {
		if p.resolve == nil {
			return nil, errResolverRequired
		}
		var err error
		addresses, err = p.resolve(ctx, dest.Address.Domain())
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return nil, errResolutionFailed
		}
	} else {
		ip, ok := netip.AddrFromSlice(dest.Address.IP())
		if ok {
			addresses = []netip.Addr{ip}
		}
	}
	if len(addresses) == 0 {
		return nil, errResolutionFailed
	}
	for _, ip := range addresses {
		if !ip.IsValid() || ip.Zone() != "" {
			return nil, errResolutionFailed
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	control := func(network, address string, raw syscall.RawConn) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		var denied bool
		err := raw.Control(func(fd uintptr) { denied = p.protect(ctx, network, fd) != nil })
		if err != nil {
			return err
		}
		if denied {
			return errProtectionDenied
		}
		return ctx.Err()
	}
	var lastErr error
	for _, ip := range addresses {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		ip = ip.Unmap()
		target := net.JoinHostPort(ip.String(), strconv.Itoa(int(dest.Port)))
		if dest.Network == xnet.Network_TCP {
			d := net.Dialer{Control: control}
			if src != nil && src != xnet.AnyIP {
				d.LocalAddr = &net.TCPAddr{IP: src.IP()}
			}
			c, err := d.DialContext(ctx, "tcp", target)
			if err == nil {
				return c, nil
			}
			if errors.Is(err, errProtectionDenied) {
				return nil, err
			}
			lastErr = err
		} else {
			network, bind := "udp4", "0.0.0.0:0"
			if ip.Is6() {
				network, bind = "udp6", "[::]:0"
			}
			if src != nil && src != xnet.AnyIP {
				bind = net.JoinHostPort(src.IP().String(), "0")
			}
			lc := net.ListenConfig{Control: control}
			packet, err := lc.ListenPacket(ctx, network, bind)
			if err != nil {
				return nil, err
			}
			if err = ctx.Err(); err != nil {
				packet.Close()
				return nil, err
			}
			return &internet.PacketConnWrapper{PacketConn: packet, Dest: &net.UDPAddr{IP: net.IP(ip.AsSlice()), Port: int(dest.Port)}}, nil
		}
	}
	return nil, lastErr
}
