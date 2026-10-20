package xraybridge

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	errNetworkClosed  = errors.New("XRAY_NETWORK_SESSION_CLOSED")
	errNetworkChanged = errors.New("XRAY_UNDERLYING_NETWORK_CHANGED")
)

type socketProtector func(context.Context, string, uintptr) error
type domainResolver func(context.Context, string) ([]netip.Addr, error)

// networkSession owns one immutable callback generation at a time. Updating the
// underlying network cancels old DNS/dials and closes its established sockets.
// The process dispatcher selects this owner by an unexported context identity.
// It is not an Android adapter: its caller must implement protection/binding/DNS.
type networkSession struct {
	mu        sync.Mutex
	parent    context.Context
	current   *networkEpoch
	closed    bool
	stopWatch func() bool
}

type networkEpoch struct {
	ctx         context.Context
	cancel      context.CancelCauseFunc
	dialer      internet.SystemDialer
	connections map[*sessionConn]struct{}
	dials       map[*networkDial]struct{}
}

type networkDial struct{ cancel context.CancelCauseFunc }

func newNetworkSession(ctx context.Context, protect socketProtector, resolve domainResolver) (*networkSession, error) {
	if protect == nil {
		return nil, errProtectionRequired
	}
	if resolve == nil {
		return nil, errResolverRequired
	}
	return newSessionWithDialer(ctx, &protectedDialer{protect: protect, resolve: resolve})
}

// Only the explicitly requested host executor uses Xray's host socket semantics.
// Missing dispatcher identities never select this owner or the host network.
func newHostNetworkSession(ctx context.Context) (*networkSession, error) {
	return newSessionWithDialer(ctx, &internet.DefaultSystemDialer{})
}

func newSessionWithDialer(ctx context.Context, dialer internet.SystemDialer) (*networkSession, error) {
	if ctx == nil {
		return nil, errors.New("XRAY_CONTEXT_REQUIRED")
	}
	s := &networkSession{parent: ctx}
	if err := s.updateDialer(dialer); err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.stopWatch = context.AfterFunc(ctx, func() { _ = s.Close() })
	s.mu.Unlock()
	return s, nil
}

func (s *networkSession) Update(protect socketProtector, resolve domainResolver) error {
	if protect == nil {
		return errProtectionRequired
	}
	if resolve == nil {
		return errResolverRequired
	}
	return s.updateDialer(&protectedDialer{protect: protect, resolve: resolve})
}

func (s *networkSession) updateDialer(dialer internet.SystemDialer) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errNetworkClosed
	}
	if err := s.parent.Err(); err != nil {
		s.mu.Unlock()
		return context.Cause(s.parent)
	}
	ctx, cancel := context.WithCancelCause(s.parent)
	old := s.current
	s.current = &networkEpoch{
		ctx: ctx, cancel: cancel,
		dialer:      dialer,
		connections: make(map[*sessionConn]struct{}),
		dials:       make(map[*networkDial]struct{}),
	}
	retired := retireEpoch(old, errNetworkChanged)
	s.mu.Unlock()
	closeSessionConnections(retired)
	return nil
}

// Called while holding the owner lock; Close itself runs outside that lock.
func retireEpoch(epoch *networkEpoch, cause error) []*sessionConn {
	if epoch == nil {
		return nil
	}
	epoch.cancel(cause)
	// Cancel registered dials synchronously, before retirement becomes visible.
	// An asynchronous context hook alone could leave a new Control/connect racing
	// with a completed Close or network replacement.
	for dial := range epoch.dials {
		dial.cancel(cause)
	}
	epoch.dials = nil
	connections := make([]*sessionConn, 0, len(epoch.connections))
	for conn := range epoch.connections {
		connections = append(connections, conn)
	}
	epoch.connections = nil
	return connections
}
func closeSessionConnections(connections []*sessionConn) {
	for _, c := range connections {
		_ = c.Close()
	}
}

// Suspend retires the current network without destroying the core or owner.
// A later Update installs a fresh generation; no dial may use the retired one.
func (s *networkSession) Suspend() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errNetworkClosed
	}
	retired := retireEpoch(s.current, errNetworkChanged)
	s.current = nil
	s.mu.Unlock()
	closeSessionConnections(retired)
	return nil
}

func (s *networkSession) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	retired := retireEpoch(s.current, errNetworkClosed)
	s.current = nil
	if s.stopWatch != nil {
		s.stopWatch()
		s.stopWatch = nil
	}
	s.mu.Unlock()
	closeSessionConnections(retired)
	return nil
}

var _ internet.SystemDialer = (*networkSession)(nil)

func (s *networkSession) DestIpAddress() xnet.IP { return nil }

