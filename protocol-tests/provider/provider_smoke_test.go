//go:build with_utls

package tls_test

import (
	"bufio"
	"context"
	"crypto/rand"
	stdtls "crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/nacl/box"

	"github.com/sagernet/sing-box/common/dialer"
	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2rayxhttp"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
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
	var input struct {
		Nodes           []providerNode `json:"nodes"`
		ReportPublicKey string         `json:"report_public_key"`
	}
	var nodes []providerNode
	if strings.HasPrefix(strings.TrimSpace(raw), "[") {
		if json.Unmarshal([]byte(raw), &nodes) != nil {
			t.Fatal("invalid private diagnostic input")
		}
	} else {
		if json.Unmarshal([]byte(raw), &input) != nil {
			t.Fatal("invalid private diagnostic input")
		}
		nodes = input.Nodes
	}
	if len(nodes) == 0 || len(nodes) > 4 {
		t.Fatal("invalid private diagnostic input")
	}
	var reportKey *[32]byte
	if input.ReportPublicKey != "" {
		decoded, err := base64.StdEncoding.DecodeString(input.ReportPublicKey)
		if err != nil || len(decoded) != 32 {
			t.Fatal("invalid report key")
		}
		reportKey = new([32]byte)
		copy(reportKey[:], decoded)
	}
	binary := os.Getenv("SFA_PROVIDER_XRAY")
	if binary == "" {
		t.Fatal("reference fixture missing")
	}
	for index, node := range nodes {
		for _, target := range []struct{ address, path string }{
			{"www.gstatic.com:443", "/generate_204"},
			{"example.com:443", "/"},
		} {
			for _, fp := range []string{"firefox", "chrome"} {
				for _, engine := range []string{"sing-box", "xray"} {
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
					stage, err := providerProbe(ctx, t, node, fp, engine, binary, target.address, target.path)
					cancel()
					t.Logf("SFA_PROVIDER_RESULT node=%d engine=%s fp=%s target=%s stage=%s code=%s", index, engine, fp, target.address, stage, providerErrorCode(err))
					if err != nil && reportKey != nil {
						report, encryptErr := providerEncryptedError(err, nodes, reportKey)
						if encryptErr != nil {
							t.Fatal("cannot encrypt diagnostic report")
						}
						t.Logf("SFA_PROVIDER_ENCRYPTED_ERROR node=%d engine=%s fp=%s target=%s data=%s", index, engine, fp, target.address, report)
					}
				}
			}
		}
	}
}

func providerEncryptedError(err error, nodes []providerNode, publicKey *[32]byte) (string, error) {
	message := err.Error()
	for _, n := range nodes {
		for _, value := range []string{n.UUID, n.PublicKey, n.ShortID, n.Server, n.SNI, n.Path} {
			if value != "" && value != "/" {
				message = strings.ReplaceAll(message, value, "[redacted]")
			}
		}
	}
	ciphertext, sealErr := box.SealAnonymous(nil, []byte(message), publicKey, rand.Reader)
	return base64.StdEncoding.EncodeToString(ciphertext), sealErr
}

