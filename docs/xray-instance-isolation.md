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
protected Android socket options are still rejected by the existing bridge.

The source preparer records hashes for BOTH ordered patches and all prepared
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
