package xraybridge

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/dns/localdns"
	"github.com/xtls/xray-core/transport/internet"
)

func protectedDNSConfig(t *testing.T, port int, servers any) []byte {
	t.Helper()
	var root map[string]any
	if err := json.Unmarshal(fixtureConfig(t, port), &root); err != nil {
		t.Fatal(err)
	}
	root["outbounds"] = []any{map[string]any{"protocol": "freedom", "streamSettings": map[string]any{"sockopt": map[string]any{"domainStrategy": "ForceIPv4"}}}}
	if servers != nil {
		root["dns"] = map[string]any{"servers": servers}
	}
	data, err := json.Marshal(root)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func bridgeCoreContext(bridge *Bridge) context.Context {
	return core.ToBackgroundDetachedContext(context.WithValue(context.Background(), core.XrayKey(1), bridge.instance))
}

func TestProtectedCoreDefaultAndLocalDNSUseNetworkProvider(t *testing.T) {
	for _, test := range []struct {
		name    string
		servers any
	}{{"default", nil}, {"localhost", []string{"localhost"}}} {
		t.Run(test.name, func(t *testing.T) {
			target := ownerReplyServer(t, "127.0.0.1:0", "dns-A")
			port := target.Addr().(*net.TCPAddr).Port
			ownerReplyServer(t, net.JoinHostPort("127.0.0.2", strconv.Itoa(port)), "dns-B")
			var dnsCalls, protections atomic.Int32
			protect := func(ctx context.Context, _ string, _ uintptr) error { protections.Add(1); return ctx.Err() }
			resolver := func(ip string) domainResolver {
				return func(ctx context.Context, host string) ([]netip.Addr, error) {
					if host != "owned-system.invalid" {
						t.Errorf("unexpected name %s", host)
					}
					dnsCalls.Add(1)
					return []netip.Addr{netip.MustParseAddr(ip)}, ctx.Err()
				}
			}
			bridge := NewBridge()
			listen := freePort(t)
			if err := bridge.StartProtected(context.Background(), protectedDNSConfig(t, listen, test.servers), protect, resolver("127.0.0.1")); err != nil {
				t.Fatal(err)
			}
			defer bridge.Stop()
			owned := bridgeCoreContext(bridge)
			destination := net.JoinHostPort("owned-system.invalid", strconv.Itoa(port))
			dispatcherReply(t, listen, destination, "dns-A")
			if err := bridge.UpdateNetwork(protect, resolver("127.0.0.2")); err != nil {
				t.Fatal(err)
			}
			dispatcherReply(t, listen, destination, "dns-B")
			if dnsCalls.Load() < 2 || protections.Load() < 2 {
				t.Fatalf("missing provider calls DNS=%d protect=%d", dnsCalls.Load(), protections.Load())
			}
			if err := bridge.Stop(); err != nil {
				t.Fatal(err)
			}
			if _, err := internet.ResolveSystemDomain(owned, "owned-system.invalid"); !errors.Is(err, ErrSessionMissingOrStopped) {
				t.Fatalf("retired DNS: %v", err)
			}
		})
	}
}

func TestOwnedSystemDNSCancellationAndNoFallback(t *testing.T) {
	entered := make(chan struct{}, 1)
	bridge := NewBridge()
	if err := bridge.StartProtected(context.Background(), fixtureConfig(t, freePort(t)), func(context.Context, string, uintptr) error { return nil }, func(ctx context.Context, _ string) ([]netip.Addr, error) {
		entered <- struct{}{}
		<-ctx.Done()
		return nil, ctx.Err()
	}); err != nil {
		t.Fatal(err)
	}
	defer bridge.Stop()
	ctx := bridgeCoreContext(bridge)
	result := make(chan error, 1)
	go func() {
		_, err := internet.LookupForIPContext(ctx, "blocked.invalid", internet.DomainStrategy_FORCE_IP4, nil)
		result <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("provider not entered")
	}
	if err := bridge.SuspendNetwork(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("retired lookup succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("core DNS did not cancel")
	}
	if _, err := internet.ResolveSystemDomain(context.Background(), "missing-owner.invalid"); !errors.Is(err, ErrSessionMissingOrStopped) {
		t.Fatalf("missing owner: %v", err)
	}
}

func TestOwnedLocalResolverFiltersFamilies(t *testing.T) {
	bridge := NewBridge()
	if err := bridge.StartProtected(context.Background(), fixtureConfig(t, freePort(t)), func(context.Context, string, uintptr) error { return nil }, func(context.Context, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("::1"), netip.MustParseAddr("127.0.0.1")}, nil
	}); err != nil {
		t.Fatal(err)
	}
	defer bridge.Stop()
	resolver := localdns.New()
	ctx := bridgeCoreContext(bridge)
	for _, test := range []struct {
		option dns.IPOption
		ip     string
	}{{dns.IPOption{IPv4Enable: true}, "127.0.0.1"}, {dns.IPOption{IPv6Enable: true}, "::1"}} {
		ips, _, err := resolver.LookupIPContext(ctx, "families.invalid", test.option)
		if err != nil || len(ips) != 1 || ips[0].String() != test.ip {
			t.Fatalf("filtered %v: %v", ips, err)
		}
	}
}

func TestProtectedDirectDNSModesRejectedBeforeNetworking(t *testing.T) {
	for _, server := range []string{"https+local://unsafe.invalid/dns-query", "h2c+local://unsafe.invalid/dns-query", "quic+local://unsafe.invalid", "tcp+local://unsafe.invalid"} {
		t.Run(server, func(t *testing.T) {
			var calls atomic.Int32
			bridge := NewBridge()
			defer bridge.Stop()
			err := bridge.StartProtected(context.Background(), protectedDNSConfig(t, freePort(t), []string{server}), func(context.Context, string, uintptr) error { calls.Add(1); return nil }, func(context.Context, string) ([]netip.Addr, error) {
				calls.Add(1)
				return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
			})
			if err == nil {
				t.Fatal("accepted direct DNS transport")
			}
			if calls.Load() != 0 {
				t.Fatal("direct DNS initialization performed network work")
			}
		})
	}
	bridge := NewBridge()
	var calls atomic.Int32
	if err := bridge.StartProtected(context.Background(), fixtureConfig(t, freePort(t)), func(context.Context, string, uintptr) error { calls.Add(1); return nil }, func(context.Context, string) ([]netip.Addr, error) {
		calls.Add(1)
		return nil, errors.New("must not resolve")
	}); err != nil {
		t.Fatal(err)
	}
	defer bridge.Stop()
	ctx := bridgeCoreContext(bridge)
	for _, strategy := range []internet.AddressPortStrategy{internet.AddressPortStrategy_SrvPortOnly, internet.AddressPortStrategy_TxtPortOnly} {
		c, err := internet.DialSystem(ctx, xnet.TCPDestination(xnet.DomainAddress("_svc._tcp.unsafe.invalid"), 443), &internet.SocketConfig{AddressPortStrategy: strategy})
		if c != nil {
			c.Close()
		}
		if err == nil {
			t.Fatal("accepted SRV/TXT")
		}
	}
	if calls.Load() != 0 {
		t.Fatal("record lookup escaped the guard")
	}
}

func TestProtectedMasksRejectUnreviewedAuxiliaryNetworking(t *testing.T) {
	var calls atomic.Int32
	bridge := NewBridge()
	defer bridge.Stop()
	var root map[string]any
	if err := json.Unmarshal(fixtureConfig(t, freePort(t)), &root); err != nil {
		t.Fatal(err)
	}
	root["outbounds"] = []any{map[string]any{"protocol": "freedom", "streamSettings": map[string]any{"finalmask": map[string]any{"udp": []any{map[string]any{"type": "realm", "settings": map[string]any{}}}}}}}
	data, err := json.Marshal(root)
	if err != nil {
		t.Fatal(err)
	}
	err = bridge.StartProtected(context.Background(), data, func(context.Context, string, uintptr) error { calls.Add(1); return nil }, func(context.Context, string) ([]netip.Addr, error) { calls.Add(1); return nil, nil })
	if err == nil || err.Error() != "XRAY_PROTECTED_MASK_NOT_REVIEWED" {
		t.Fatalf("mask startup guard: %v", err)
	}
	if calls.Load() != 0 {
		t.Fatal("mask constructor performed networking")
	}

	for _, fragment := range []string{`{"finalmask":{"udp":[{"type":"realm","settings":{}}]}}`, `{"FiNaLmAsK":{"udp":[{"TYPE":"xicmp"}]}}`, `{"xhttpSettings":{"extra":{"downloadSettings":{"finalmask":{"udp":[{"type":"udphop"}]}}}}}`} {
		if validateProtectedMasks([]byte(fragment)) == nil {
			t.Fatal("accepted unreviewed mask", fragment)
		}
	}
	if err := validateProtectedMasks([]byte(`{"finalmask":{"tcp":[{"type":"header-custom","settings":{"clients":[[{"type":"str","packet":"fixture"}]]}}],"udp":[{"type":"salamander","settings":{"password":"fixture"}}]}}`)); err != nil {
		t.Fatal(err)
	}
}
