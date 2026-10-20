package xraybridge

import (
	"bytes"
	"context"
	"fmt"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Bridge encapsulates an Xray core instance. It operates as a composite
// handler for specialized protocols unsupported natively by sing-box,
// such as specific FinalMask and XHTTP features.
type Bridge struct {
	instance *core.Instance
}

// NewBridge creates a new Bridge.
func NewBridge() *Bridge {
	return &Bridge{}
}

// Start launches the embedded Xray core with the provided JSON configuration.
// By contract, this configuration should open a loopback SOCKS inbound
// with a random auth token.
func (b *Bridge) Start(ctx context.Context, configJson []byte) error {
	config, err := serial.DecodeJSONConfig(bytes.NewReader(configJson))
	if err != nil {
		return fmt.Errorf("failed to decode json config: %w", err)
	}
	coreConfig, err := config.Build()
	if err != nil {
		return fmt.Errorf("failed to build core config: %w", err)
	}
	instance, err := core.New(coreConfig)
	if err != nil {
		return fmt.Errorf("failed to create core instance: %w", err)
	}
	if err := instance.Start(); err != nil {
		return fmt.Errorf("failed to start core instance: %w", err)
	}
	b.instance = instance
	return nil
}

// Stop cleanly terminates the embedded Xray core instance.
func (b *Bridge) Stop() error {
	if b.instance != nil {
		return b.instance.Close()
	}
	return nil
}
