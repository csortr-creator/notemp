package composite

import (
	"context"
	"fmt"
	"sync"

	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/xraybridge"
)

type ExecutorFacade struct {
	mu     sync.Mutex
	bridge *xraybridge.Bridge
	cancel context.CancelFunc
}

func NewExecutorFacade() *ExecutorFacade {
	return &ExecutorFacade{}
}

func (f *ExecutorFacade) StartBridge(config string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.bridge != nil {
		return fmt.Errorf("bridge already started")
	}
	ctx, cancel := context.WithCancel(context.Background())
	bridge := xraybridge.NewBridge()
	if err := bridge.Start(ctx, []byte(config)); err != nil {
		cancel()
		return err
	}
	f.cancel = cancel
	f.bridge = bridge
	return nil
}

// StartProtectedBridge never falls back to host networking. The provider must
// capture a non-VPN Android network and implement protection, binding and DNS.
func (f *ExecutorFacade) StartProtectedBridge(config string, provider NetworkProvider) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.bridge != nil {
		return fmt.Errorf("bridge already started")
	}
	if provider == nil {
		return fmt.Errorf("network provider required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	bridge := xraybridge.NewBridge()
	adapter := providerAdapter{provider}
	if err := bridge.StartProtected(ctx, []byte(config), adapter.protect, adapter.resolve); err != nil {
		cancel()
		return err
	}
	f.cancel, f.bridge = cancel, bridge
	return nil
}

// UpdateBridgeNetwork retires old DNS, dials and connected sockets before using
// this provider. Host bridges cannot be converted to protected bridges.
func (f *ExecutorFacade) UpdateBridgeNetwork(provider NetworkProvider) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.bridge == nil {
		return fmt.Errorf("bridge not started")
	}
	if provider == nil {
		return fmt.Errorf("network provider required")
	}
	adapter := providerAdapter{provider}
	return f.bridge.UpdateNetwork(adapter.protect, adapter.resolve)
}

func (f *ExecutorFacade) StopBridge() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.bridge == nil {
		return nil
	}
	err := f.bridge.Stop()
	if f.cancel != nil {
		f.cancel()
		f.cancel = nil
	}
	f.bridge = nil
	return err
}

func (f *ExecutorFacade) CheckSingboxConfig(config string) error {
	return libbox.CheckConfig(config)
}
