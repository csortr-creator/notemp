package xraybridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"
)

type ownerResource struct {
	closed  atomic.Int32
	onClose func()
}

func (r *ownerResource) Close() error {
	r.closed.Add(1)
	if r.onClose != nil {
		r.onClose()
	}
	return nil
}

func TestOwnedTransportResourceIsolationAndRetirement(t *testing.T) {
	a := internet.ContextWithSystemDialerScope(context.Background())
	b := internet.ContextWithSystemDialerScope(context.Background())
	defer internet.RetireSystemDialerContext(a)
	defer internet.RetireSystemDialerContext(b)
	ra, rb := &ownerResource{}, &ownerResource{}
	acquire := func(ctx context.Context, r *ownerResource) io.Closer {
		t.Helper()
		got, owned, err := internet.AcquireSystemDialerResource(ctx, "same-endpoint", func() (io.Closer, error) { return r, nil })
		if !owned || err != nil {
			t.Fatalf("acquire: %v %v", owned, err)
		}
		return got
	}
	if acquire(a, ra) != ra || acquire(b, rb) != rb {
		t.Fatal("owners shared a resource")
	}
	if acquire(a, &ownerResource{}) != ra {
		t.Fatal("live owner lost its cache")
	}
	reentered := make(chan struct{})
	ra.onClose = func() {
		_, _, err := internet.AcquireSystemDialerResource(a, "same-endpoint", func() (io.Closer, error) { t.Error("retired factory ran"); return nil, nil })
		if err == nil {
			t.Error("retired cache reused")
		}
		close(reentered)
	}
	go internet.RetireSystemDialerContext(a)
	select {
	case <-reentered:
	case <-time.After(time.Second):
		t.Fatal("Close callback blocked under cache lock")
	}
	internet.RetireSystemDialerContext(a)
	if ra.closed.Load() != 1 || rb.closed.Load() != 0 {
		t.Fatal("retirement affected another owner or closed twice")
	}
	if acquire(b, &ownerResource{}) != rb {
		t.Fatal("stopping A invalidated B")
	}
	// A failed construction never poisons the live cache.
	failure := errors.New("synthetic creation failure")
	if _, _, err := internet.AcquireSystemDialerResource(b, "retry", func() (io.Closer, error) { return nil, failure }); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	retry := &ownerResource{}
	got, _, err := internet.AcquireSystemDialerResource(b, "retry", func() (io.Closer, error) { return retry, nil })
	if err != nil || got != retry {
		t.Fatal("failed creation poisoned cache")
	}
}

func TestOwnedTransportRetirementRacesPendingCreation(t *testing.T) {
	for range 10 {
		ctx := internet.ContextWithSystemDialerScope(context.Background())
		const count = 12
		var ready, done sync.WaitGroup
		ready.Add(count)
		done.Add(count)
		release := make(chan struct{})
		resources := make([]*ownerResource, count)
		for i := range resources {
			r := &ownerResource{}
			resources[i] = r
			go func() {
				defer done.Done()
				_, _, err := internet.AcquireSystemDialerResource(ctx, "same-key", func() (io.Closer, error) { ready.Done(); <-release; return r, nil })
				if err == nil {
					t.Error("resource inserted after retirement")
				}
			}()
		}
		ready.Wait()
		internet.RetireSystemDialerContext(ctx)
		close(release)
		done.Wait()
		for _, r := range resources {
			if r.closed.Load() != 1 {
				t.Fatal("pending candidate leaked or closed twice")
			}
		}
	}
}

