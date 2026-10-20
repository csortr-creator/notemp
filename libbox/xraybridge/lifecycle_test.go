package xraybridge

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"
)

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}
func fixtureConfig(t *testing.T, port int) []byte {
	t.Helper()
	data, err := json.Marshal(map[string]interface{}{
		"log": map[string]interface{}{"loglevel": "none"},
		"inbounds": []interface{}{map[string]interface{}{
			"listen": "127.0.0.1", "port": port, "protocol": "socks",
			"settings": map[string]interface{}{"auth": "password", "udp": true,
				"accounts": []interface{}{map[string]interface{}{"user": "fixture", "pass": "xray_fixture_password_0123456789abcdef"}}},
		}},
		"outbounds": []interface{}{map[string]interface{}{"protocol": "freedom"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func TestBridgeInboundPolicy(t *testing.T) {
	cases := []struct {
		name string
		edit func(map[string]interface{})
	}{
		{"public", func(i map[string]interface{}) { i["listen"] = "0.0.0.0" }},
		{"hostname", func(i map[string]interface{}) { i["listen"] = "localhost" }},
		{"unauthenticated", func(i map[string]interface{}) { i["settings"].(map[string]interface{})["auth"] = "noauth" }},
		{"short-password", func(i map[string]interface{}) {
			i["settings"].(map[string]interface{})["accounts"].([]interface{})[0].(map[string]interface{})["pass"] = "short"
		}},
		{"http", func(i map[string]interface{}) { i["protocol"] = "http" }},
	}
	for _, scenario := range cases {
		t.Run(scenario.name, func(t *testing.T) {
			var root map[string]interface{}
			json.Unmarshal(fixtureConfig(t, freePort(t)), &root)
			scenario.edit(root["inbounds"].([]interface{})[0].(map[string]interface{}))
			data, _ := json.Marshal(root)
			if err := NewBridge().Start(context.Background(), data); !errors.Is(err, ErrInboundPolicy) {
				t.Fatalf("expected policy rejection, got %v", err)
			}
		})
	}
	for _, field := range []string{"api", "metrics", "reverse"} {
		t.Run(field, func(t *testing.T) {
			var root map[string]interface{}
			json.Unmarshal(fixtureConfig(t, freePort(t)), &root)
			root[field] = map[string]interface{}{"listen": "0.0.0.0:11111"}
			data, _ := json.Marshal(root)
			if err := NewBridge().Start(context.Background(), data); !errors.Is(err, ErrInboundPolicy) {
				t.Fatal(err)
			}
		})
	}
}
func TestBridgeRejectsSecondStart(t *testing.T) {
	b := NewBridge()
	if err := b.Start(context.Background(), fixtureConfig(t, freePort(t))); err != nil {
		t.Fatal(err)
	}
	defer b.Stop()
	if err := b.Start(context.Background(), fixtureConfig(t, freePort(t))); !errors.Is(err, ErrStarted) {
		t.Fatal(err)
	}
}
func TestBridgeCancelledBeforeStart(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := NewBridge().Start(ctx, fixtureConfig(t, freePort(t))); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
func TestBridgeCancellationAndReplacement(t *testing.T) {
	b := NewBridge()
	ctx, cancel := context.WithCancel(context.Background())
	port := freePort(t)
	if err := b.Start(ctx, fixtureConfig(t, port)); err != nil {
		t.Fatal(err)
	}
	cancel()
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(2 * time.Second)
	for {
		connection, err := net.DialTimeout("tcp", address, 50*time.Millisecond)
		if err != nil {
			break
		}
		connection.Close()
		if time.Now().After(deadline) {
			t.Fatal("context cancellation did not release listener")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := b.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := b.Start(context.Background(), fixtureConfig(t, port)); err != nil {
		t.Fatal(err)
	}
	defer b.Stop()
	cancel()
	connection, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatal("stale context closed replacement", err)
	}
	connection.Close()
}
func TestBridgeConcurrentStopsAndRestart(t *testing.T) {
	b := NewBridge()
	for i := 0; i < 3; i++ {
		if err := b.Start(context.Background(), fixtureConfig(t, freePort(t))); err != nil {
			t.Fatal(err)
		}
		var workers sync.WaitGroup
		for j := 0; j < 8; j++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				if err := b.Stop(); err != nil {
					t.Error(err)
				}
			}()
		}
		workers.Wait()
	}
}
func TestBridgeFailedStartClosesPartialInstance(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	firstPort := freePort(t)
	var root map[string]interface{}
	json.Unmarshal(fixtureConfig(t, firstPort), &root)
	first := root["inbounds"].([]interface{})[0].(map[string]interface{})
	second := map[string]interface{}{}
	for key, value := range first {
		second[key] = value
	}
	second["port"] = occupied.Addr().(*net.TCPAddr).Port
	root["inbounds"] = append(root["inbounds"].([]interface{}), second)
	data, _ := json.Marshal(root)
	b := NewBridge()
	if err := b.Start(context.Background(), data); err == nil {
		b.Stop()
		t.Fatal("occupied listener accepted")
	}
	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(firstPort)))
	if err != nil {
		t.Fatal("partial instance leaked listener", err)
	}
	listener.Close()
	if err := b.Start(context.Background(), fixtureConfig(t, firstPort)); err != nil {
		t.Fatal(err)
	}
	b.Stop()
}
