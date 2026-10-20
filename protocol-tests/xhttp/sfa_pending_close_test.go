package v2rayxhttp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
)

type sfaPendingRoundTripper struct{ stopped chan struct{} }

func (r sfaPendingRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	httptrace.ContextClientTrace(request.Context()).GotConn(httptrace.GotConnInfo{})
	<-request.Context().Done()
	close(r.stopped)
	return nil, request.Context().Err()
}

type sfaPendingRoundTripFunc func(*http.Request) (*http.Response, error)

func (f sfaPendingRoundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func TestSFAXHTTPCancelBeforeConnection(t *testing.T) {
	config, err := newConfig(option.V2RayXHTTPOptions{Path: "/", Mode: "stream-up"})
	if err != nil {
		t.Fatal(err)
	}
	target := &clientTarget{config: config, requestURL: "http://fixture.invalid/"}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started, stopped := make(chan struct{}), make(chan struct{})
	transport := sfaPendingRoundTripFunc(func(request *http.Request) (*http.Response, error) {
		close(started)
		<-request.Context().Done()
		close(stopped)
		return nil, request.Context().Err()
	})
	result := make(chan error, 1)
	go func() {
		response, err := target.openStream(ctx, transport, http.MethodGet, "synthetic", nil)
		if response != nil {
			response.Body.Close()
		}
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("request did not start")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("dial cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled dial did not return")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("cancelled dial leaked RoundTrip")
	}
}

type sfaCloseCountingBody struct{ closes atomic.Int32 }

func (b *sfaCloseCountingBody) Read([]byte) (int, error) { return 0, io.EOF }
func (b *sfaCloseCountingBody) Close() error             { b.closes.Add(1); return nil }

func TestSFAXHTTPConcurrentResponseClose(t *testing.T) {
	for range 100 {
		r := newWaitingResponseBody()
		var cancellations atomic.Int32
		r.cancel = func() { cancellations.Add(1) }
		body := &sfaCloseCountingBody{}
		start := make(chan struct{})
		var workers sync.WaitGroup
		for range 4 {
			workers.Add(1)
			go func() { defer workers.Done(); <-start; r.Close() }()
		}
		workers.Add(1)
		go func() { defer workers.Done(); <-start; r.set(body, nil) }()
		close(start)
		workers.Wait()
		if body.closes.Load() != 1 {
			t.Fatalf("body close count: %d", body.closes.Load())
		}
		if cancellations.Load() != 1 {
			t.Fatalf("request cancellation count: %d", cancellations.Load())
		}
		select {
		case <-r.ready:
		default:
			t.Fatal("closed response remains blocked")
		}
	}
}

func TestSFAXHTTPPendingResponseClose(t *testing.T) {
	config, err := newConfig(option.V2RayXHTTPOptions{Path: "/", Mode: "stream-up"})
	if err != nil {
		t.Fatal(err)
	}
	target := &clientTarget{config: config, requestURL: "http://fixture.invalid/"}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stopped := make(chan struct{})
	response, err := target.openStream(ctx, sfaPendingRoundTripper{stopped}, http.MethodGet, "synthetic", nil)
	if err != nil {
		t.Fatal(err)
	}
	cancel() // A completed dial must outlive its routing context.
	select {
	case <-stopped:
		t.Fatal("routing context cancelled the established stream")
	default:
	}
	read := make(chan error, 1)
	go func() { _, err := response.Body.Read(make([]byte, 1)); read <- err }()
	response.Body.Close()
	select {
	case err := <-read:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("read after close: %v", err)
		}
	case <-time.After(time.Second):
		t.Error("closing a pending response did not unblock Read")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Error("closing a pending response did not cancel RoundTrip")
	}
}

type sfaLateBody struct {
	io.Reader
	closed atomic.Bool
}

func (b *sfaLateBody) Close() error { b.closed.Store(true); return nil }

func TestSFAXHTTPResponseArrivesAfterClose(t *testing.T) {
	for range 100 {
		r := newWaitingResponseBody()
		r.Close()
		late := &sfaLateBody{Reader: strings.NewReader("late response")}
		r.set(late, nil)
		if !late.closed.Load() {
			t.Fatal("late response body leaked")
		}
		if _, err := r.Read(make([]byte, 1)); !errors.Is(err, net.ErrClosed) {
			t.Fatalf("closed stream returned late response: %v", err)
		}
		r.Close()
	}
}
