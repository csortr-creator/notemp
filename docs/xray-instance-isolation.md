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

This closes the reviewed DNS/outbound-manager cross-instance paths, not every
possible Xray facility. Production activation still needs an explicit generated
client-config contract: local/system DNS and direct DNS transports can use paths
outside the protected socket callback; do not accept arbitrary full Xray JSON as
Android-safe. Transport cache ownership/lifetime also needs review. Android device
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
