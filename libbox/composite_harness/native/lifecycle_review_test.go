package composite

import (
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
)

func facadeFixture(t *testing.T) (string, string) {
	t.Helper()
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := l.Addr().String()
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"fixture","pass":"composite_fixture_password_0123456789abcdef"}]}}],"outbounds":[{"protocol":"freedom"}]}`, port), address
}

func TestFacadeFailedStartCanRetryAndReleaseListener(t *testing.T) {
	f := NewExecutorFacade()
	defer f.StopBridge()
	if err := f.StartBridge("{broken"); err == nil {
		t.Fatal("invalid config started")
	}
	if err := f.StopBridge(); err != nil {
		t.Fatal(err)
	}
	config, address := facadeFixture(t)
	if err := f.StartBridge(config); err != nil {
		t.Fatalf("failed start prevented retry: %v", err)
	}
	if err := f.StartBridge("{broken"); err == nil {
		t.Fatal("second start accepted")
	}
	c, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatalf("second start destroyed active listener: %v", err)
	}
	c.Close()
	if err = f.StopBridge(); err != nil {
		t.Fatal(err)
	}
	l, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatalf("Stop leaked listener: %v", err)
	}
	l.Close()
}

func TestFacadeConcurrentStartHasOneOwner(t *testing.T) {
	f := NewExecutorFacade()
	defer f.StopBridge()
	config, address := facadeFixture(t)
	start := make(chan struct{})
	var workers sync.WaitGroup
	var successes atomic.Int32
	for range 8 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			if f.StartBridge(config) == nil {
				successes.Add(1)
			}
		}()
	}
	close(start)
	workers.Wait()
	if successes.Load() != 1 {
		t.Fatalf("concurrent start successes: %d", successes.Load())
	}
	c, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	for range 8 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if err := f.StopBridge(); err != nil {
				t.Error(err)
			}
		}()
	}
	workers.Wait()
	l, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatalf("concurrent Stop leaked listener: %v", err)
	}
	l.Close()
	if err = f.StartBridge(config); err != nil {
		t.Fatalf("concurrent Stop prevented restart: %v", err)
	}
}
