package urltest

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type sfaOriginDialer struct {
	dials atomic.Int32
	fail  error
	block bool
}

func (d *sfaOriginDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	d.dials.Add(1)
	if d.block {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	if d.fail != nil {
		return nil, d.fail
	}
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}
func (*sfaOriginDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("unused")
}

func TestSFAOriginFallback(t *testing.T) {
	for _, warm := range []bool{false, true} {
		t.Run(map[bool]string{false: "cold", true: "warm"}[warm], func(t *testing.T) {
			cancelled := make(chan struct{})
			primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done(); close(cancelled) }))
			defer primary.Close()
			var responses atomic.Int32
			fallback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { responses.Add(1); w.WriteHeader(204) }))
			defer fallback.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
			defer cancel()
			delay, err := urlTestCandidates(ctx, []string{primary.URL, fallback.URL}, &sfaOriginDialer{}, warm)
			if err != nil || delay == 0 {
				t.Fatalf("reachable alternate origin failed: %v", err)
			}
			want := int32(1)
			if warm {
				want = 2
			}
			if responses.Load() != want {
				t.Fatalf("warm semantics lost: %d requests", responses.Load())
			}
			select {
			case <-cancelled:
			case <-time.After(time.Second):
				t.Fatal("losing request was not cancelled")
			}
		})
	}
}

func TestSFAOriginFallbackKeepsPrimaryBudget(t *testing.T) {
	started := make(chan struct{})
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-started:
			time.Sleep(10 * time.Millisecond)
			w.WriteHeader(204)
		case <-r.Context().Done():
		}
	}))
	defer primary.Close()
	stopped := make(chan struct{})
	fallback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(started); <-r.Context().Done(); close(stopped) }))
	defer fallback.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	if _, err := urlTestCandidates(ctx, []string{primary.URL, fallback.URL}, &sfaOriginDialer{}, false); err != nil {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("fallback outlived the successful primary")
	}
}

func TestSFAOriginFallbackNeverDoublesBlockedServerDial(t *testing.T) {
	d := &sfaOriginDialer{block: true}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err := urlTestCandidates(ctx, []string{"http://primary.invalid/", "http://fallback.invalid/"}, d, false)
	if !errors.Is(err, context.DeadlineExceeded) || d.dials.Load() != 1 {
		t.Fatalf("blocked server retried or deadline lost: %v, dials=%d", err, d.dials.Load())
	}
}

func TestSFAOriginFallbackNeverHidesConnectFailure(t *testing.T) {
	d := &sfaOriginDialer{fail: io.ErrUnexpectedEOF}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err := urlTestCandidates(ctx, []string{"http://primary.invalid/", "http://fallback.invalid/"}, d, false)
	var phase *ProbeError
	if !errors.As(err, &phase) || phase.Stage != "connect" || !errors.Is(err, io.ErrUnexpectedEOF) || d.dials.Load() != 1 {
		t.Fatalf("connect failure was masked/retried: %v, calls=%d", err, d.dials.Load())
	}
}

func TestSFAOriginFallbackPreservesCertificateVerification(t *testing.T) {
	primary := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer primary.Close()
	var hits atomic.Int32
	fallback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(204) }))
	defer fallback.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err := urlTestCandidates(ctx, []string{primary.URL, fallback.URL}, &sfaOriginDialer{}, false)
	if err == nil || hits.Load() != 0 {
		t.Fatal("untrusted certificate was accepted or hidden by a retry")
	}
}

func TestSFAExplicitOriginIsNotReplaced(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer server.Close()
	d := &sfaOriginDialer{}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err := URLTest(ctx, server.URL, d)
	if err == nil || d.dials.Load() != 1 {
		t.Fatalf("configured origin was replaced: %v, dials=%d", err, d.dials.Load())
	}
}
