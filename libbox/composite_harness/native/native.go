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
