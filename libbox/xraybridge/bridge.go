package xraybridge

import (
	"bytes"
	"context"
	"errors"
	"sync"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var ErrStarted = errors.New("XRAY_ALREADY_STARTED")

// Bridge is an isolated experimental executor, not an Android/JNI integration.
// Calls are serialized; a retired context can never close a replacement instance.
type Bridge struct {
	mu          sync.Mutex
	instance    *core.Instance
	session     *networkSession
	protected   bool
	cancelWatch func() bool
}

func NewBridge() *Bridge { return &Bridge{} }

// Start is the explicit host-only executor. Android callers must use StartProtected.
func (b *Bridge) Start(ctx context.Context, configJSON []byte) error {
	return b.start(ctx, configJSON, nil, nil, false)
}

// StartProtected requires callbacks which protect AND bind sockets, and resolve
// names using the underlying network. No fallback to host DNS/sockets is allowed.
func (b *Bridge) StartProtected(ctx context.Context, configJSON []byte, protect socketProtector, resolve domainResolver) error {
	if protect == nil {
		return errProtectionRequired
	}
	if resolve == nil {
		return errResolverRequired
	}
	return b.start(ctx, configJSON, protect, resolve, true)
}

func (b *Bridge) UpdateNetwork(protect socketProtector, resolve domainResolver) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.session == nil {
		return errNetworkClosed
	}
	if !b.protected {
		return errors.New("XRAY_PROTECTED_SESSION_REQUIRED")
	}
	return b.session.Update(protect, resolve)
}

// SuspendNetwork fails closed during underlying-network loss, retaining the
// authenticated local listener so UpdateNetwork can resume this protected owner.
func (b *Bridge) SuspendNetwork() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.session == nil {
		return errNetworkClosed
	}
	if !b.protected {
		return errors.New("XRAY_PROTECTED_SESSION_REQUIRED")
	}
	return b.session.Suspend()
}

func (b *Bridge) start(ctx context.Context, configJSON []byte, protect socketProtector, resolve domainResolver, protected bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.instance != nil {
		return ErrStarted
	}
	if ctx == nil {
		return errors.New("XRAY_CONTEXT_REQUIRED")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := validateBridgePolicy(configJSON); err != nil {
		return err
	}
	config, err := serial.DecodeJSONConfig(bytes.NewReader(configJSON))
	if err != nil {
		return errors.New("XRAY_CONFIG_DECODE_FAILED")
	}
	coreConfig, err := config.Build()
	if err != nil {
		return errors.New("XRAY_CONFIG_BUILD_FAILED")
	}
	var owner *networkSession
	if protected {
		owner, err = newNetworkSession(ctx, protect, resolve)
	} else {
		owner, err = newHostNetworkSession(ctx)
	}
	if err != nil {
		return err
	}
	globalDispatcher.register(owner)
	committed := false
	defer func() {
		if !committed {
			globalDispatcher.retire(owner)
		}
	}()
	ctx = context.WithValue(ctx, sessionKey{}, owner)
	instance, err := core.NewWithContext(ctx, coreConfig)
	if err != nil {
		return errors.New("XRAY_INSTANCE_CREATE_FAILED")
	}
	if err := instance.Start(); err != nil {
		globalDispatcher.retire(owner)
		_ = instance.Close()
		return errors.New("XRAY_INSTANCE_START_FAILED")
	}
	if err := ctx.Err(); err != nil {
		globalDispatcher.retire(owner)
		_ = instance.Close()
		return err
	}
	b.instance = instance
	b.session, b.protected = owner, protected
	committed = true
	b.cancelWatch = context.AfterFunc(ctx, func() { _ = b.stopInstance(instance) })
	return nil
}

func (b *Bridge) Stop() error { return b.stopInstance(nil) }

func (b *Bridge) stopInstance(expected *core.Instance) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.instance == nil || (expected != nil && b.instance != expected) {
		return nil
	}
	instance := b.instance
	b.instance = nil
	owner := b.session
	b.session = nil
	b.protected = false
	if b.cancelWatch != nil {
		b.cancelWatch()
		b.cancelWatch = nil
	}
	// Cancel DNS/dials and close sockets before core shutdown can wait on them.
	globalDispatcher.retire(owner)
	// Keep restart serialized until listeners have actually been released.
	if err := instance.Close(); err != nil {
		return errors.New("XRAY_INSTANCE_STOP_FAILED")
	}
	return nil
}
