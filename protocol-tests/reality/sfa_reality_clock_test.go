//go:build with_utls

package tls

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"golang.org/x/crypto/hkdf"
)

// Decrypt the actual authenticated ClientHello as the server does. A configured
// clock deliberately differs from the machine clock, including after Clone.
func TestSFARealityConfiguredClock(t *testing.T) {
	for _, fp := range []string{"chrome", "firefox"} {
		t.Run(fp, func(t *testing.T) {
			private, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				t.Fatal(err)
			}
			config, err := NewRealityClient(context.Background(), logger.NOP(), "example.com", option.OutboundTLSOptions{
				Enabled: true, ServerName: "example.com", UTLS: &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp},
				Reality: &option.OutboundRealityOptions{Enabled: true, PublicKey: base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()), ShortID: "abcd"},
			})
			if err != nil {
				t.Fatal(err)
			}
			const seconds = 1700000000
			config.(*RealityClientConfig).uClient.config.Time = func() time.Time { return time.Unix(seconds, 0) }
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
			var public []byte
			for pos < end {
				typ := binary.BigEndian.Uint16(record[pos:])
				size := int(binary.BigEndian.Uint16(record[pos+2:]))
				pos += 4
				body := record[pos : pos+size]
				pos += size
				if typ == 51 {
					for i := 2; i < len(body); {
						group := binary.BigEndian.Uint16(body[i:])
						size := int(binary.BigEndian.Uint16(body[i+2:]))
						if group == 29 {
							public = body[i+4 : i+4+size]
						}
						i += 4 + size
					}
				}
			}
			peer, err := ecdh.X25519().NewPublicKey(public)
			if err != nil {
				t.Fatal(err)
			}
			auth, err := private.ECDH(peer)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = hkdf.New(sha256.New, auth, record[6:26], []byte("REALITY")).Read(auth); err != nil {
				t.Fatal(err)
			}
			block, _ := aes.NewCipher(auth)
			gcm, _ := cipher.NewGCM(block)
			sealed := append([]byte(nil), record[39:71]...)
			clear(record[39:71])
			plain, err := gcm.Open(nil, record[26:38], sealed, record)
			if err != nil {
				t.Fatal(err)
			}
			if got := binary.BigEndian.Uint32(plain[4:8]); got != seconds {
				t.Fatalf("Reality timestamp=%d, configured clock=%d", got, seconds)
			}
		})
	}
}
