package composite_test

import (
	"context"
	"fmt"
	"net"
	"testing"

	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/xraybridge"
)

// This imports the actual patched libbox, not a second reference to Xray.
// It is a host dependency/initialization proof, not an Android AAR/JNI test.
func TestCompositeInitialization(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	config := fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"fixture","pass":"composite_fixture_password_0123456789abcdef"}]}}],"outbounds":[{"protocol":"freedom"}]}`, port)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	bridge := xraybridge.NewBridge()
	if err = bridge.Start(ctx, []byte(config)); err != nil {
		t.Fatal(err)
	}
	defer bridge.Stop()
	if err = libbox.CheckConfig(`{"outbounds":[{"type":"direct","tag":"direct"}]}`); err != nil {
		t.Fatal(err)
	}
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if err = bridge.Stop(); err != nil {
		t.Fatal(err)
	}
}