// Concurrent successful constructions retain one winner and close every loser.
func TestOwnedTransportConcurrentCreationAndCancelledInsertion(t *testing.T) {
	ctx := internet.ContextWithSystemDialerScope(context.Background())
	const count = 12
	var ready, done sync.WaitGroup
	ready.Add(count)
	done.Add(count)
	release := make(chan struct{})
	results := make(chan io.Closer, count)
	resources := make([]*ownerResource, count)
	for i := range resources {
		r := &ownerResource{}
		resources[i] = r
		go func() {
			defer done.Done()
			got, _, err := internet.AcquireSystemDialerResource(ctx, "shared", func() (io.Closer, error) { ready.Done(); <-release; return r, nil })
			if err != nil {
				t.Error(err)
			}
			results <- got
		}()
	}
	ready.Wait()
	close(release)
	done.Wait()
	close(results)
	var winner io.Closer
	for got := range results {
		if winner == nil {
			winner = got
		}
		if got != winner {
			t.Error("concurrent requests got different cached resources")
		}
	}
	var closed int32
	for _, r := range resources {
		closed += r.closed.Load()
	}
	if closed != count-1 {
		t.Fatalf("loser resources not closed: %d", closed)
	}
	internet.RetireSystemDialerContext(ctx)
	for _, r := range resources {
		if r.closed.Load() != 1 {
			t.Fatal("retirement leaked or double-closed a candidate")
		}
	}
	owner := internet.ContextWithSystemDialerScope(context.Background())
	defer internet.RetireSystemDialerContext(owner)
	request, cancel := context.WithCancel(owner)
	candidate := &ownerResource{}
	_, _, err := internet.AcquireSystemDialerResource(request, "cancelled", func() (io.Closer, error) { cancel(); return candidate, nil })
	if !errors.Is(err, context.Canceled) || candidate.closed.Load() != 1 {
		t.Fatal("cancelled request inserted a resource")
	}
}

func TestOwnedTransportContextUsesBaseAndBothCancellations(t *testing.T) {
	type key int
	owner := internet.ContextWithSystemDialerScope(context.WithValue(context.Background(), key(1), "owner"))
	request, cancelRequest := context.WithCancel(context.WithValue(owner, key(2), "request-only"))
	base := internet.SystemDialerOwnerBase(request)
	cancelRequest()
	if base.Err() != nil || base.Value(key(2)) != nil {
		t.Fatal("cached transport retained a request")
	}
	operation, cancelOperation := context.WithTimeout(context.Background(), time.Second)
	defer cancelOperation()
	ctx, release := internet.ContextWithSystemDialerOwner(operation, request)
	defer release()
	if ctx.Value(key(1)) != "owner" || ctx.Value(key(2)) != nil || ctx.Err() != nil {
		t.Fatal("owner values lost or request retained")
	}
	deadline, _ := ctx.Deadline()
	expected, _ := operation.Deadline()
	if deadline != expected {
		t.Fatal("operation deadline lost")
	}
	cancelOperation()
	if ctx.Err() == nil {
		t.Fatal("operation cancellation lost")
	}
	ctx2, release2 := internet.ContextWithSystemDialerOwner(context.Background(), base)
	defer release2()
	internet.RetireSystemDialerContext(owner)
	select {
	case <-ctx2.Done():
	case <-time.After(time.Second):
		t.Fatal("owner retirement did not cancel pending dial")
	}
}

