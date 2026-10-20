package xraybridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet/finalmask/header/custom"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"golang.org/x/net/proxy"
)

const wirePassword = "xray_fixture_password_0123456789abcdef"

func wirePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func wireStream(header string) map[string]any {
	stream := map[string]any{"network": "xhttp", "xhttpSettings": map[string]any{
		"mode": "stream-up", "path": "/fixture", "extra": map[string]any{"xPaddingBytes": "112-1344"},
	}}
	if header != "" {
		stream["finalmask"] = map[string]any{"tcp": []any{map[string]any{
			"type": "header-custom", "settings": map[string]any{
				"clients": [][]any{{map[string]any{"type": "str", "packet": header}}},
				"servers": [][]any{{map[string]any{"type": "str", "packet": "server-response"}}},
			},
		}}}
	}
	return stream
}

func assertWireMask(t *testing.T, stream *conf.StreamConfig, header string) {
	t.Helper()
	if stream == nil {
		t.Fatal("stream config missing")
	}
	built, err := stream.Build()
	if err != nil {
		t.Fatal(err)
	}
	var checkedTransport bool
	for _, transport := range built.TransportSettings {
		if transport.ProtocolName != "splithttp" {
			continue
		}
		message, err := transport.Settings.GetInstance()
		if err != nil {
			t.Fatal(err)
		}
		settings, ok := message.(*splithttp.Config)
		if !ok || settings.Mode != "stream-up" || settings.Path != "/fixture" ||
			settings.XPaddingBytes == nil || settings.XPaddingBytes.From != 112 || settings.XPaddingBytes.To != 1344 {
			t.Fatal("built XHTTP config lost mode, path or padding from extra")
		}
		checkedTransport = true
	}
	if !checkedTransport {
		t.Fatal("built config has no XHTTP transport")
	}
	if header == "" {
		if len(built.Tcpmasks) != 0 {
			t.Fatal("unexpected TCP mask")
		}
		return
	}
	if len(built.Tcpmasks) != 1 {
		t.Fatal("FinalMask was ignored by the config builder")
	}
	m, err := built.Tcpmasks[0].GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	mask, ok := m.(*custom.TCPConfig)
	if !ok || len(mask.Clients) != 1 || len(mask.Clients[0].Sequence) != 1 ||
		!bytes.Equal(mask.Clients[0].Sequence[0].Packet, []byte(header)) {
		t.Fatal("built header mask does not contain the configured bytes")
	}
}

func TestWireXHTTPHeaderMask(t *testing.T) {
	for _, test := range []struct {
		name, clientHeader string
		wantExchange       bool
	}{
		{"matching", "client-header", true},
		{"wrong", "different-key", false},
		{"missing", "", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			target, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer target.Close()
			// A fixed server response cannot become a false positive through symmetric echo.
			reply := []byte("fixture-response-from-destination")
			served := make(chan error, 1)
			go func() {
				c, err := target.Accept()
				if err != nil {
					served <- err
					return
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(4 * time.Second))
				request := make([]byte, 4)
				if _, err = io.ReadFull(c, request); err == nil && bytes.Equal(request, []byte("ping")) {
					_, err = c.Write(reply)
				}
				served <- err
			}()
			port := wirePort(t)
			server := map[string]any{
				"log": map[string]any{"loglevel": "none"},
				"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless",
					"settings":       map[string]any{"clients": []any{map[string]any{"id": "11111111-1111-1111-1111-111111111111"}}, "decryption": "none"},
					"streamSettings": wireStream("client-header")}},
				"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{
					// Modern Xray blocks private targets for public proxy inbounds by default.
					// Permit only this fixture endpoint, preserving the rest of that policy.
					"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1/32"}, "port": target.Addr().(*net.TCPAddr).Port}},
				}}},
			}
			raw, err := json.Marshal(server)
			if err != nil {
				t.Fatal(err)
			}
			parsed, err := serial.DecodeJSONConfig(bytes.NewReader(raw))
			if err != nil {
				t.Fatal(err)
			}
			assertWireMask(t, parsed.InboundConfigs[0].StreamSetting, "client-header")
			cfg, err := parsed.Build()
			if err != nil {
				t.Fatal(err)
			}
			instance, err := core.NewWithContext(ctx, cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}

			clientPort := wirePort(t)
			client := map[string]any{
				"log": map[string]any{"loglevel": "none"},
				"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": clientPort, "protocol": "socks",
					"settings": map[string]any{"auth": "password", "accounts": []any{map[string]any{"user": "fixture", "pass": wirePassword}}}}},
				"outbounds": []any{map[string]any{"protocol": "vless",
					"settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": port,
						"users": []any{map[string]any{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}}}}},
					"streamSettings": wireStream(test.clientHeader)}},
			}
			raw, err = json.Marshal(client)
			if err != nil {
				t.Fatal(err)
			}
			parsed, err = serial.DecodeJSONConfig(bytes.NewReader(raw))
			if err != nil {
				t.Fatal(err)
			}
			assertWireMask(t, parsed.OutboundConfigs[0].StreamSetting, test.clientHeader)
			bridge := NewBridge()
			if err = bridge.Start(ctx, raw); err != nil {
				t.Fatal(err)
			}
			defer bridge.Stop()
			dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", clientPort),
				&proxy.Auth{User: "fixture", Password: wirePassword}, &net.Dialer{Timeout: 2 * time.Second})
			if err != nil {
				t.Fatal(err)
			}
			dialCtx, stop := context.WithTimeout(ctx, 2*time.Second)
			defer stop()
			c, err := dialer.(proxy.ContextDialer).DialContext(dialCtx, "tcp", target.Addr().String())
			// A local SOCKS failure is a broken fixture, not evidence of mask rejection.
			if err != nil {
				t.Fatalf("SOCKS setup: %v", err)
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(2 * time.Second))
			_, writeErr := c.Write([]byte("ping"))
			response := make([]byte, len(reply))
			_, readErr := io.ReadFull(c, response)
			if test.wantExchange {
				if writeErr != nil || readErr != nil || !bytes.Equal(response, reply) {
					t.Fatalf("matching mask exchange failed: write=%v read=%v", writeErr, readErr)
				}
				if err := <-served; err != nil {
					t.Fatal(err)
				}
			} else if readErr == nil || bytes.Equal(response, reply) {
				t.Fatal("destination response crossed a wrong or absent mask")
			}
			target.Close()
		})
	}
}
