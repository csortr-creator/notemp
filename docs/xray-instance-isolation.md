# Xray instance DNS and outbound feature isolation

The official pinned core assigned every new instance's DNS client and outbound
manager to process-wide variables in internet.InitSystemDialer. Transport DNS
strategies, outbound target strategies, freedom's DNS paths and dialerProxy then
used whichever instance had initialized most recently. Serializing Start alone
would not protect a live instance from another instance's subsequent Start.

The checksum-pinned patch xray-instance-dialer-features.patch changes this owner
contract, without changing Libbox's public Java API:

- Each core instance allocates a private context scope before creating features.
  Its DNS/outbound pair is initialized once and read through atomic snapshots.
- Every internal LookupForIP caller passes its context, including freedom's UDP
  packet writer. dialerProxy uses the manager from that same scope.
- Empty and retired scopes fail closed; they never consult the legacy default.
  Close retires the scope before closing features. Late DNS answers are rejected.
- Core context attachment preserves its instance's scope. Background DNS context
  derives from the instance base with cancellation detached, preserving the socket
  owner's identity without retaining per-request context values.
- Explicit legacy InitSystemDialer/LookupForIP remain available with a synchronized
  legacy snapshot. Creating a core no longer changes that process default.

Five regression tests cover independent context DNS under concurrent lookups,
empty/retired scopes against a different legacy default, a pending answer arriving
after retirement, real SOCKS traffic through two Xray-managed DNS configurations,
real dialerProxy chains with the same tag but different destinations, detached
DNS/socket identity and six concurrently constructed instances. These are grouped
into five Go test functions and run in the combined production dependency graph.
The socket-strategy/dialerProxy wire fixtures deliberately use host executors:
protected Android socket options were still rejected at that gate; the following
owned system-DNS gate now permits already-applied DNS/scheduling settings.

The source preparer records hashes for the ordered patches and all prepared
source files. It preserves and rejects modified, unowned or differently patched
output; explicitly preserve a verified old prepared tree before preparing a new
patch set. Four Python tests exercise the patch order, module-cache immutability,
verified reuse and rejection of changed patches/sources or unowned output.

PR41 closed the reviewed DNS/outbound-manager cross-instance paths. The following
gate addresses the audited local/system DNS and direct-DNS paths. Production
activation still needs an explicit generated client-config contract; arbitrary
full Xray JSON is not yet Android-safe. Transport cache ownership/lifetime remains
open. Android device
JNI/protect/bind, handover/reload, runtime/probe selection with sing-box TUN/routing,
SOCKS UDP/mask chains and performance gates remain open.

Validation: CI506/run39446460854 succeeded for code
108bd8fb0a2aff61bd880f6a48a0fc45a56c4e1f. Combined Go tests passed -race -count=3,
with the source-preparation, JVM lifecycle, AAR/API/JNI and Android packaging gates.
Artifact12276359097 independently contains matching hashes for both committed
patches and the contextual lookup in JNI; APK native bytes match the candidate AAR.


## 2026-10-20 — owned system DNS

The third checksum-recorded patch, xray-owned-system-dns.patch, routes core default
DNS, explicit localhost DNS, freedom final-rule resolution and UDP fallback
resolution through the permanent dispatcher and its session owner. Default DNS
supports contextual lookup without changing the legacy public DNS interface.
Protected owners use the selected Android provider's A/AAAA callback; failures,
missing owners and retired network generations never fall back to OS DNS. Host
owners deliberately retain cancellable system DNS. DNS operations participate in
the same epoch cancellation registry as pending socket operations.

Protected execution rejects DNS +local transports before initialization and
SRV/TXT socket strategies before stock record resolution: the current Android
provider only implements A/AAAA. It also rejects unreviewed finalmask types before
core construction, including nested download settings; only header-custom and
salamander currently have reviewed wire fixtures. Import retention is unaffected.
This is a restriction on the experimental protected executor, not a claim of
universal full-JSON Android compatibility.

