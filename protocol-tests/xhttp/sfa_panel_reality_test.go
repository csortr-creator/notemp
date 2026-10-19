//go:build with_utls

package v2rayxhttp_test

import (
	"bytes"
	"context"
	"github.com/sagernet/sing-box/common/dialer"
	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2rayxhttp"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/stretchr/testify/require"
	"io"
	"testing"
	"time"
)

// Synthetic credentials only. Exercise the panel combination without weakening
// the peer's version policy (the previous fixtures forced minClientVer=0.0.0).
func TestSFAPanelRealityXHTTP(t *testing.T) {
	binary := requireXRayBinary(t)
	for _, minimum := range []string{"0.0.0", "26.3.27", "26.9.9"} {
		for _, fingerprint := range []string{"chrome", "firefox"} {
			for _, mode := range []string{"auto", "stream-up"} {
				t.Run(minimum+"/"+fingerprint+"/"+mode, func(t *testing.T) {
					private, public := interopRealityKeyPair(t)
					const name = "xhttp.test"
					const sid = "0123456789abcdef"
					serverPort := interopPort(t)
					targetPort, stop := interopEchoServer(t)
					defer stop()
					fallback, stopFallback := interopRealityFallback(t, name)
					defer stopFallback()
					uuid := interopUUID(t)
					stream := xrayXHTTPRealityStreamSettings("server", fallback, private, "", name, sid, mode)
					stream["realitySettings"].(map[string]any)["minClientVer"] = minimum
					stream["xhttpSettings"] = map[string]any{"path": "/", "mode": mode, "xPaddingBytes": "112-1344"}
					startXRay(t, binary, serverPort, map[string]any{
						"log": map[string]any{"loglevel": "warning"},
						"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": serverPort, "protocol": "vless",
							"settings": map[string]any{"decryption": "none", "clients": []any{map[string]any{"id": uuid}}}, "streamSettings": stream}},
						"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow"}}}}},
					})
					tls := interopRealityClientTLS(name, public, sid)
					tls.UTLS.Fingerprint = fingerprint
					ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
					defer cancel()
					tlsConfig, err := btls.NewRealityClient(ctx, logger.NOP(), name, *tls)
					require.NoError(t, err)
					d, err := dialer.NewDefault(ctx, option.DialerOptions{})
					require.NoError(t, err)
					transport, err := v2rayxhttp.NewClient(ctx, d, M.ParseSocksaddrHostPort("127.0.0.1", serverPort), option.V2RayXHTTPOptions{Path: "/", Mode: mode, XPaddingBytes: option.V2RayXHTTPRange{From: 112, To: 1344}}, tlsConfig)
					require.NoError(t, err)
					defer transport.Close()
					conn, err := transport.DialContext(ctx)
					require.NoError(t, err)
					defer conn.Close()
					client, err := vless.NewClient(uuid, "", logger.NOP())
					require.NoError(t, err)
					tunnel, err := client.DialEarlyConn(conn, M.ParseSocksaddrHostPort("127.0.0.1", targetPort))
					require.NoError(t, err)
					defer tunnel.Close()
					payload := bytes.Repeat([]byte("panel-xhttp-reality"), 4096)
					_, err = tunnel.Write(payload)
					require.NoError(t, err)
					result := make([]byte, len(payload))
					_, err = io.ReadFull(tunnel, result)
					require.NoError(t, err)
					require.Equal(t, payload, result)
				})
			}
		}
	}
}
