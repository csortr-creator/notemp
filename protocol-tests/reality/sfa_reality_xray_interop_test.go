//go:build with_utls

package tls

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/vlessenc"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
)

func TestSFARealityXrayInterop(t *testing.T) {
	sfaRunRealityXrayMatrix(t, "")
}

func sfaRunRealityXrayMatrix(t *testing.T, flow string) {
	for _, binary := range []struct{ name, path string }{
		{"legacy", os.Getenv("XHTTP_XRAY_BINARY")},
		{"modern", os.Getenv("SFA_REALITY_XRAY_BINARY")},
	} {
		t.Run(binary.name, func(t *testing.T) {
			if binary.path == "" {
				t.Skip("Xray fixture binary is not configured")
			}
			sfaCheckRealityXray(t, binary.path, flow)
		})
	}
}

func sfaCheckRealityXray(t *testing.T, binary, flow string) {
	sfaCheckRealityCombination(t, binary, flow, "chrome", "")
}

func sfaCheckRealityCombination(t *testing.T, binary, flow, fingerprint, encryptionMode string, hybrid ...*bool) {
	target := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	target.Config.ErrorLog = log.New(io.Discard, "", 0)
	target.EnableHTTP2 = true
	target.TLS = &tls.Config{MinVersion: tls.VersionTLS13, CurvePreferences: []tls.CurveID{tls.X25519MLKEM768, tls.X25519}}
	target.StartTLS()
	t.Cleanup(func() { target.CloseClientConnections(); target.Close() })
	payload := bytes.Repeat([]byte("verified-vision-stream\n"), 65536)
	origin := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if encryptionMode == "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		uploaded, err := io.ReadAll(io.LimitReader(r.Body, int64(len(payload)+1)))
		if err != nil || !bytes.Equal(uploaded, payload) {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Write(payload)
	}))
	origin.Config.ErrorLog = log.New(io.Discard, "", 0)
	originListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	origin.Listener.Close()
	origin.Listener = originListener
	origin.StartTLS()
	t.Cleanup(func() { origin.CloseClientConnections(); origin.Close() })
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encryption, decryption := "none", "none"
	if encryptionMode != "" {
		encryptionKey, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		encryption = "mlkem768x25519plus.native." + encryptionMode + "." + base64.RawURLEncoding.EncodeToString(encryptionKey.PublicKey().Bytes())
		decryption = "mlkem768x25519plus.native.600s." + base64.RawURLEncoding.EncodeToString(encryptionKey.Bytes())
	}
	encryptionClient, err := vlessenc.NewClient(encryption)
	if err != nil {
		t.Fatal(err)
	}
	address := sfaStartRealityXray(t, binary, target.Listener.Addr().String(), key.Bytes(), decryption)
	cases := []struct {
		name, shortID, flow string
		validKey, success   bool
	}{
		{"valid", "abcd", flow, true, true},
		{"valid-reconnect", "abcd", flow, true, true},
		{"wrong-short-id", "fefe", "", true, false},
		{"wrong-public-key", "abcd", "", false, false},
	}
	if encryptionMode != "" && fingerprint == "chrome" {
		cases = append(cases, struct {
			name, shortID, flow string
			validKey, success   bool
		}{"wrong-encryption-key", "abcd", flow, true, true})
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			public := key.PublicKey().Bytes()
			if !tc.validKey {
				other, err := ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
				public = other.PublicKey().Bytes()
			}
			var supportHybrid *bool
			if len(hybrid) != 0 {
				supportHybrid = hybrid[0]
			}
			config, err := NewRealityClient(context.Background(), logger.NOP(), "example.com", option.OutboundTLSOptions{
				Enabled: true, ServerName: "example.com",
				UTLS:    &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fingerprint},
				Reality: &option.OutboundRealityOptions{SupportX25519MLKEM768: supportHybrid, Enabled: true, PublicKey: base64.RawURLEncoding.EncodeToString(public), ShortID: tc.shortID},
			})
			if err != nil {
				t.Fatal(err)
			}
			connection, err := net.DialTimeout("tcp", address, 3*time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			// Xray probes camouflage post-handshake records asynchronously, retrying
			// at five-second intervals. TCP listening does not mean this probe is ready.
			_ = connection.SetDeadline(time.Now().Add(20 * time.Second))
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			authenticated, err := ClientHandshake(ctx, connection, config)
			if tc.success {
				if err != nil {
					t.Fatalf("valid Reality handshake rejected: %v", err)
				}
				defer authenticated.Close()
				if authenticated.ConnectionState().Version != tls.VersionTLS13 {
					t.Fatal("Reality handshake did not use TLS 1.3")
				}
				uuid := "00000000-0000-0000-0000-000000000001"
				if tc.flow != "" {
					uuid = "00000000-0000-0000-0000-000000000002"
				}
				client, err := vless.NewClient(uuid, tc.flow, logger.NOP())
				if err != nil {
					t.Fatal(err)
				}
				var stream net.Conn = authenticated
				if encryptionClient != nil {
					activeEncryption := encryptionClient
					if tc.name == "wrong-encryption-key" {
						wrong, err := ecdh.X25519().GenerateKey(rand.Reader)
						if err != nil {
							t.Fatal(err)
						}
						activeEncryption, err = vlessenc.NewClient("mlkem768x25519plus.native." + encryptionMode + "." + base64.RawURLEncoding.EncodeToString(wrong.PublicKey().Bytes()))
						if err != nil {
							t.Fatal(err)
						}
						var cancelNegative context.CancelFunc
						ctx, cancelNegative = context.WithTimeout(ctx, 2*time.Second)
						defer cancelNegative()
					}
					stream, err = activeEncryption.Wrap(ctx, stream)
					if tc.name == "wrong-encryption-key" {
						if err == nil {
							stream.Close()
							t.Fatal("VLESS Encryption accepted wrong public key")
						}
						return
					}
					if err != nil {
						t.Fatalf("VLESS Encryption handshake failed: %v", err)
					}
				}
				tunnel, err := client.DialEarlyConn(stream, M.ParseSocksaddr(origin.Listener.Addr().String()))
				if err != nil {
					t.Fatal(err)
				}
				defer tunnel.Close()
				roots := x509.NewCertPool()
				roots.AddCert(origin.Certificate())
				https := tls.Client(tunnel, &tls.Config{ServerName: "example.com", RootCAs: roots, MinVersion: tls.VersionTLS13})
				defer https.Close()
				if err := https.HandshakeContext(ctx); err != nil {
					t.Fatalf("HTTPS through VLESS failed: %v", err)
				}
				if encryptionMode == "" {
					if _, err := fmt.Fprint(https, "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"); err != nil {
						t.Fatal(err)
					}
				} else {
					request, err := http.NewRequest("POST", origin.URL, bytes.NewReader(payload))
					if err != nil {
						t.Fatal(err)
					}
					request.Close = true
					if err := request.Write(https); err != nil {
						t.Fatal(err)
					}
				}
				response, err := http.ReadResponse(bufio.NewReader(https), nil)
				if err != nil {
					t.Fatal(err)
				}
				body, readErr := io.ReadAll(response.Body)
				response.Body.Close()
				expected := http.StatusNoContent
				if encryptionMode != "" {
					expected = http.StatusOK
				}
				if readErr != nil {
					t.Fatal(readErr)
				}
				if encryptionMode != "" && !bytes.Equal(body, payload) {
					t.Fatal("Vision stream payload mismatch")
				}
				if response.StatusCode != expected {
					t.Fatalf("unexpected HTTPS status %d", response.StatusCode)
				}
			} else if err == nil {
				authenticated.Close()
				t.Fatal("Reality accepted invalid authentication")
			}
		})
	}
}

