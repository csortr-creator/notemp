//go:build with_utls

package tls

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net"
	"reflect"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

func TestSFARealityConfiguredALPN(t *testing.T) {
	for _, fingerprint := range []string{"chrome", "firefox"} {
		for _, protocol := range []string{"h2", "http/1.1"} {
			t.Run(fingerprint+"/"+protocol, func(t *testing.T) {
				key, err := ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
				config, err := NewRealityClient(context.Background(), logger.NOP(), "example.com", option.OutboundTLSOptions{
					Enabled: true, ServerName: "example.com", ALPN: []string{protocol},
					UTLS:    &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fingerprint},
					Reality: &option.OutboundRealityOptions{Enabled: true, PublicKey: base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), ShortID: "abcd"},
				})
				if err != nil {
					t.Fatal(err)
				}
				client, server := net.Pipe()
				defer server.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()
				done := make(chan error, 1)
				go func() { _, err := ClientHandshake(ctx, client, config.Clone()); client.Close(); done <- err }()
				server.SetDeadline(time.Now().Add(3 * time.Second))
				header := make([]byte, 5)
				if _, err = io.ReadFull(server, header); err != nil {
					t.Fatal(err)
				}
				record := make([]byte, binary.BigEndian.Uint16(header[3:]))
				if _, err = io.ReadFull(server, record); err != nil {
					t.Fatal(err)
				}
				server.Close()
				if err = <-done; err == nil {
					t.Fatal("unauthenticated server accepted")
				}
				pos := 39 + int(record[38])
				pos += 2 + int(binary.BigEndian.Uint16(record[pos:]))
				pos += 1 + int(record[pos])
				end := pos + 2 + int(binary.BigEndian.Uint16(record[pos:]))
				pos += 2
				var got []string
				for pos < end {
					typ := binary.BigEndian.Uint16(record[pos:])
					size := int(binary.BigEndian.Uint16(record[pos+2:]))
					pos += 4
					body := record[pos : pos+size]
					pos += size
					if typ == 16 {
						for i := 2; i < len(body); {
							size := int(body[i])
							got = append(got, string(body[i+1:i+1+size]))
							i += 1 + size
						}
					}
				}
				if !reflect.DeepEqual(got, []string{protocol}) {
					t.Fatalf("on-wire ALPN=%v, requested=%v", got, []string{protocol})
				}
			})
		}
	}
}
