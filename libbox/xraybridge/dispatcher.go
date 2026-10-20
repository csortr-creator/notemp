package xraybridge

import (
	"context"
	"errors"
	"net"
	"sync"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

var ErrSessionMissingOrStopped = errors.New("XRAY_SESSION_IDENTITY_MISSING_OR_STOPPED")

type sessionKey struct{}

type processDispatcher struct {
	mu       sync.RWMutex
	sessions map[*networkSession]struct{}
}

var globalDispatcher = &processDispatcher{sessions: make(map[*networkSession]struct{})}

// Install exactly once, during package initialization, before any executor can
// start goroutines. Never swap a process-wide hook on Start/Stop/network change.
func init() { internet.UseAlternativeSystemDialer(globalDispatcher) }

func (d *processDispatcher) register(s *networkSession) {
	d.mu.Lock()
	d.sessions[s] = struct{}{}
	d.mu.Unlock()
}

func (d *processDispatcher) retire(s *networkSession) {
	if s == nil {
		return
	}
	d.mu.Lock()
	delete(d.sessions, s)
	d.mu.Unlock()
	_ = s.Close()
}

func (d *processDispatcher) Dial(ctx context.Context, src xnet.Address, dest xnet.Destination, options *internet.SocketConfig) (net.Conn, error) {
	if ctx == nil {
		return nil, ErrSessionMissingOrStopped
	}
	s, ok := ctx.Value(sessionKey{}).(*networkSession)
	if !ok || s == nil {
		return nil, ErrSessionMissingOrStopped
	}
	d.mu.RLock()
	_, registered := d.sessions[s]
	d.mu.RUnlock()
	if !registered {
		return nil, ErrSessionMissingOrStopped
	}
	// A concurrent retirement also closes the owner, cancelling any dial that
	// raced this membership check. The owner never migrates to another Bridge.
	return s.Dial(ctx, src, dest, options)
}

func (*processDispatcher) DestIpAddress() xnet.IP { return nil }