func sfaStartRealityXray(t *testing.T, binary, target string, private []byte, decryption string) string {
	t.Helper()
	var address string
	var port int
	for attempt := 0; attempt < 100; attempt++ {
		n, err := rand.Int(rand.Reader, big.NewInt(12000))
		if err != nil {
			t.Fatal(err)
		}
		port = 17000 + int(n.Int64())
		address = fmt.Sprintf("127.0.0.1:%d", port)
		listener, err := net.Listen("tcp", address)
		if err == nil {
			listener.Close()
			break
		}
		if attempt == 99 {
			t.Fatal("no free fixture port")
		}
	}
	config := map[string]any{
		"log": map[string]any{"loglevel": "debug"},
		"inbounds": []any{map[string]any{
			"listen": "127.0.0.1", "port": port, "protocol": "vless",
			"settings": map[string]any{"clients": []any{map[string]any{"id": "00000000-0000-0000-0000-000000000001"}, map[string]any{"id": "00000000-0000-0000-0000-000000000002", "flow": "xtls-rprx-vision"}}, "decryption": decryption},
			"streamSettings": map[string]any{"network": "tcp", "security": "reality", "realitySettings": map[string]any{
				"dest": target, "serverNames": []string{"example.com"}, "privateKey": base64.RawURLEncoding.EncodeToString(private), "shortIds": []string{"abcd"},
			}},
		}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.0/8"}}}, "ipsBlocked": []string{}}}},
	}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "server.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(binary, "run", "-c", path)
	var logs bytes.Buffer
	cmd.Stdout = &logs
	cmd.Stderr = &logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		select {
		case <-done:
			if t.Failed() {
				t.Logf("Xray fixture diagnostics: %s", logs.String())
			}
		case <-time.After(3 * time.Second):
			t.Error("Xray fixture did not exit")
		}
	})
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case err := <-done:
			done <- err
			t.Fatalf("Xray fixture exited before listening: %v", err)
		default:
		}
		conn, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if err == nil {
			conn.Close()
			return address
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("Xray fixture did not listen before deadline")
	return ""
}

func TestSFARealityHybridOptionInterop(t *testing.T) {
	for _, fixture := range []struct {
		name, env string
		hybrid    bool
	}{
		{"legacy-explicit-false", "XHTTP_XRAY_BINARY", false},
		{"modern-explicit-true", "SFA_REALITY_XRAY_BINARY", true},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			binary := os.Getenv(fixture.env)
			if binary == "" {
				t.Skip("Xray fixture not configured")
			}
			sfaCheckRealityCombination(t, binary, "", "chrome", "", &fixture.hybrid)
		})
	}
}
