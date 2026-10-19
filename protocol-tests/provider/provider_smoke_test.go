//go:build with_utls

package tls_test

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2rayxhttp"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
)

type providerNode struct {
	Server    string `json:"server"`
	Port      uint16 `json:"port"`
	UUID      string `json:"uuid"`
	SNI       string `json:"sni"`
	PublicKey string `json:"public_key"`
	ShortID   string `json:"short_id"`
	Transport string `json:"transport"`
	Mode      string `json:"mode"`
	Path      string `json:"path"`
	Padding   string `json:"padding"`
}

// Opt-in remote diagnosis, never part of a release or a default test run.
// Input is an encrypted repository secret; never print configs or raw errors.
func TestSFAProviderSmoke(t *testing.T) {
	raw := os.Getenv("SFA_PROVIDER_PROBE")
	if raw == "" {
		t.Skip("provider diagnostic not requested")
	}
	var nodes []providerNode
	if json.Unmarshal([]byte(raw), &nodes) != nil || len(nodes) == 0 || len(nodes) > 4 {
		t.Fatal("invalid private diagnostic input")
	}
	binary := os.Getenv("SFA_PROVIDER_XRAY")
	if binary == "" {
		t.Fatal("reference fixture missing")
	}
	for index, node := range nodes {
		for _, fp := range []string{"firefox", "chrome"} {
			for _, engine := range []string{"sing-box", "xray"} {
				ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				stage, err := providerProbe(ctx, t, node, fp, engine, binary)
				cancel()
				t.Logf("SFA_PROVIDER_RESULT node=%d engine=%s fp=%s stage=%s code=%s", index, engine, fp, stage, providerErrorCode(err))
			}
		}
	}
}

func providerErrorCode(err error) string {
	if err == nil {
		return "success"
	}
	s := strings.ToLower(err.Error())
	switch {
	case strings.Contains(s, "reality verification failed"):
		return "reality_auth_failed"
	case strings.Contains(s, "certificate") || strings.Contains(s, "x509"):
		return "certificate_failed"
	case strings.Contains(s, "deadline") || strings.Contains(s, "timeout"):
		return "timeout"
	case strings.Contains(s, "refused"):
		return "connection_refused"
	default:
		return "failed"
	}
}