// DNS and socket operations share the same epoch cancellation registry.
func (s *networkSession) beginOperation(ctx context.Context) (*networkEpoch, context.Context, *networkDial, error) {
	if ctx == nil {
		return nil, nil, nil, errors.New("XRAY_CONTEXT_REQUIRED")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	epoch := s.current
	if s.closed || epoch == nil {
		return nil, nil, nil, errNetworkClosed
	}
	if epoch.ctx.Err() != nil {
		return nil, nil, nil, context.Cause(epoch.ctx)
	}
	dialCtx, cancel := context.WithCancelCause(ctx)
	operation := &networkDial{cancel: cancel}
	epoch.dials[operation] = struct{}{}
	return epoch, dialCtx, operation, nil
}

func (s *networkSession) endOperation(epoch *networkEpoch, operation *networkDial) {
	operation.cancel(nil)
	s.mu.Lock()
	delete(epoch.dials, operation)
	s.mu.Unlock()
}

func (s *networkSession) resolveSystemDomain(ctx context.Context, domain string) ([]net.IP, error) {
	epoch, lookupCtx, operation, err := s.beginOperation(ctx)
	if err != nil {
		return nil, err
	}
	defer s.endOperation(epoch, operation)
	var ips []net.IP
	if protected, ok := epoch.dialer.(*protectedDialer); ok {
		if protected.resolve == nil {
			return nil, errResolverRequired
		}
		addresses, err := protected.resolve(lookupCtx, domain)
		if err != nil {
			return nil, err
		}
		if len(addresses) == 0 || len(addresses) > 256 {
			return nil, errResolutionFailed
		}
		for _, ip := range addresses {
			if !ip.IsValid() || ip.Zone() != "" {
				return nil, errResolutionFailed
			}
			ips = append(ips, net.IP(ip.Unmap().AsSlice()))
		}
	} else {
		ips, err = net.DefaultResolver.LookupIP(lookupCtx, "ip", domain)
		if err != nil {
			return nil, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if epoch.ctx.Err() != nil {
		return nil, context.Cause(epoch.ctx)
	}
	if lookupCtx.Err() != nil {
		return nil, context.Cause(lookupCtx)
	}
	if s.closed || s.current != epoch {
		return nil, errNetworkChanged
	}
	return ips, nil
}

func (s *networkSession) checkDirectDNS(ctx context.Context) error {
	epoch, _, operation, err := s.beginOperation(ctx)
	if err != nil {
		return err
	}
	defer s.endOperation(epoch, operation)
	if _, protected := epoch.dialer.(*protectedDialer); protected {
		return errors.New("XRAY_DIRECT_SYSTEM_DNS_UNSUPPORTED")
	}
	return nil
}

func (s *networkSession) Dial(ctx context.Context, src xnet.Address, dest xnet.Destination, options *internet.SocketConfig) (net.Conn, error) {
	epoch, dialCtx, operation, err := s.beginOperation(ctx)
	if err != nil {
		return nil, err
	}
	defer s.endOperation(epoch, operation)
	conn, err := epoch.dialer.Dial(dialCtx, src, dest, options)
	if err != nil {
		if epoch.ctx.Err() != nil {
			return nil, context.Cause(epoch.ctx)
		}
		if dialCtx.Err() != nil {
			return nil, context.Cause(dialCtx)
		}
		return nil, err
	}
	s.mu.Lock()
	if s.closed || s.current != epoch || epoch.ctx.Err() != nil || dialCtx.Err() != nil {
		s.mu.Unlock()
		conn.Close()
		if epoch.ctx.Err() != nil {
			return nil, context.Cause(epoch.ctx)
		}
		if dialCtx.Err() != nil {
			return nil, context.Cause(dialCtx)
		}
		return nil, errNetworkChanged
	}
	managed := &sessionConn{Conn: conn, owner: s, epoch: epoch}
	epoch.connections[managed] = struct{}{}
	s.mu.Unlock()
	if packet, ok := conn.(net.PacketConn); ok {
		// Xray's Hysteria transport requires this concrete wrapper, not merely
		// net.PacketConn. Keep ownership/cancellation inside its packet layer so
		// QUIC and mask consumers cannot bypass retirement by unwrapping it.
		return &internet.PacketConnWrapper{
			PacketConn: &sessionPacketConn{sessionConn: managed, packet: packet},
			Dest:       conn.RemoteAddr(),
		}, nil
	}
	return managed, nil
}

type sessionConn struct {
	net.Conn
	owner    *networkSession
	epoch    *networkEpoch
	once     sync.Once
	closeErr error
}

func (c *sessionConn) Close() error {
	c.once.Do(func() {
		c.owner.mu.Lock()
		delete(c.epoch.connections, c)
		c.owner.mu.Unlock()
		c.closeErr = c.Conn.Close()
	})
	return c.closeErr
}
func (c *sessionConn) Read(b []byte) (int, error) {
	if c.epoch.ctx.Err() != nil {
		return 0, context.Cause(c.epoch.ctx)
	}
	return c.Conn.Read(b)
}
func (c *sessionConn) Write(b []byte) (int, error) {
	if c.epoch.ctx.Err() != nil {
		return 0, context.Cause(c.epoch.ctx)
	}
	return c.Conn.Write(b)
}

type sessionPacketConn struct {
	*sessionConn
	packet net.PacketConn
}

func (c *sessionPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	if c.epoch.ctx.Err() != nil {
		return 0, nil, context.Cause(c.epoch.ctx)
	}
	return c.packet.ReadFrom(b)
}
func (c *sessionPacketConn) WriteTo(b []byte, a net.Addr) (int, error) {
	if c.epoch.ctx.Err() != nil {
		return 0, context.Cause(c.epoch.ctx)
	}
	return c.packet.WriteTo(b, a)
}