Domain strategy and Happy Eyeballs are consumed by Xray before a concrete IP
socket reaches the provider. The bridge permits these already-applied settings;
other native socket options still fail closed. Integration tests exercise real
SOCKS exchanges with default and localhost DNS, change the owner resolver to a
second loopback server on network replacement, reject lookups after Stop, cancel
pending DNS on suspend, filter address families and reject direct-DNS/mask modes
before network callbacks. Local combined tests passed -race -count=3; Android CI
validation is recorded separately after completion.

Next transport audit: gRPC's fresh dial callback context currently loses owner
identity; transport caches (gRPC, split-HTTP/Xmux, Hysteria and ECH HTTP clients)
need owner-scoped lifetime and cleanup. These findings remain open, along with
Android device execution, SOCKS UDP/masks, performance and production activation.


### Transport audit acceptance criteria — 21 October 2026

Preserve socket-owner identity and the instance feature scope in gRPC-generated
dial contexts, together with gRPC's own cancellation/deadline, without retaining
a completed application request's cancellation. Cache insertion and retirement
must be race-safe: no callbacks under cache locks, no insertion after retirement,
no cross-instance reuse and no retained entries keeping stopped owners alive.
Do not reset process-global caches on every Start/Stop.

Regressions must cover identical endpoints/configurations in two owners, Stop of
one while the other continues real traffic, retirement racing insertion, failed
connect/retry, network loss/resume with changed DNS and repeated Start/Stop with
bounded resources. Cover gRPC first, then split-HTTP/Xmux, Hysteria and ECH. Keep
the combined -race -count=3 and Android AAR/API/JNI/packaging gates.


PR42 validation — 21 October 2026: code
`320fbda6b597dc2f58735b0e8c49a2dedfaac461` passed CI508/run39452695392,
attempt 2: combined Go -race -count=3, four source-preparation regressions, JVM
lifecycle tests, complete production-feature AAR, unchanged Libbox API/JNI and
Android app/native packaging checks. Attempt 1 failed fetching Kotlin's Maven BOM;
no code or check was changed to obtain the passing retry.
Independent inspection of artifact12280616559 confirmed all three patch hashes,
ResolveSystemDomain in JNI and identical AAR/APK native bytes.
JNI SHA256: 5b5adf94c07b6a5f3dde7dd3bab2565054b64da36a60dd5c78291ddaed400b8a.
Archive SHA256: 962602cb1ea81e5be64b8e1920e3d37b7131db898bafe83ba52eaa6cac0a0a82.
Later PR42 changes are documentation only. No device execution or production
activation is claimed. Next implementation remains gRPC context/cache ownership.


## 2026-10-21 — gRPC instance-owned connections

The fourth ordered patch, xray-grpc-owned-resources.patch, moves gRPC clients for
scoped core instances out of the process-global map into their instance's resource
registry. Core retirement cancels pending transport operations, removes the registry
and closes resources outside its lock. Creation also runs outside that lock;
concurrent candidates converge on one winner, losers close, and candidates that
finish after retirement or request cancellation close without insertion. Legacy
unscoped callers keep the upstream map.

The gRPC reconnect callback retains the instance base context, with a snapshot of
explicit dial metadata, rather than the first application's request context. Its
fresh operation context preserves the gRPC deadline/cancellation and adds owner
identity, DNS/outbound scope and owner-retirement cancellation. It never borrows
another instance's state or resets another owner's cache on Stop.

New regressions cover identical resource keys across owners, reentrant Close,
failed construction/retry, concurrent successful candidates, cancellation before
insertion, 120 creation/retirement races, context values/deadlines/cancellation,
and real SOCKS -> VLESS/gRPC -> destination traffic through two simultaneous
protected bridges. The wire test suspends A, keeps B's existing stream alive,
resumes A using a different loopback server address, stops A, and checks B again.
These are host callback fixtures, not Android device protection evidence.

Remaining transport work: apply ownership/retirement to split-HTTP/Xmux, Hysteria
and ECH clients/requests. This patch does not claim those caches are fixed. Device
JNI/protect/bind/handover, SOCKS UDP/mask, performance and production activation
remain open. Full Android CI evidence is recorded after completion.