func providerProbe(ctx context.Context, t *testing.T, n providerNode, fp, engine, binary string) (string, error) {
	const target = "www.gstatic.com:443"
	d, err := dialer.NewDefault(ctx, option.DialerOptions{})
	if err != nil {
		return "dialer", err
	}
	var conn net.Conn
	if engine == "xray" {
		address, closePeer, err := providerXray(ctx, t, n, fp, binary)
		if err != nil {
			return "reference_start", err
		}
		defer closePeer()
		proxy := socks.NewClient(d, M.ParseSocksaddr(address), socks.Version5, "", "")
		conn, err = proxy.DialContext(ctx, "tcp", M.ParseSocksaddr(target))
		if err != nil {
			return "reference_socks", err
		}
	} else {
		tlsOptions := option.OutboundTLSOptions{Enabled: true, ServerName: n.SNI, UTLS: &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp}}
		if n.PublicKey != "" {
			tlsOptions.Reality = &option.OutboundRealityOptions{Enabled: true, PublicKey: n.PublicKey, ShortID: n.ShortID}
		}
		tlsConfig, err := btls.NewClient(ctx, logger.NOP(), n.Server, tlsOptions)
		if err != nil {
			return "tls_config", err
		}
		if n.Transport == "xhttp" {
			opts := option.V2RayXHTTPOptions{Path: n.Path, Mode: n.Mode}
			if p := strings.Split(n.Padding, "-"); len(p) == 2 {
				lo, e1 := strconv.Atoi(p[0])
				hi, e2 := strconv.Atoi(p[1])
				if e1 != nil || e2 != nil {
					return "padding", fmt.Errorf("invalid padding")
				}
				opts.XPaddingBytes = option.V2RayXHTTPRange{From: int32(lo), To: int32(hi)}
			}
			transport, err := v2rayxhttp.NewClient(ctx, d, M.ParseSocksaddrHostPort(n.Server, n.Port), opts, tlsConfig)
			if err != nil {
				return "transport_config", err
			}
			defer transport.Close()
			conn, err = transport.DialContext(ctx)
			if err != nil {
				return "xhttp_handshake", err
			}
		} else {
			raw, err := d.DialContext(ctx, "tcp", M.ParseSocksaddrHostPort(n.Server, n.Port))
			if err != nil {
				return "tcp", err
			}
			defer raw.Close()
			conn, err = btls.ClientHandshake(ctx, raw, tlsConfig)
			if err != nil {
				return "tls_handshake", err
			}
		}
		defer conn.Close()
		client, err := vless.NewClient(n.UUID, "", logger.NOP())
		if err != nil {
			return "vless_config", err
		}
		conn, err = client.DialEarlyConn(conn, M.ParseSocksaddr(target))
		if err != nil {
			return "vless", err
		}
	}
	defer conn.Close()
	// Bound every stage, including transports whose SetDeadline is unsupported.
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	https := stdtls.Client(conn, &stdtls.Config{ServerName: "www.gstatic.com", MinVersion: stdtls.VersionTLS12})
	defer https.Close()
	if err = https.HandshakeContext(ctx); err != nil {
		return "origin_tls", err
	}
	_, err = io.WriteString(https, "GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n")
	if err != nil {
		return "origin_write", err
	}
	response, err := http.ReadResponse(bufio.NewReader(https), nil)
	if err != nil {
		return "origin_read", err
	}
	response.Body.Close()
	if response.StatusCode != 204 {
		return "origin_status", fmt.Errorf("unexpected status")
	}
	return "complete", nil
}

func providerXray(ctx context.Context, t *testing.T, n providerNode, fp, binary string) (string, func(), error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", nil, err
	}
	addr := listener.Addr().String()
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	stream := map[string]any{"network": "tcp", "security": "tls", "tlsSettings": map[string]any{"serverName": n.SNI, "fingerprint": fp}}
	if n.PublicKey != "" {
		stream["security"] = "reality"
		stream["realitySettings"] = map[string]any{"serverName": n.SNI, "fingerprint": fp, "publicKey": n.PublicKey, "shortId": n.ShortID}
		delete(stream, "tlsSettings")
	}
	if n.Transport == "xhttp" {
		stream["network"] = "xhttp"
		x := map[string]any{"path": n.Path, "mode": n.Mode}
		if n.Padding != "" {
			x["extra"] = map[string]any{"xPaddingBytes": n.Padding}
		}
		stream["xhttpSettings"] = x
	}
	config := map[string]any{"log": map[string]any{"loglevel": "none"},
		"inbounds":  []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": map[string]any{"udp": false}}},
		"outbounds": []any{map[string]any{"protocol": "vless", "settings": map[string]any{"vnext": []any{map[string]any{"address": n.Server, "port": n.Port, "users": []any{map[string]any{"id": n.UUID, "encryption": "none"}}}}}, "streamSettings": stream}},
	}
	data, err := json.Marshal(config)
	if err != nil {
		return "", nil, err
	}
	path := filepath.Join(t.TempDir(), "private.json")
	if err = os.WriteFile(path, data, 0600); err != nil {
		return "", nil, err
	}
	cmd := exec.CommandContext(ctx, binary, "run", "-c", path)
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if err = cmd.Start(); err != nil {
		return "", nil, err
	}
	closePeer := func() { cmd.Process.Kill(); cmd.Wait() }
	for {
		conn, e := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if e == nil {
			conn.Close()
			return addr, closePeer, nil
		}
		select {
		case <-ctx.Done():
			closePeer()
			return "", nil, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
}
