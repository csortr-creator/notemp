package xraybridge

import (
	"context"
	"encoding/json"
	"golang.org/x/net/proxy"
	"net"
	"testing"
	"time"
)

// TestBridgeStartStopAndSocks verifies that we can start the bridge,
// connect through its loopback SOCKS inbound, pass traffic, and
// subsequently stop it cleanly.
func TestBridgeStartStopAndSocks(t *testing.T) {
	b := NewBridge()

	// Synthetic fixture credentials; production callers must use a CSPRNG.
	user := "xray_user"
	pass := "xray_fixture_password_0123456789abcdef"

	config := map[string]interface{}{
		"inbounds": []interface{}{
			map[string]interface{}{
				"port":     10800,
				"listen":   "127.0.0.1",
				"protocol": "socks",
				"settings": map[string]interface{}{
					"auth": "password",
					"accounts": []interface{}{
						map[string]interface{}{
							"user": user,
							"pass": pass,
						},
					},
					"udp": true,
				},
			},
		},
		"outbounds": []interface{}{
			map[string]interface{}{
				"protocol": "freedom",
			},
		},
	}

	configBytes, _ := json.Marshal(config)
	if err := b.Start(context.Background(), configBytes); err != nil {
		t.Fatalf("failed to start: %v", err)
	}
	defer b.Stop()

	// Wait a moment for Xray to bind the listener
	time.Sleep(1 * time.Second)

	auth := &proxy.Auth{User: user, Password: pass}
	dialer, err := proxy.SOCKS5("tcp", "127.0.0.1:10800", auth, proxy.Direct)
	if err != nil {
		t.Fatalf("failed to create socks proxy: %v", err)
	}

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen local tcp: %v", err)
	}
	defer l.Close()

	go func() {
		conn, err := l.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		conn.Write([]byte("ok"))
	}()

	conn, err := dialer.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatalf("failed to dial through proxy: %v", err)
	}
	defer conn.Close()

	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("failed to read from connection: %v", err)
	}
	if string(buf[:n]) != "ok" {
		t.Fatalf("expected ok, got %s", string(buf[:n]))
	}
	wrong, err := proxy.SOCKS5("tcp", "127.0.0.1:10800", &proxy.Auth{User: user, Password: "wrong"}, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	unexpected, err := wrong.Dial("tcp", l.Addr().String())
	if err == nil {
		unexpected.Close()
		t.Fatal("wrong SOCKS password accepted")
	}
}

func TestBridgeMultipleStart(t *testing.T) {
	b := NewBridge()
	config := map[string]interface{}{
		"inbounds": []interface{}{
			map[string]interface{}{
				"port":     10802,
				"listen":   "127.0.0.1",
				"protocol": "socks",
				"settings": map[string]interface{}{
					"auth":     "password",
					"accounts": []interface{}{map[string]interface{}{"user": "fixture", "pass": "xray_fixture_password_0123456789abcdef"}},
				},
			},
		},
		"outbounds": []interface{}{
			map[string]interface{}{
				"protocol": "freedom",
			},
		},
	}
	configBytes, _ := json.Marshal(config)
	if err := b.Start(context.Background(), configBytes); err != nil {
		t.Fatalf("failed to start: %v", err)
	}
	if err := b.Stop(); err != nil {
		t.Fatalf("failed to stop: %v", err)
	}

	if err := b.Start(context.Background(), configBytes); err != nil {
		t.Fatalf("failed to restart: %v", err)
	}
	b.Stop()
}
