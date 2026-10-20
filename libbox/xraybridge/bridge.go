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
	cancelWatch func() bool
}

func NewBridge() *Bridge { return &Bridge{} }

func (b *Bridge) Start(ctx context.Context, configJSON []byte) error {
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
	instance, err := core.NewWithContext(ctx, coreConfig)
	if err != nil {
		return errors.New("XRAY_INSTANCE_CREATE_FAILED")
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return errors.New("XRAY_INSTANCE_START_FAILED")
	}
	if err := ctx.Err(); err != nil {
		_ = instance.Close()
		return err
	}
	b.instance = instance
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
	if b.cancelWatch != nil {
		b.cancelWatch()
		b.cancelWatch = nil
	}
	// Keep restart serialized until listeners have actually been released.
	if err := instance.Close(); err != nil {
		return errors.New("XRAY_INSTANCE_STOP_FAILED")
	}
	return nil
}
