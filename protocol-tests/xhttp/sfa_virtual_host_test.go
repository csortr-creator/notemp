//go:build with_utls

package v2rayxhttp_test

import (
	"context"
	stdtls "crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2rayxhttp"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
)

type sfaHostEcho struct{}

func (sfaHostEcho) NewConnectionEx(_ context.Context, conn net.Conn, _ M.Socksaddr, _ M.Socksaddr, _ N.CloseHandlerFunc) {
	defer conn.Close()
	_, _ = io.Copy(conn, conn)
}

// Dial an IP, but route requests by TLS server_name, as Xray does. A strict
// virtual host reproduces panel/reverse-proxy failure that permissive fixtures
// cannot expose. All credentials and certificates are generated locally.
func TestSFAXHTTPVirtualHost(t *testing.T) {
	for _, fp := range []string{"chrome", "firefox"} {
		for _, mode := range []string{"auto", "stream-up"} {
			for _, override := range []string{"", "edge.test"} {
				t.Run(fp+"/"+mode+"/"+override, func(t *testing.T) {
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					defer cancel()
					certificate, key, ca := interopCertificate(t)
					cert, err := stdtls.LoadX509KeyPair(certificate, key)
					require.NoError(t, err)
					server, err := v2rayxhttp.NewServer(ctx, logger.NOP(), option.V2RayXHTTPOptions{Path: "/panel/", Mode: mode}, nil, sfaHostEcho{})
					require.NoError(t, err)
					defer server.Close()
					wantHost := "xhttp.test"
					if override != "" {
						wantHost = override
					}
					endpoint := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
						if r.Host != wantHost {
							w.WriteHeader(http.StatusMisdirectedRequest)
							return
						}
						server.ServeHTTP(w, r)
					}))
					endpoint.EnableHTTP2 = true
					endpoint.TLS = &stdtls.Config{Certificates: []stdtls.Certificate{cert}}
					endpoint.StartTLS()
					defer func() {
						cancel()
						server.Close()
						endpoint.CloseClientConnections()
						endpoint.Close()
					}()
					tlsConfig, err := btls.NewClient(ctx, logger.NOP(), "127.0.0.1", option.OutboundTLSOptions{
						Enabled: true, ServerName: "xhttp.test", CertificatePath: ca,
						UTLS: &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp}, ALPN: []string{"h2", "http/1.1"},
					})
					require.NoError(t, err)
					d, err := dialer.NewDefault(ctx, option.DialerOptions{})
					require.NoError(t, err)
					client, err := v2rayxhttp.NewClient(ctx, d, M.SocksaddrFromNet(endpoint.Listener.Addr()), option.V2RayXHTTPOptions{Path: "/panel/", Mode: mode, Host: override}, tlsConfig)
					require.NoError(t, err)
					defer client.Close()
					conn, err := client.DialContext(ctx)
					require.NoError(t, err)
					defer conn.Close()
					payload := []byte("strict-vhost-roundtrip")
					_, err = conn.Write(payload)
					require.NoError(t, err)
					response := make([]byte, len(payload))
					_, err = io.ReadFull(conn, response)
					require.NoError(t, err)
					require.Equal(t, payload, response)
				})
			}
		}
	}
}