func grpcFixtureServer(t *testing.T, listen string, port, targetPort int) {
	t.Helper()
	server := map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"inbounds":  []any{map[string]any{"listen": listen, "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{map[string]any{"id": "11111111-1111-1111-1111-111111111111"}}, "decryption": "none"}, "streamSettings": map[string]any{"network": "grpc", "grpcSettings": map[string]any{"serviceName": "owned-fixture"}}}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1/32"}, "port": targetPort}}}}},
	}
	data, err := json.Marshal(server)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := serial.DecodeJSONConfig(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	built, err := parsed.Build()
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.NewWithContext(ownedHostTestContext(t, context.Background()), built)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
}
func grpcFixtureClient(t *testing.T, port, serverPort int) []byte {
	t.Helper()
	var config map[string]any
	if err := json.Unmarshal(fixtureConfig(t, port), &config); err != nil {
		t.Fatal(err)
	}
	config["outbounds"] = []any{map[string]any{"protocol": "vless", "settings": map[string]any{"vnext": []any{map[string]any{"address": "grpc-owner.invalid", "port": serverPort, "users": []any{map[string]any{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}}}}}, "streamSettings": map[string]any{"network": "grpc", "grpcSettings": map[string]any{"serviceName": "owned-fixture"}}}}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func TestProtectedGRPCOwnersStopAndNetworkRecovery(t *testing.T) {
	target := ownerReplyServer(t, "127.0.0.1:0", "grpc-ok")
	serverPort := freePort(t)
	grpcFixtureServer(t, "127.0.0.1", serverPort, target.Addr().(*net.TCPAddr).Port)
	grpcFixtureServer(t, "127.0.0.2", serverPort, target.Addr().(*net.TCPAddr).Port)
	var protectA, protectB, lookupA, lookupB atomic.Int32
	protect := func(n *atomic.Int32) socketProtector {
		return func(ctx context.Context, _ string, _ uintptr) error { n.Add(1); return ctx.Err() }
	}
	resolve := func(ip string, n *atomic.Int32) domainResolver {
		return func(ctx context.Context, host string) ([]netip.Addr, error) {
			if host != "grpc-owner.invalid" {
				return nil, errors.New("unexpected DNS")
			}
			n.Add(1)
			return []netip.Addr{netip.MustParseAddr(ip)}, ctx.Err()
		}
	}
	a, b := NewBridge(), NewBridge()
	defer a.Stop()
	defer b.Stop()
	pa, pb := freePort(t), freePort(t)
	if err := a.StartProtected(context.Background(), grpcFixtureClient(t, pa, serverPort), protect(&protectA), resolve("127.0.0.1", &lookupA)); err != nil {
		t.Fatal(err)
	}
	if err := b.StartProtected(context.Background(), grpcFixtureClient(t, pb, serverPort), protect(&protectB), resolve("127.0.0.1", &lookupB)); err != nil {
		t.Fatal(err)
	}
	oldA := dispatcherReply(t, pa, target.Addr().String(), "grpc-ok")
	liveB := dispatcherReply(t, pb, target.Addr().String(), "grpc-ok")
	if err := a.SuspendNetwork(); err != nil {
		t.Fatal(err)
	}
	oldA.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := oldA.Read(make([]byte, 1)); err == nil {
		t.Fatal("suspended owner kept old transport")
	}
	// B's existing HTTP/2 stream survives A's loss and subsequent Stop.
	exchange := func() {
		t.Helper()
		liveB.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := liveB.Write([]byte("ping")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 7)
		if _, err := io.ReadFull(liveB, reply); err != nil || string(reply) != "grpc-ok" {
			t.Fatalf("other owner disrupted: %q %v", reply, err)
		}
	}
	exchange()
	if err := a.UpdateNetwork(protect(&protectA), resolve("127.0.0.2", &lookupA)); err != nil {
		t.Fatal(err)
	}
	dispatcherReply(t, pa, target.Addr().String(), "grpc-ok")
	if lookupA.Load() < 2 || protectA.Load() < 2 || lookupB.Load() == 0 || protectB.Load() == 0 {
		t.Fatalf("missed owned callbacks: %d %d / %d %d", lookupA.Load(), protectA.Load(), lookupB.Load(), protectB.Load())
	}
	retired := bridgeCoreContext(a)
	if err := a.Stop(); err != nil {
		t.Fatal(err)
	}
	exchange()
	_, owned, err := internet.AcquireSystemDialerResource(retired, "post-stop", func() (io.Closer, error) { t.Error("factory after Stop"); return nil, nil })
	if !owned || err == nil {
		t.Fatal("stopped core accepted cached resources")
	}
	dispatcherReply(t, pb, net.JoinHostPort("127.0.0.1", strconv.Itoa(target.Addr().(*net.TCPAddr).Port)), "grpc-ok")
}
