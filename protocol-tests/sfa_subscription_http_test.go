package libbox

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type sfaCountedBody struct {
	read   int
	closed bool
}

func (b *sfaCountedBody) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'a'
	}
	b.read += len(p)
	return len(p), nil
}
func (b *sfaCountedBody) Close() error { b.closed = true; return nil }

func TestSFASubscriptionHTTPResponseLimit(t *testing.T) {
	for _, length := range []int64{-1, 100000} {
		body := &sfaCountedBody{}
		response := &httpResponse{Response: &http.Response{Body: body, ContentLength: length}, responseLimit: 64}
		_, err := response.GetContent()
		if err == nil || !strings.Contains(err.Error(), "HTTP_RESPONSE_TOO_LARGE") {
			t.Fatalf("expected limit error: %v", err)
		}
		if !body.closed || body.read > 65 {
			t.Fatalf("unbounded or open body: %+v", body)
		}
		if length > 0 && body.read != 0 {
			t.Fatal("must reject oversized Content-Length before reading")
		}
		first := body.read
		_, err = response.GetContent()
		if err == nil || body.read != first {
			t.Fatal("re-reading failed response")
		}
	}
	for _, limit := range []int64{0, 4, 5} {
		response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader("test")), ContentLength: 4}, responseLimit: limit}
		got, err := response.GetContent()
		if err != nil || got.Value != "test" {
			t.Fatalf("exact/smaller response rejected: %v", err)
		}
	}
}

func TestSFASubscriptionHTTPTimeoutIncludesBody(t *testing.T) {
	for _, sendHeaders := range []bool{false, true} {
		t.Run(map[bool]string{false: "headers", true: "body"}[sendHeaders], func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if sendHeaders {
					w.WriteHeader(http.StatusOK)
					w.(http.Flusher).Flush()
				}
				<-r.Context().Done()
			}))
			defer server.Close()
			client := NewHTTPClient().(*httpClient)
			defer client.Close()
			client.SetTimeout(1)
			if client.client.Timeout != time.Second {
				t.Fatal("timeout API ignored")
			}
			client.client.Timeout = 80 * time.Millisecond
			req := client.NewRequest()
			if err := req.SetURL(server.URL); err != nil {
				t.Fatal(err)
			}
			start := time.Now()
			response, err := req.Execute()
			if err == nil {
				_, err = response.GetContent()
			}
			var timeout interface{ Timeout() bool }
			if !errors.As(err, &timeout) || !timeout.Timeout() {
				t.Fatalf("expected deadline: %v", err)
			}
			if time.Since(start) > time.Second {
				t.Fatal("exchange exceeded timeout")
			}
		})
	}
}

func TestSFASubscriptionHTTPCloseCancelsActiveRead(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer server.Close()
	client := NewHTTPClient()
	defer client.Close()
	req := client.NewRequest()
	req.SetResponseLimit(64)
	if err := req.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := req.Execute()
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := response.GetContent(); done <- err }()
	client.Close()
	client.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("closed read succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not cancel active read")
	}
	_, err = client.NewRequest().Execute()
	if err == nil {
		t.Fatal("request after Close succeeded")
	}
}

func TestSFASubscriptionHTTPRedirectKeepsLimit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/start" {
			http.Redirect(w, r, "/content", http.StatusFound)
			return
		}
		w.Write([]byte(strings.Repeat("a", 128)))
	}))
	defer server.Close()
	client := NewHTTPClient()
	defer client.Close()
	req := client.NewRequest()
	req.SetResponseLimit(64)
	if err := req.SetURL(server.URL + "/start"); err != nil {
		t.Fatal(err)
	}
	response, err := req.Execute()
	if err != nil {
		t.Fatal(err)
	}
	_, err = response.GetContent()
	if err == nil || !strings.Contains(err.Error(), "HTTP_RESPONSE_TOO_LARGE") {
		t.Fatalf("redirect lost limit: %v", err)
	}
}
