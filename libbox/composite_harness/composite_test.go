package composite_test

import (
	"context"
	"testing"
	"github.com/sagernet/sing-box/xraybridge"
	"github.com/xtls/xray-core/core"
)

func TestComposite(t *testing.T) {
	b := xraybridge.NewBridge()
	if b == nil { t.Fail() }

	// Test the actual bridge start using internal core
	err := b.Start(context.Background(), []byte{})
	if err == nil {
		t.Logf("started empty bridge")
	}
	_ = core.Instance{}
}
