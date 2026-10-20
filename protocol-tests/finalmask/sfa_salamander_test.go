//go:build with_quic

package vless

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	HY "github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
)

func TestSFASalamanderXray(t *testing.T) {
	binary := os.Getenv("SFA_REALITY_XRAY_BINARY")
	if binary == "" {
		t.Skip("current Xray fixture binary is not configured")
	}
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer origin.Close()
	certificate := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: origin.TLS.Certificates[0].Certificate[0]})
	key, err := x509.MarshalPKCS8PrivateKey(origin.TLS.Certificates[0].PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certFile, keyFile := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	if err := os.WriteFile(certFile, certificate, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}), 0600); err != nil {
		t.Fatal(err)
	}
	reserve, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reserve.LocalAddr().(*net.UDPAddr).Port
	reserve.Close()
	config := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"listen": "127.0.0.1", "port": port, "protocol": "hysteria",
			"settings": map[string]any{"version": 2, "users": []any{map[string]any{"auth": "proxy-password"}}},
			"streamSettings": map[string]any{
				"network": "hysteria", "security": "tls", "hysteriaSettings": map[string]any{"version": 2},
				"tlsSettings": map[string]any{"alpn": []string{"h3"}, "certificates": []any{map[string]any{"certificateFile": certFile, "keyFile": keyFile}}},
				"finalmask":   map[string]any{"udp": []any{map[string]any{"type": "salamander", "settings": map[string]any{"password": "mask-password"}}}},
			},
		}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.0/8"}}}, "ipsBlocked": []string{}}}},
	}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "server.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	logs := &sfaXrayStartupLog{ready: make(chan struct{})}
	cmd := exec.Command(binary, "run", "-c", path)
	cmd.Stdout, cmd.Stderr = logs, logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	var waitErr error
	go func() { waitErr = cmd.Wait(); close(done) }()
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		select {
		case <-done:
			if t.Failed() {
				t.Logf("Xray fixture output:\n%s", logs.String())
			}
		case <-time.After(3 * time.Second):
			t.Error("Xray did not exit")
		}
	})
	// Never probe readiness by binding the server port: that probe can win the
	// bind race and make Xray exit. A connected UDP client can also receive an
	// immediate ICMP refusal before startup, so wait for Xray's own started event.
	select {
	case <-logs.ready:
	case <-done:
		t.Fatalf("Xray exited during startup: %v", waitErr)
	case <-time.After(5 * time.Second):
		t.Fatal("Xray did not report successful startup")
	}
	pool := x509.NewCertPool()
	pool.AddCert(origin.Certificate())
	for _, tc := range []struct {
		name, password, mask            string
		valid, disableParrot, wrongName bool
	}{
		{"valid", "proxy-password", "mask-password", true, false, false},
		{"without-parrot", "proxy-password", "mask-password", true, true, false},
		{"wrong-certificate-name", "proxy-password", "mask-password", false, false, true},
		{"wrong-auth", "wrong-password", "mask-password", false, false, false},
		{"wrong-mask", "proxy-password", "wrong-mask-password", false, false, false},
	} {
		passed := t.Run(tc.name, func(t *testing.T) {
			select {
			case <-done:
				t.Fatalf("Xray exited before exchange: %v", waitErr)
			default:
			}
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			serverName := "127.0.0.1"
			if tc.wrongName {
				serverName = "wrong.example.org"
			}
			out, err := HY.NewOutbound(ctx, nil, logger.NOP(), "hy2", option.Hysteria2OutboundOptions{
				ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: uint16(port)}, Password: tc.password,
				DisableChromeParrot:         tc.disableParrot,
				Obfs:                        &option.Hysteria2Obfs{Type: "salamander", Password: tc.mask},
				OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &option.OutboundTLSOptions{Enabled: true, ServerName: serverName, Certificate: []string{string(certificate)}}},
			})
			if err != nil {
				t.Fatal(err)
			}
			defer out.(*HY.Outbound).Close()
			transport := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool}, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				return out.DialContext(ctx, network, M.ParseSocksaddr(address))
			}}
			defer transport.CloseIdleConnections()
			response, err := (&http.Client{Transport: transport, Timeout: 3 * time.Second}).Get(origin.URL)
			if !tc.valid {
				if err == nil {
					response.Body.Close()
					t.Fatal("invalid authentication or mask accepted")
				}
				select {
				case <-done:
					t.Fatalf("Xray exited during negative exchange: %v", waitErr)
				default:
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != 204 {
				t.Fatalf("HTTPS status %d", response.StatusCode)
			}
		})
		if !passed && tc.valid {
			// A dead/unready server cannot establish negative-case rejection.
			return
		}
	}
}

// Observe startup without opening or claiming the server's UDP endpoint.
// os/exec may deliver the marker in multiple writes and both streams use this
// writer; synchronization also permits safe diagnostic snapshots on failure.
type sfaXrayStartupLog struct {
	access sync.Mutex
	buffer bytes.Buffer
	ready  chan struct{}
	once   sync.Once
}

func (l *sfaXrayStartupLog) Write(p []byte) (int, error) {
	l.access.Lock()
	defer l.access.Unlock()
	n, err := l.buffer.Write(p)
	output := l.buffer.Bytes()
	if bytes.Contains(output, []byte("core: Xray ")) && bytes.Contains(output, []byte(" started")) {
		l.once.Do(func() { close(l.ready) })
	}
	return n, err
}

func (l *sfaXrayStartupLog) String() string {
	l.access.Lock()
	defer l.access.Unlock()
	return l.buffer.String()
}
