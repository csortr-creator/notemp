package xraybridge

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/transport/internet"
)

type scopeDNS struct {
	ip      string
	calls   atomic.Int32
	entered chan struct{}
	release chan struct{}
}

func (*scopeDNS) Type() interface{} { return dns.ClientType() }
func (*scopeDNS) Start() error      { return nil }
func (*scopeDNS) Close() error      { return nil }
func (d *scopeDNS) LookupIP(string, dns.IPOption) ([]xnet.IP, uint32, error) {
	d.calls.Add(1)
	if d.entered != nil {
		close(d.entered)
		<-d.release
	}
	return []xnet.IP{net.ParseIP(d.ip)}, 300, nil
}
func scopeLookup(ctx context.Context) (string, error) {
	ips, err := internet.LookupForIPContext(ctx, "same-owner-name.invalid", internet.DomainStrategy_FORCE_IP4, nil)
	if err != nil {
		return "", err
	}
	if len(ips) != 1 {
		return "", fmt.Errorf("IP count %d", len(ips))
	}
	return ips[0].String(), nil
}

func TestScopedDNSNeverBorrowsLegacyOrAnotherOwner(t *testing.T) {
	legacy := &scopeDNS{ip: "127.0.0.9"}
	internet.InitSystemDialer(legacy, nil)
	defer internet.InitSystemDialer(nil, nil)
	ips, err := internet.LookupForIP("legacy.invalid", internet.DomainStrategy_FORCE_IP4, nil)
	if err != nil || len(ips) != 1 || ips[0].String() != "127.0.0.9" {
		t.Fatalf("explicit legacy API: %v %v", ips, err)
	}
	legacy.calls.Store(0)
	if err := internet.InitSystemDialerContext(context.Background(), legacy, nil); err == nil {
		t.Fatal("initialized an unowned context")
	}
	a, b := internet.ContextWithSystemDialerScope(context.Background()), internet.ContextWithSystemDialerScope(context.Background())
	if _, err := scopeLookup(a); err == nil {
		t.Fatal("empty owner borrowed legacy DNS")
	}
	if err := internet.InitSystemDialerContext(a, &scopeDNS{ip: "127.0.0.1"}, nil); err != nil {
		t.Fatal(err)
	}
	if err := internet.InitSystemDialerContext(b, &scopeDNS{ip: "127.0.0.2"}, nil); err != nil {
		t.Fatal(err)
	}
	if err := internet.InitSystemDialerContext(a, legacy, nil); err == nil {
		t.Fatal("reinitialized existing owner")
	}
	var wg sync.WaitGroup
	for _, test := range []struct {
		ctx context.Context
		ip  string
	}{{a, "127.0.0.1"}, {b, "127.0.0.2"}} {
		wg.Add(1)
		go func(ctx context.Context, want string) {
			defer wg.Done()
			for range 100 {
				if got, err := scopeLookup(ctx); err != nil || got != want {
					t.Errorf("lookup %s: %s %v", want, got, err)
					return
				}
			}
		}(test.ctx, test.ip)
	}
	wg.Wait()
	internet.RetireSystemDialerContext(a)
	if _, err := scopeLookup(a); err == nil {
		t.Fatal("retired owner borrowed a resolver")
	}
	if err := internet.InitSystemDialerContext(a, legacy, nil); err == nil {
		t.Fatal("reinitialized retired owner")
	}
	if got, err := scopeLookup(b); err != nil || got != "127.0.0.2" {
		t.Fatalf("live owner %s: %v", got, err)
	}
	if legacy.calls.Load() != 0 {
		t.Fatal("owned request consulted legacy DNS")
	}
}

func TestScopedDNSRejectsAnswerAfterOwnerRetirement(t *testing.T) {
	fixture := &scopeDNS{ip: "127.0.0.1", entered: make(chan struct{}), release: make(chan struct{})}
	ctx := internet.ContextWithSystemDialerScope(context.Background())
	if err := internet.InitSystemDialerContext(ctx, fixture, nil); err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	unblock := func() { once.Do(func() { close(fixture.release) }) }
	defer unblock()
	result := make(chan error, 1)
	go func() { _, err := scopeLookup(ctx); result <- err }()
	select {
	case <-fixture.entered:
	case <-time.After(time.Second):
		t.Fatal("DNS not started")
	}
	internet.RetireSystemDialerContext(ctx)
	unblock()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("accepted late DNS answer")
		}
	case <-time.After(time.Second):
		t.Fatal("DNS did not finish")
	}
}