func TestSFAProviderReportRedaction(t *testing.T) {
	publicKey, privateKey, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	n := providerNode{UUID: "private-uuid", PublicKey: "private-pbk", ShortID: "private-sid", Server: "private-server", SNI: "private-sni", Path: "/private-path"}
	report, err := providerEncryptedError(fmt.Errorf("request to %s%s: %s %s %s %s: protocol error", n.Server, n.Path, n.UUID, n.PublicKey, n.ShortID, n.SNI), []providerNode{n}, publicKey)
	if err != nil {
		t.Fatal(err)
	}
	sealed, err := base64.StdEncoding.DecodeString(report)
	if err != nil {
		t.Fatal(err)
	}
	plain, ok := box.OpenAnonymous(nil, sealed, publicKey, privateKey)
	if !ok || strings.Contains(string(plain), "private-") || !strings.Contains(string(plain), "protocol error") {
		t.Fatal("report did not preserve a redacted diagnostic")
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
	case strings.Contains(s, "domain not resolved") || strings.Contains(s, "no such host"):
		return "dns_failed"
	case strings.Contains(s, "certificate") || strings.Contains(s, "x509"):
		return "certificate_failed"
	case strings.Contains(s, "deadline") || strings.Contains(s, "timeout"):
		return "timeout"
	case strings.Contains(s, "refused"):
		return "connection_refused"
	case strings.Contains(s, "eof"):
		return "eof"
	case strings.Contains(s, "reset by peer"):
		return "connection_reset"
	case strings.Contains(s, "closed pipe") || strings.Contains(s, "broken pipe"):
		return "closed_pipe"
	case strings.Contains(s, "remote error: tls"):
		return "tls_alert"
	case strings.Contains(s, "first record does not look like"):
		return "non_tls_response"
	default:
		return "failed"
	}
}

func providerProbe(ctx context.Context, t *testing.T, n providerNode, fp, engine, binary, target, path string) (string, error) {
	host, _, splitErr := net.SplitHostPort(target)
	if splitErr != nil {
		return "target_config", splitErr
	}
	rawDialer, err := dialer.NewDefault(ctx, option.DialerOptions{})
	if err != nil {
		return "dialer", err
	}
	// The isolated TLS fixture has no application DNS router. DefaultDialer
	// accepts only IPs; resolve the physical endpoint through the runner's
	// resolver, as reference Xray does. Preserve the original TLS SNI/authority.
	d := providerResolvingDialer{Dialer: rawDialer, lookup: func(ctx context.Context, host string) ([]netip.Addr, error) {
		return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	}}
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
	https := stdtls.Client(conn, &stdtls.Config{ServerName: host, MinVersion: stdtls.VersionTLS12})
	defer https.Close()
	if err = https.HandshakeContext(ctx); err != nil {
		return "origin_tls", err
	}
	_, err = fmt.Fprintf(https, "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", path, host)
	if err != nil {
		return "origin_write", err
	}
	response, err := http.ReadResponse(bufio.NewReader(https), nil)
	if err != nil {
		return "origin_read", err
	}
	response.Body.Close()
	if response.StatusCode != 204 && response.StatusCode != 200 {
		return "origin_status", fmt.Errorf("unexpected status")
	}
	return "complete", nil
}

type providerResolvingDialer struct {
	N.Dialer
	lookup func(context.Context, string) ([]netip.Addr, error)
}

func (d providerResolvingDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	if !address.IsDomain() {
		return d.Dialer.DialContext(ctx, network, address)
	}
	addresses, err := d.lookup(ctx, address.Fqdn)
	if err != nil {
		return nil, err
	}
	return N.DialSerial(ctx, d.Dialer, network, address, addresses)
}

func TestSFAProviderDomainDialer(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	raw, err := dialer.NewDefault(ctx, option.DialerOptions{})
	if err != nil {
		t.Fatal(err)
	}
	destination := M.ParseSocksaddrHostPort("fixture.invalid", uint16(listener.Addr().(*net.TCPAddr).Port))
	if conn, err := raw.DialContext(ctx, "tcp", destination); err == nil {
		conn.Close()
		t.Fatal("fixture must expose unresolved default dialer")
	}
	resolved := false
	d := providerResolvingDialer{Dialer: raw, lookup: func(_ context.Context, host string) ([]netip.Addr, error) {
		if host != "fixture.invalid" {
			t.Fatal("original domain lost")
		}
		resolved = true
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	}}
	conn, err := d.DialContext(ctx, "tcp", destination)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if !resolved {
		t.Fatal("resolver was not called")
	}
	conn, err = d.DialContext(ctx, "tcp", M.SocksaddrFromNet(listener.Addr()))
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
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
