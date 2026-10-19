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
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

// Capture the actual outgoing record, including REALITY's authentication setup.
func TestSFARealityHybridClientHello(t *testing.T) {
	for _, fp := range []string{"chrome", "firefox", "safari"} {
		for _, mode := range []string{"default", "true", "false"} {
			t.Run(fp+"/"+mode, func(t *testing.T) {
				key, err := ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
				var hybrid *bool
				if mode != "default" {
					value := mode == "true"
					hybrid = &value
				}
				config, err := NewRealityClient(context.Background(), logger.NOP(), "example.com", option.OutboundTLSOptions{
					Enabled: true, ServerName: "example.com",
					UTLS:    &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp},
					Reality: &option.OutboundRealityOptions{Enabled: true, PublicKey: base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), ShortID: "abcd", SupportX25519MLKEM768: hybrid},
				})
				if err != nil {
					t.Fatal(err)
				}
				client, server := net.Pipe()
				defer server.Close()
				_ = server.SetDeadline(time.Now().Add(3 * time.Second))
				done := make(chan error, 1)
				go func() {
					_, err := ClientHandshake(context.Background(), client, config.Clone())
					client.Close()
					done <- err
				}()
				header := make([]byte, 5)
				if _, err := io.ReadFull(server, header); err != nil {
					t.Fatal(err)
				}
				if header[0] != 22 {
					t.Fatal("expected handshake record")
				}
				record := make([]byte, binary.BigEndian.Uint16(header[3:]))
				if _, err := io.ReadFull(server, record); err != nil {
					t.Fatal(err)
				}
				server.Close()
				if err := <-done; err == nil {
					t.Fatal("handshake without server authentication succeeded")
				}
				// ClientHello: header, legacy version, random, session, ciphers, compression.
				pos := 4 + 2 + 32
				pos += 1 + int(record[pos])
				pos += 2 + int(binary.BigEndian.Uint16(record[pos:]))
				pos += 1 + int(record[pos])
				end := pos + 2 + int(binary.BigEndian.Uint16(record[pos:]))
				pos += 2
				groups, shares := false, false
				for pos < end {
					typ := binary.BigEndian.Uint16(record[pos:])
					size := int(binary.BigEndian.Uint16(record[pos+2:]))
					pos += 4
					body := record[pos : pos+size]
					pos += size
					if typ == 10 {
						for offset := 2; offset < len(body); offset += 2 {
							groups = groups || binary.BigEndian.Uint16(body[offset:]) == 4588
						}
					}
					if typ == 51 {
						for offset := 2; offset < len(body); {
							shares = shares || binary.BigEndian.Uint16(body[offset:]) == 4588
							offset += 4 + int(binary.BigEndian.Uint16(body[offset+2:]))
						}
					}
				}
				want := mode != "false"
				if groups != want || shares != want {
					t.Fatalf("hybrid groups=%v shares=%v want=%v", groups, shares, want)
				}
			})
		}
	}
}
