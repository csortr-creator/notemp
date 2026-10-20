package xraybridge

import (
	"context"
	"encoding/json"
	"testing"
)

// TestBridgeXrayFeatures checks XHTTP config startup only.
// It is not a data-plane or FinalMask interoperability test. Due to the lack of
// existing functional local XHTTP endpoints in test, this test verifies
// that the configs are parsed correctly and bridge spins up/down
// cleanly without config errors on those specific features.
func TestBridgeXrayFeatures(t *testing.T) {
	b := NewBridge()
	config := map[string]interface{}{
		"inbounds": []interface{}{
			map[string]interface{}{
				"port":     10801,
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
				"protocol": "vless",
				"settings": map[string]interface{}{
					"vnext": []interface{}{
						map[string]interface{}{
							"address": "127.0.0.1",
							"port":    443,
							"users": []interface{}{
								map[string]interface{}{
									"id":         "00000000-0000-0000-0000-000000000000",
									"encryption": "none",
								},
							},
						},
					},
				},
				"streamSettings": map[string]interface{}{
					"network":  "xhttp",
					"security": "tls",
					"xhttpSettings": map[string]interface{}{
						"mode": "stream-up", // Required stream-up verification
						"extra": map[string]interface{}{
							"path": "/",
						},
					},
					"tlsSettings": map[string]interface{}{
						"serverName": "example.com",
					},
				},
			},
		},
	}
	configBytes, _ := json.Marshal(config)
	if err := b.Start(context.Background(), configBytes); err != nil {
		t.Fatalf("failed to start bridge with xhttp config: %v", err)
	}
	if err := b.Stop(); err != nil {
		t.Fatalf("failed to stop: %v", err)
	}
}

// This fixture contains no FinalMask. Passing startup is not a mask wire proof.
func TestBridgeTCPDialerProxyConfigSmoke(t *testing.T) {
	b := NewBridge()
	config := map[string]interface{}{
		"inbounds": []interface{}{
			map[string]interface{}{
				"port":     10803,
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
				"protocol": "vless",
				"settings": map[string]interface{}{
					"vnext": []interface{}{
						map[string]interface{}{
							"address": "127.0.0.1",
							"port":    443,
							"users": []interface{}{
								map[string]interface{}{
									"id":         "00000000-0000-0000-0000-000000000000",
									"encryption": "none",
								},
							},
						},
					},
				},
				"streamSettings": map[string]interface{}{
					"network":     "tcp",
					"security":    "tls",
					"tcpSettings": map[string]interface{}{},
					"sockopt": map[string]interface{}{
						"dialerProxy": "freedom",
					},
				},
			},
		},
	}
	configBytes, _ := json.Marshal(config)
	if err := b.Start(context.Background(), configBytes); err != nil {
		t.Fatalf("failed to start bridge with finalmask config: %v", err)
	}
	if err := b.Stop(); err != nil {
		t.Fatalf("failed to stop: %v", err)
	}
}