The operation-context regression also checks a custom cancellation cause. It
failed against the first draft, which prioritized owner context internals, and
passed after preserving the operation's cancellation internals while explicitly
binding the feature scope. The superseded CI510 was cancelled before final
validation; only the subsequent final-code run is acceptance evidence.


Next split-HTTP/Xmux audit notes: upstream XmuxManager assumes the global dialer
map lock serializes GetXmuxClient and has no retirement method. Adding Close needs
its own synchronization and a retired state; reject late selection/creation and
close clients outside locks. DefaultDialerClient.Close currently closes HTTP/3
only. HTTP/1 and HTTP/2 idle transports and in-flight requests (which deliberately
use context.WithoutCancel) also need an explicit ownership/retirement contract.
A generic registry alone cannot prove those HTTP requests or pools are released.


PR43 validation — 21 October 2026: final code
`2b73e9efe85ac373b48b11d9b5c564b27482340f` passed CI511/run39542101838.
All combined Go tests passed -race -count=3, including five resource/context/gRPC
regression functions. Four source-preparation tests, JVM lifecycle tests, the
complete production-feature AAR and unchanged Libbox API/JNI comparison, Android
application compilation/packaging and native identity checks passed.
Independent inspection of artifact12320626101 confirmed all four committed patch
hashes, compiled AcquireSystemDialerResource/ContextWithSystemDialerOwner symbols,
and byte-identical AAR/APK native libraries.
JNI SHA256: 23898d58537530c8d9565b336c9346010d33f90eb93b29b1ba464e6e8c2fd881.
Archive SHA256: cc2cf309ec0fd1734d7ebd5dd8d0a37afb4b65a07dc45a0c3fbbe9bccd48944a.
Subsequent PR43 changes only record evidence/documentation. Next implementation:
split-HTTP/Xmux lifetime and HTTP pool/request cleanup, then Hysteria/ECH. Device
execution, UDP/mask, performance and production activation remain open gates.


## 2026-10-26 — split-HTTP/Xmux, Hysteria and ECH ownership

Ordered patches after gRPC complete the reviewed transport-cache ownership path:

- **Xmux**: `xray-xmux-owned-mux|dialer-a|dialer-b|client|tests` — XmuxManager
  retirement lock, owned `getHTTPClient` via AcquireSystemDialerResource, owner
  base for retained dials (TCP/Reality/TLS and H3), DefaultDialerClient Close for
  HTTP/1–3 idle pools with post-close reject, unit tests under -race.
- **Hysteria**: `xray-hysteria-owned-client-a|b` — instance-owned `*client` cache,
  Close retires QUIC/transport/pktConn, late dial/tcp/udp rejected, DialSystem
  bound to owner context; legacy global clientManager remains for unscoped callers.
- **ECH**: `xray-ech-owned-cache-a|b1|b2|b3|c` — GetTLSConfigContext, ApplyECH and
  QueryRecord take owner context; scoped ECHConfigCache entries retire with the
  instance; DOH/UDP dnsQuery DialSystem uses ContextWithSystemDialerOwner; owned
  Hysteria/XHTTP call sites pass GetTLSConfigContext(ownerCtx).

Source preparer applies **17** ordered Xray patches after the DOH residual patch
below. Local ownership regressions passed -race for Xmux, Hysteria and ECH cache
Close. CI on `8b7e40fd7b77a7009eba15e9d2a2cec9a6cd7828` (run 40285417961)
completed success for build and isolated Xray wire/composite proof.

**Still open:** Android device JNI/protect/bind/handover, SOCKS UDP/mask chains,
performance, and production activation. Host wire fixtures are not device
protection evidence. See `docs/xray-device-gate-matrix.md`.


### Residual ECH DOH HTTP client (2026-10-26)

`xray-ech-owned-doh-client-a.patch` moves scoped DOH `http.Client` instances into
AcquireSystemDialerResource (`ech-doh|…`) with `dohHTTPClient.Close` calling
CloseIdleConnections. Unscoped callers keep `clientForECHDOH`. Prepare chain is
**17** ordered patches. CI on `ce6c8f2c0dad` succeeded for build and isolated
Xray wire/composite proof. Device gates remain open.
