package composite

import (
	"fmt"
	"net"
	"sync"
	"testing"
)

func TestExecutorFacade(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()

	facade := NewExecutorFacade()

	config := fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"fixture","pass":"composite_fixture_password_0123456789abcdef"}]}}],"outbounds":[{"protocol":"freedom"}]}`, port)

	if err := facade.StartBridge(config); err != nil {
		t.Fatal(err)
	}

	if err := facade.CheckSingboxConfig(`{"outbounds":[{"type":"direct","tag":"direct"}]}`); err != nil {
		t.Fatal(err)
	}

	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	c.Close()

	if err := facade.StopBridge(); err != nil {
		t.Fatal(err)
	}

	// Test idempotency
	if err := facade.StopBridge(); err != nil {
		t.Fatalf("expected nil error stopping already stopped bridge, got: %v", err)
	}

	// Test duplicate start error
	if err := facade.StartBridge(config); err != nil {
		t.Fatal(err)
	}
	if err := facade.StartBridge(config); err == nil {
		t.Fatal("expected error starting already started bridge")
	}
	facade.StopBridge()

	// Test concurrent execution
	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			facade.StartBridge(config)
			facade.StopBridge()
		}()
	}
	wg.Wait()
	// final stop to make sure everything is cleaned up
	facade.StopBridge()
}
