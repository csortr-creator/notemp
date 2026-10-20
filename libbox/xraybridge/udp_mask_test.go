package xraybridge

import (
	"bytes"
	"encoding/json"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/transport/internet/finalmask"
	"github.com/xtls/xray-core/transport/internet/finalmask/salamander"
)

type observedPacketConn struct {
	net.PacketConn
	received atomic.Int32
}

func (c *observedPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, a, err := c.PacketConn.ReadFrom(b)
	if err == nil {
		c.received.Add(1)
	}
	return n, a, err
}

func udpMask(t *testing.T, password string) *finalmask.UdpmaskManager {
	t.Helper()
	raw := json.RawMessage(`{"password":"` + password + `"}`)
	cfg, err := (&conf.StreamConfig{FinalMask: &conf.FinalMask{Udp: []conf.Mask{{Type: "salamander", Settings: &raw}}}}).Build()
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Udpmasks) != 1 {
		t.Fatal("UDP mask missing from built config")
	}
	m, err := cfg.Udpmasks[0].GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	mask, ok := m.(*salamander.Config)
	if !ok || mask.Password != password {
		t.Fatal("wrong built Salamander config")
	}
	return finalmask.NewUdpmaskManager([]finalmask.Udpmask{mask})
}

// Exercises the actual UDP mask manager on real sockets, not SOCKS UDP/JNI.
// Salamander is obfuscation, not authentication: wrong keys may produce garbage.
func TestWireUDPSalamander(t *testing.T) {
	for _, name := range []string{"matching", "wrong", "missing"} {
		t.Run(name, func(t *testing.T) {
			serverRaw, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			observed := &observedPacketConn{PacketConn: serverRaw}
			server, err := udpMask(t, "server-password").WrapPacketConnServer(observed)
			if err != nil {
				serverRaw.Close()
				t.Fatal(err)
			}
			defer server.Close()
			client, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			if name != "missing" {
				password := "server-password"
				if name == "wrong" {
					password = "different-password"
				}
				raw := client
				client, err = udpMask(t, password).WrapPacketConnClient(raw)
				if err != nil {
					raw.Close()
					t.Fatal(err)
				}
			}
			defer client.Close()
			server.SetDeadline(time.Now().Add(250 * time.Millisecond))
			client.SetDeadline(time.Now().Add(250 * time.Millisecond))
			request := []byte("udp-original-request-with-enough-bytes")
			if _, err = client.WriteTo(request, server.LocalAddr()); err != nil {
				t.Fatal(err)
			}
			b := make([]byte, 256)
			n, peer, readErr := server.ReadFrom(b)
			if observed.received.Load() == 0 {
				t.Fatal("fixture sent no packet to masked socket")
			}
			if name != "matching" {
				if readErr == nil && bytes.Equal(b[:n], request) {
					t.Fatal("original request crossed a wrong/absent mask")
				}
				return
			}
			if readErr != nil || !bytes.Equal(b[:n], request) {
				t.Fatalf("request exchange: %v", readErr)
			}
			reply := []byte("distinct-udp-server-response")
			if _, err = server.WriteTo(reply, peer); err != nil {
				t.Fatal(err)
			}
			n, _, err = client.ReadFrom(b)
			if err != nil || !bytes.Equal(b[:n], reply) {
				t.Fatalf("response exchange: %v", err)
			}
		})
	}
}
