package v2rayxhttp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"strings"
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