func managedConfig(t *testing.T, port int, outbounds any, ip string) []byte {
	t.Helper()
	var cfg map[string]any
	if err := json.Unmarshal(fixtureConfig(t, port), &cfg); err != nil {
		t.Fatal(err)
	}
	cfg["outbounds"] = outbounds
	cfg["dns"] = map[string]any{"hosts": map[string]string{"managed-owner.invalid": ip}, "queryStrategy": "UseIPv4"}
	data, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// Host bridges deliberately allow socket strategies. This verifies per-instance
// Xray-managed DNS before the socket dispatcher, not Android socket capabilities.
func TestRealManagedDNSOwnersAndDetachedContext(t *testing.T) {
	a := ownerReplyServer(t, "127.0.0.1:0", "dns-A")
	port := a.Addr().(*net.TCPAddr).Port
	ownerReplyServer(t, net.JoinHostPort("127.0.0.2", strconv.Itoa(port)), "dns-B")
	outbounds := []any{map[string]any{"protocol": "freedom", "streamSettings": map[string]any{"sockopt": map[string]any{"domainStrategy": "ForceIPv4"}}}}
	ba, bb := NewBridge(), NewBridge()
	pa, pb := freePort(t), freePort(t)
	if err := ba.Start(context.Background(), managedConfig(t, pa, outbounds, "127.0.0.1")); err != nil {
		t.Fatal(err)
	}
	defer ba.Stop()
	if err := bb.Start(context.Background(), managedConfig(t, pb, outbounds, "127.0.0.2")); err != nil {
		t.Fatal(err)
	}
	defer bb.Stop()
	target := net.JoinHostPort("managed-owner.invalid", strconv.Itoa(port))
	dispatcherReply(t, pa, target, "dns-A")
	dispatcherReply(t, pb, target, "dns-B")
	// XrayKey is explicitly exported by core for tests. No private-field access.
	type requestKey struct{}
	request, cancel := context.WithCancel(context.WithValue(context.WithValue(context.Background(), core.XrayKey(1), ba.instance), requestKey{}, true))
	cancel()
	detached := core.ToBackgroundDetachedContext(request)
	if detached.Err() != nil || detached.Value(requestKey{}) != nil {
		t.Fatal("detached context kept request state")
	}
	if ips, err := internet.LookupForIPContext(detached, "managed-owner.invalid", internet.DomainStrategy_FORCE_IP4, nil); err != nil || len(ips) != 1 || ips[0].String() != "127.0.0.1" {
		t.Fatalf("detached DNS %v: %v", ips, err)
	}
	c, err := internet.DialSystem(detached, xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(port)), nil)
	if err != nil {
		t.Fatal("detached context lost socket owner:", err)
	}
	c.SetDeadline(time.Now().Add(time.Second))
	c.Write([]byte("ping"))
	reply := make([]byte, 5)
	_, err = io.ReadFull(c, reply)
	c.Close()
	if err != nil || string(reply) != "dns-A" {
		t.Fatalf("detached reply %q: %v", reply, err)
	}
	if err := ba.Stop(); err != nil {
		t.Fatal(err)
	}
	if _, err := internet.LookupForIPContext(detached, "managed-owner.invalid", internet.DomainStrategy_FORCE_IP4, nil); err == nil {
		t.Fatal("retired detached context borrowed live owner's DNS")
	}
	dispatcherReply(t, pb, target, "dns-B")
}

func TestRealDialerProxyUsesItsOwnOutboundManager(t *testing.T) {
	a := ownerReplyServer(t, "127.0.0.1:0", "hop-A")
	b := ownerReplyServer(t, "127.0.0.1:0", "hop-B")
	makeConfig := func(port int, target string) []byte {
		return managedConfig(t, port, []any{
			map[string]any{"tag": "entry", "protocol": "freedom", "streamSettings": map[string]any{"sockopt": map[string]any{"dialerProxy": "hop"}}},
			map[string]any{"tag": "hop", "protocol": "freedom", "settings": map[string]any{"redirect": target}},
		}, "127.0.0.1")
	}
	ba, bb := NewBridge(), NewBridge()
	pa, pb := freePort(t), freePort(t)
	if err := ba.Start(context.Background(), makeConfig(pa, a.Addr().String())); err != nil {
		t.Fatal(err)
	}
	defer ba.Stop()
	if err := bb.Start(context.Background(), makeConfig(pb, b.Addr().String())); err != nil {
		t.Fatal(err)
	}
	defer bb.Stop()
	dispatcherReply(t, pa, "127.0.0.1:1", "hop-A")
	dispatcherReply(t, pb, "127.0.0.1:1", "hop-B")
	internet.InitSystemDialer(nil, ba.instance.GetFeature(outbound.ManagerType()).(outbound.Manager))
	defer internet.InitSystemDialer(nil, nil)
	empty := internet.ContextWithSystemDialerScope(context.Background())
	retired := internet.ContextWithSystemDialerScope(context.Background())
	internet.RetireSystemDialerContext(retired)
	for _, scope := range []context.Context{empty, retired} {
		c, err := internet.DialSystem(scope, xnet.TCPDestination(xnet.LocalHostIP, 1), &internet.SocketConfig{DialerProxy: "hop"})
		if c != nil {
			c.Close()
		}
		if err == nil {
			t.Fatal("retired owner borrowed legacy outbound manager")
		}
	}
	bb.Stop()
	dispatcherReply(t, pa, "127.0.0.1:1", "hop-A")
}

func TestConcurrentCoreConstructionHasIndependentDNS(t *testing.T) {
	const count = 6
	var wg sync.WaitGroup
	for i := range count {
		port := freePort(t)
		config := managedConfig(t, port, []any{map[string]any{"protocol": "freedom"}}, fmt.Sprintf("127.0.0.%d", i+1))
		wg.Add(1)
		go func(want string) {
			defer wg.Done()
			bridge := NewBridge()
			if err := bridge.Start(context.Background(), config); err != nil {
				t.Error(err)
				return
			}
			defer bridge.Stop()
			ctx := core.ToBackgroundDetachedContext(context.WithValue(context.Background(), core.XrayKey(1), bridge.instance))
			for range 20 {
				ips, err := internet.LookupForIPContext(ctx, "managed-owner.invalid", internet.DomainStrategy_FORCE_IP4, nil)
				if err != nil || len(ips) != 1 || ips[0].String() != want {
					t.Errorf("owner %s: %v %v", want, ips, err)
					return
				}
			}
		}(fmt.Sprintf("127.0.0.%d", i+1))
	}
	wg.Wait()
}
