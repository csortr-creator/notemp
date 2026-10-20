package xraybridge

import (
	"bufio"
	"bytes"
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"golang.org/x/net/proxy"
)

const hysteriaSessionChild = "NOTEMP_HYSTERIA_SESSION_CHILD"

// The child uses the same permanent dispatcher and StartProtected as every
// protected executor. Its callbacks are never installed process-wide.
// This verifies the real Hysteria TCP-over-QUIC path, not SOCKS UDP or Android.
func TestNetworkSessionHysteriaTransport(t *testing.T) {
	if raw := os.Getenv(hysteriaSessionChild); raw != "" {
		runHysteriaSessionChild(t, raw)
		return
	}
	var requests atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		fmt.Fprint(w, "network-owner-hysteria-reply")
	}))
	defer origin.Close()
	target := strings.TrimPrefix(origin.URL, "http://")
	_, targetPort, err := net.SplitHostPort(target)
	if err != nil {
		t.Fatal(err)
	}
	portNumber, err := strconv.Atoi(targetPort)
	if err != nil {
		t.Fatal(err)
	}

	certificateServer := httptest.NewTLSServer(http.NotFoundHandler())
	defer certificateServer.Close()
	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificateServer.TLS.Certificates[0].Certificate[0]})
	key, err := x509.MarshalPKCS8PrivateKey(certificateServer.TLS.Certificates[0].PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certFile, keyFile := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	if err := os.WriteFile(certFile, cert, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}), 0600); err != nil {
		t.Fatal(err)
	}
	reserve, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reserve.LocalAddr().(*net.UDPAddr).Port
	reserve.Close()
	stream := func(server bool) map[string]any {
		tls := map[string]any{"alpn": []string{"h3"}}
		hy := map[string]any{"version": 2}
		if server {
			// This fixture's certificate is immutable. Do not run Xray's unrelated
			// certificate hot-reload ticker (which races its TLS certificate slice).
			tls["certificates"] = []any{map[string]any{"certificateFile": certFile, "keyFile": keyFile, "oneTimeLoading": true}}
		} else {
			tls["serverName"], tls["disableSystemRoot"] = "127.0.0.1", true
			tls["certificates"] = []any{map[string]any{"certificateFile": certFile, "usage": "verify"}}
			hy["auth"] = "synthetic-hysteria-auth"
		}
		return map[string]any{"network": "hysteria", "security": "tls", "tlsSettings": tls, "hysteriaSettings": hy,
			"finalmask": map[string]any{"udp": []any{map[string]any{"type": "salamander", "settings": map[string]any{"password": "synthetic-mask"}}}}}
	}
	serverJSON, err := json.Marshal(map[string]any{
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "hysteria",
			"settings": map[string]any{"version": 2, "users": []any{map[string]any{"auth": "synthetic-hysteria-auth"}}}, "streamSettings": stream(true)}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{
			"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1/32"}, "port": portNumber}}}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := serial.DecodeJSONConfig(bytes.NewReader(serverJSON))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parsed.Build()
	if err != nil {
		t.Fatal(err)
	}
	server, err := core.NewWithContext(ownedHostTestContext(t, context.Background()), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	var client map[string]any
	if err := json.Unmarshal(fixtureConfig(t, freePort(t)), &client); err != nil {
		t.Fatal(err)
	}
	client["outbounds"] = []any{map[string]any{"protocol": "hysteria", "settings": map[string]any{
		"version": 2, "address": "owner-fixture.invalid", "port": port}, "streamSettings": stream(false)}}
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	for _, deny := range []bool{false, true} {
		name := "protected-exchange"
		if deny {
			name = "denied-protection"
		}
		if !t.Run(name, func(t *testing.T) {
			payload, err := json.Marshal(hysteriaChildConfig{Config: client, Target: target, Deny: deny})
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, exe, "-test.run=^TestNetworkSessionHysteriaTransport$", "-test.timeout=15s")
			cmd.Env = append(os.Environ(), hysteriaSessionChild+"="+string(payload))
			before := requests.Load()
			output, err := cmd.CombinedOutput()
			if err != nil {
				t.Fatalf("Hysteria child failed: %v\n%s", err, output)
			}
			expected := int32(1)
			if deny {
				expected = 0
			}
			if got := requests.Load() - before; got != expected {
				t.Fatalf("destination requests=%d, want %d", got, expected)
			}
		}) {
			return
		} // A negative result is meaningful only after the positive control.
	}
}

type hysteriaChildConfig struct {
	Config map[string]any
	Target string
	Deny   bool
}

func runHysteriaSessionChild(t *testing.T, raw string) {
	var fixture hysteriaChildConfig
	if err := json.Unmarshal([]byte(raw), &fixture); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	var protected, resolved atomic.Int32
	protect := socketProtector(
		func(context.Context, string, uintptr) error {
			protected.Add(1)
			if fixture.Deny {
				return fmt.Errorf("synthetic protection denied")
			}
			return nil
		})
	resolve := domainResolver(func(_ context.Context, domain string) ([]netip.Addr, error) {
		if domain != "owner-fixture.invalid" {
			return nil, fmt.Errorf("unexpected resolver target: %s", domain)
		}
		resolved.Add(1)
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	})
	config, err := json.Marshal(fixture.Config)
	if err != nil {
		t.Fatal(err)
	}
	bridge := NewBridge()
	if err := bridge.StartProtected(ctx, config, protect, resolve); err != nil {
		t.Fatal(err)
	}
	defer bridge.Stop()
	inbound := fixture.Config["inbounds"].([]any)[0].(map[string]any)
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(int(inbound["port"].(float64))))
	dialer, err := proxy.SOCKS5("tcp", address, &proxy.Auth{User: "fixture", Password: "xray_fixture_password_0123456789abcdef"}, &net.Dialer{Timeout: 3 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	conn, exchangeErr := dialer.(proxy.ContextDialer).DialContext(ctx, "tcp", fixture.Target)
	if exchangeErr == nil {
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(6 * time.Second))
		_, exchangeErr = fmt.Fprintf(conn, "GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n")
		if exchangeErr == nil {
			var response *http.Response
			response, exchangeErr = http.ReadResponse(bufio.NewReader(conn), nil)
			if exchangeErr == nil {
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				exchangeErr = err
				if string(body) != "network-owner-hysteria-reply" {
					t.Fatalf("unexpected destination response: %q", body)
				}
			}
		}
	}
	if resolved.Load() == 0 || protected.Load() == 0 {
		t.Fatalf("actual Xray path missed callbacks: DNS=%d protection=%d", resolved.Load(), protected.Load())
	}
	if fixture.Deny {
		if exchangeErr == nil {
			t.Fatal("denied protection reached destination")
		}
	} else if exchangeErr != nil {
		t.Fatalf("protected Hysteria exchange: %v", exchangeErr)
	}
}
