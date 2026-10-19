# Single-core subscription compatibility

SFAxtnd uses its pinned sing-box core. Import translates equivalent settings;
it does not make an unsupported wire protocol work by dropping its options.
The table describes implemented paths, not a guarantee that a remote server is reachable.

| Input | Native representation | Scope / limit |
| --- | --- | --- |
| SNI / ALPN | `tls.server_name` / `tls.alpn` | Preserve provider values and certificate verification |
| Xray certificate pins / names | Native verification options | Existing mapping; no conversion into `insecure` |
| Reality public key / short ID / fingerprint | Native Reality TLS options | Existing core compatibility patches; fingerprint support still matters |
| Mihomo Reality `support-x25519mlkem768` | `tls.reality.support_x25519mlkem768` | URI/Base64 and Clash JSON/YAML; explicit false removes hybrid shares for legacy servers, absent/true preserves browser shares |
| XHTTP padding range | `transport.x_padding_bytes` | Existing parser/core support; conflicting aliases rejected |
| VLESS Encryption + Vision | `encryption` + `flow` | Native single-key `mlkem768x25519plus.native.0rtt/1rtt`; Vision can use the encryption connection without removing either layer |
| FinalMask UDP Salamander | Hysteria2 `obfs.type=salamander` | One mask, password-only settings, at least four UTF-8 bytes; proxy authentication remains separate |
| FinalMask TCP header-custom | VLESS `tcp_header.clients/servers` | One mask; literal `str` items; plain TCP without TLS/Reality, other transport, or Vision |
| FinalMask QUIC tuning | Native Hysteria2 BBR/MTU/hopping options | Existing supported subset; other QUIC fields are rejected |
| Hysteria2 URI / Xray JSON fingerprint | Source-aware QUIC conversion | Xray uses standard TLS here and does not apply its TCP fingerprint; preserve the independent QUIC Chrome parrot option |
| Native / Clash QUIC uTLS | Hysteria2 Chrome parrot for exact `chrome` | Explicit native/Clash uTLS remains strict; the Xray rule does not apply to these formats |
| Other FinalMask masks/chains | Import issue | Unsupported settings are not ignored or collapsed into a different mask |

## Shared mapping

`FinalMaskCompatibility` is used by URI and Xray JSON adapters through
`ProxyLinkParser.applyFinalMask`. It validates a copy before updating the node,
so a rejected mapping leaves no partially applied native settings behind.
Existing native obfuscation plus another FinalMask UDP layer is rejected even
when the passwords match: collapsing a two-layer mask would change the wire.

Trojan URI recognizes `fm` and rejects unsupported combinations instead of
silently claiming that the setting was applied. Legacy VMess JSON remains strict
about unsupported fields. This change does not convert all input formats or all
future Xray extensions automatically.

## Runtime extension

The small pinned-core TCP header patch implements literal sequence exchange:
write each client sequence, read the corresponding server sequence, then read
any remaining server sequences. Responses are compared exactly. Exchange happens
before VLESS Encryption and VLESS traffic, on both TCP and packet dialing paths.

The parser and core bound headers to 32 sequences per direction and 64 KiB total;
the parser additionally bounds item count. Exchange has a 15-second maximum,
honors a shorter dialing deadline, and closes the socket on cancellation.
Errors do not include literal header contents, and cancellation stays cancellation.
Unmasked outbounds do not enter this path.

## Verification

JVM fixtures exercise URI/Xray JSON conversion, password/TLS preservation,
unsupported masks/settings/chains, conflicting obfuscation, transactional mapping,
and no fingerprint bypass. Generated full configurations are checked by the
patched core in CI.

Official checksum-pinned Xray v26.9.9 is a **test fixture**, not a second runtime
in the APK. The wire tests cover:

- TCP header with plain VLESS, Encryption 0rtt and 1rtt, and fresh connections;
- complete HTTPS exchange with a trusted test certificate;
- wrong header rejection and cancelled header exchange;
- native Hysteria2 Salamander against Xray FinalMask, including wrong proxy
  authentication and wrong mask password rejection.

Loopback permission exists only in fixture configs. Android device and provider
tests are separate from successful import, config checks, and loopback tests.

## Subscription compatibility matrix

The same Encryption + Vision + Reality connection is tested through nine input
containers: URI, Base64 URI, Xray JSON, Xray JSON array, Clash JSON, Clash YAML,
Base64 YAML, sing-box JSON and sing-box outbound array. The matrix preserves
encryption, flow, UUID, public key, short ID, SNI and the chosen fingerprint,
and exports every generated configuration for native core validation.

The runtime uses the existing sing-vmess Vision implementation with the
`CommonConn` registry adapter from sing-box-lx. Its buffer layout and registry
entry are tested so a dependency change cannot silently invalidate the adapter.
The browser preset dependency is pinned to utls-lx; Firefox 148 and Safari 26.3
provide their own hybrid key shares, with Chrome Auto remaining Chrome 133.

The Xray interoperability matrix covers Chrome, Firefox and Safari with 0rtt
and 1rtt, fresh and resumed connections, TLS 1.3, and a 1.4 MiB upload/download.
Wrong Reality key/short ID and wrong Encryption key must fail. Vision tests run
without race/checkptr because sing-vmess uses unsafe reflection; ordinary
Reality, encryption and Hysteria tests retain race instrumentation.

Hysteria2 source conversion follows Xray's actual dialer: `GetTLSConfig`
returns `crypto/tls.Config`; `tlsSettings.fingerprint` is not used by that path.
`finalmask.quicParams.disableChromeParrot` is independent and is preserved.
The same rule applies to the Xray-style `fp` extension in Hysteria2 share links,
with a report note explaining it. SNI, certificate verification/pins, proxy
authentication and Salamander credentials are not changed. Native/Clash uTLS
settings are not reinterpreted as Xray hints. Hysteria2 wire tests cover both
parrot settings and reject wrong certificate names, authentication and masks.

Pinned implementation references:

- [Vision adapter, sing-box-lx 6b1cfa4](https://github.com/Leadaxe/sing-box-lx/blob/6b1cfa43d931ba5f7e79acaa64841290f25557de/protocol/vless/encryption/lx_vision.go)
- [utls-lx ad69b19](https://github.com/Leadaxe/utls-lx/tree/ad69b191e2f83367ec0ac5f3a1a61310732c43b9), locked by Go module checksums
- [Xray v26.9.9 Hysteria dialer](https://github.com/XTLS/Xray-core/blob/v26.9.9/transport/internet/hysteria/dialer.go)
- [Xray v26.9.9 TLS configuration](https://github.com/XTLS/Xray-core/blob/v26.9.9/transport/internet/tls/config.go)

## Remaining work

Audit unsupported combinations against native capabilities before implementing
new protocol code. Current limits include dynamic header items, arbitrary mask
chains, Salamander packet-size/gecko options, explicit native QUIC fingerprints,
multi-key Encryption profiles and Reality ML-DSA signatures. Keep offline probe/Stop lifecycle work separate
from protocol compatibility; this change does not claim to fix those behaviors.

No importer can guarantee arbitrary future protocol extensions or a remote
server's availability. Unsupported wire requirements must stay visible and must
not become a successful import by removing encryption or certificate checks.

References: pinned Xray `infra/conf/transport_finalmask.go` and
`transport/internet/finalmask/header/custom/tcp.go`; pinned sing-quic
`hysteria2/salamander.go`; local interoperability fixtures.

## Android network startup and remote rules

The initial default-interface update completes synchronously before the native
monitor registration returns. Android may deliver `onAvailable` before link
properties or an interface index exist; registration waits up to five seconds
for a usable snapshot instead of starting DNS/rule-set downloads against an
empty native interface list. Later link-property updates trigger a refresh.
Cancelled updates cannot publish into a replaced listener. A missing network
fails startup explicitly; routing rules are never skipped to make startup pass.

Hybrid-option regression tests capture the actual outgoing ClientHello for
Chrome/Firefox/Safari, including cloned configurations, and check both supported
groups and key shares. Legacy Xray with explicit false and current Xray with
explicit true must complete HTTPS and reject wrong Reality keys/short IDs.
The source flag is validated as a boolean and is rejected without Reality.


### Reality version policy and panel XHTTP regression

The old client advertised REALITY version 1.8.1. Peers explicitly configured
with `minClientVer: "26.3.27"` reject it before authenticating the session.
The initial compatibility patch reused the 26.3.27 value from
[sing-box-lx's pinned Reality implementation](https://github.com/Leadaxe/sing-box-lx/blob/6b1cfa43d931ba5f7e79acaa64841290f25557de/common/tls/reality_client.go).
The subsequent version-policy patch advances the verified compatibility value
to 26.9.9, as described below. Public-key and short-ID validation stay intact.
Xray versions do not all impose a floor by default.

`protocol-tests/xhttp/sfa_panel_reality_test.go` covers VLESS + Reality +
Chrome/Firefox + XHTTP auto/stream-up, path `/`, padding 112–1344 bytes, and
permissive, explicit 26.3.27, and explicit 26.9.9 minimum-version policies. Each case
exchanges 76 KiB through the tunnel against Xray 26.3.27 and 26.9.9.
The previous XHTTP fixture explicitly set the minimum to zero and tested
VMess with Chrome; it could not expose this incompatibility.
`TestSFARealityMinimumVersionInterop` also verifies reconnects and rejects
wrong keys and short IDs with the new floor enabled.

These local fixtures do not establish the version policy or availability of
any provider endpoint. In particular, Russia Hysteria2/Salamander failures
are independent of REALITY and require a reachable endpoint or its runtime
error to diagnose.


### Configured Reality clock

Reality previously overwrote its NTP/configured timestamp with `time.Now()`.
The incremental clock patch uses the same configured time as the TLS client.
`TestSFARealityConfiguredClock` captures and decrypts the authenticated outgoing
ClientHello with synthetic keys for Chrome and Firefox (including Clone), and
checks a clock deliberately different from the machine's. It fails before the
patch and passes afterward. This can correct clock-policy rejection when an
NTP correction is configured; it does not establish that clock skew caused a
particular provider rejection.

### External Stop during profile switching

Unscoped notification and Quick Settings Stop now record an explicit user-stop
generation. Starting services reject stale completion through the existing quiet
teardown path. The profile selection queue observes that generation synchronously
before taking or dispatching a request, discarding earlier pending work and
retaining the active cleanup slot. Dashboard Stop uses the same generation.
Quick Settings also accepts Stop during Starting. Internal switch/deletion stops
retain their existing scoped behavior. JVM tests exercise stale completion,
active/pending cancellation without a UI callback, later selection, and internal
switch preservation. Android startup/notification timing still requires device
verification.


### XHTTP authority with IP endpoints and TLS virtual hosts

The XHTTP HTTP authority now follows Xray precedence: explicit `host`, then the
TLS/Reality server name, then the dial address without its port. IPv6 literals
are bracketed. This selection is performed independently for upload and separate
download targets. The physical destination and TLS certificate checks are unchanged.

Previously, an IP endpoint with a separate TLS server name and an empty `host`
sent `IP:port` as HTTP authority. This is incompatible with strict virtual hosts.
`TestSFAXHTTPVirtualHost` reproduces HTTP 421 before the patch, while an explicit
Host override succeeds. After the patch, generated certificates with certificate
verification, Chrome/Firefox, auto/stream-up and explicit Host overrides all
complete a payload roundtrip through an IP endpoint and strict virtual-host
server. The new fixture tears down server streams before its HTTPS listener.

Source: [Xray 26.9.9 XHTTP dialer](https://github.com/XTLS/Xray-core/blob/v26.9.9/transport/internet/splithttp/dialer.go),
`Dial` and separate download URL construction. This defect matches the empty-Host,
IP-endpoint configuration shown for GeodemaWL Cobalt/Lead, but provider availability
still needs device verification. Magnetit is unreachable over TCP from the CI
runner; its device behavior remains open. The separate Finland Reality/XHTTP
failure is resolved by the version-policy fix and verified with the exact URI.


### Optional private provider comparison

The manual `Private provider diagnostic` workflow runs the patched production
core and reference Xray against identical node settings from a temporary encrypted
repository secret. It is not a release gate and never runs automatically.
It reports numeric node index, engine, diagnostic fingerprint, stage and a fixed
result code. It prints neither configs nor raw errors; reference Xray logging is
disabled. No credentials or provider JSON are stored in Git or artifacts.
Delete the temporary secret after the run. A skipped probe is not a provider PASS.

For detailed diagnosis, the secret may instead contain an object with `nodes`
and a base64 `report_public_key` (32-byte NaCl box public key). Errors have known
node identifiers removed before anonymous sealed-box encryption. Only ciphertext
is logged; keep the ephemeral private report key locally and discard it afterward.
The redaction and encryption round trip has a synthetic regression test.

The control fingerprints are diagnostic variants only; this does not change
fingerprints or select fallback settings in the product. The runner's network
can differ from the user's mobile network, and settings reconstructed from a
screenshot require caution if both implementations fail.

The isolated fixture resolves the endpoint explicitly because the low-level
sing-box default dialer accepts IPs only and has no application DNS router.
Both engines use one resolved IPv4 address while retaining TLS SNI and HTTP
authority. A synthetic domain-dialer test guards this diagnostic prerequisite.

### Reality version policy

The exact-text Finland URI exposed a server version policy: with one pinned
endpoint, Reality compatibility version 26.3.27 fails authentication, while
changing only that version to 26.9.9 completes HTTPS with Firefox and Chrome
to both test origins, matching reference Xray 26.9.9. Earlier runs without
fixture DNS resolution cannot establish native Reality compatibility.

The version-policy patch uses the verified 26.9.9 value. Local server-floor
tests cover 26.3.27 and 26.9.9, including negative key/short-ID controls and
Reality/XHTTP. This does not disable peer authentication or change imported
credentials, fingerprints, TLS settings, or transport parameters.

### HTTP/1 XHTTP connection lifecycle

The cleartext HTTP/1 branch now disables HTTP connection pooling, matching the
reference Xray dialer. Its source documents the problem with pooled chunked
downloads through a custom dialer; the TLS HTTP/1 branch already used this setting.
`TestTransportResourceCleanup` reproduced `context canceled` with the old
cleartext branch (both locally and in CI). After the change, 200 repetitions
with the race detector pass across stream-one, stream-up, and packet-up.


### URL probe origins, cancellation, and error stages

The default health probe retains gstatic as its primary origin and can test
example.com through the same outbound after half the shared deadline. The
primary retains its full budget; the first authenticated HTTP response wins and
the losing request is cancelled. A blocked outbound dial never starts a second
server dial. Explicit profile URLs, protocol settings, and TLS validation are
retained. Latency describes the origin that answered, rather than an ICMP ping.

Errors retain their cause and distinguish connect, tunnel/TLS, and HTTP-response
stages. Individual online failures are preserved when native group history
replays the same zero-delay result without an error code. An explicit TCP dial
timeout is shown as no TCP connectivity; a DNS/unspecified deadline remains a
generic timeout. This does not make an unreachable address/port available.

Closing an XHTTP stream now cancels a pending RoundTrip and wakes readers even
before response headers arrive. A late response is closed without resurrecting
the stream. Routing-context cancellation after a successful dial still leaves
the stream usable, as required for long-lived connections.

### Reality ALPN preservation

uTLS applies its browser preset back to the cloned TLS config. Reading
`uConfig.NextProtos` after the preset therefore reads browser defaults, rather
than the caller's requested ALPN. Reality previously sent both h2 and HTTP/1.1
when the caller explicitly requested either protocol alone.

The Reality patch snapshots the requested ALPN before building the preset and
marshals ALPN and key-share changes before authenticating the raw ClientHello.
`TestSFARealityConfiguredALPN` captures the actual wire record for Chrome/Firefox
and h2/HTTP1.1, including Clone; all four cases fail before and pass afterward.
Reality/XHTTP exchanges and wrong-key/short-ID controls verify that authentication
is retained. This corrects configured transport behavior; it is not proof that a
specific provider's authentication failure was caused by ALPN.

### Subscription refresh consistency and bounded fetches

A refresh binds its result to both the pre-fetch configuration generation and the
subscription URL/path. Editing the source invalidates pending fetches and partial
import confirmations. Refresh metadata is applied to the latest database row in a
Room transaction; it does not restore a pre-fetch name, icon, update interval, or
update toggle. Reordering subscriptions updates only `userOrder`. Profile editing
shares the configuration commit mutex and reloads the latest row before saving.
Disabling updates and changing their interval both reconfigure WorkManager.

Subscription HTTP requests have a 30-second deadline per attempt covering headers
and body reads. The native response reader enforces an optional 8 MiB limit before
an oversized body can be fully allocated, including chunked/decompressed responses.
The existing bounded retry policy now covers transient body-read failures as well
as connection failures. A size failure is not retried, and reports use the stable
`IMPORT_TOO_LARGE` diagnostic. Native HTTP client Close cancels active requests and
is idempotent. Other native HTTP callers keep unlimited/default behavior unless
these options are explicitly configured; streaming file downloads are unchanged.

Import bounds are checked before per-node native validation: 10,000 records and
8 MiB UTF-8 input. Native JSON outbound arrays preserve explicit tags exactly,
including percent signs and built-in names, so selector/default/detour references
remain valid. Ambiguous duplicate tags reject the array instead of silently
rewiring it. A BOM inside a Base64 subscription is normalized after decoding.

Within one import, a bounded cache of 256 definitive node validation results avoids
repeating native checks for identical entries. It clears at batch boundaries and
never caches unknown/unavailable results. A 100-entry identical-input test reduces
native calls from 101 to 2 (one family control and one node), without deduplicating
or dropping imported entries. This is a call-count result, not a device speed claim.
Unchanged profile reloads reuse fingerprints and avoid rewriting unchanged latency
cache data. Concurrent creation of a subscription HWID is serialized.

Regression coverage includes byte and entry limits, decoded BOMs, native reference
tags, changed-source rejection, cancelled pre-commit updates, bounded cache eviction,
parameter changes, cancellation propagation, slow headers/body reads, response limits,
redirects, and active-read cancellation. Android scheduling, database/UI integration,
Stop during switching, and network recovery still require full Android CI and device
checks. Servers timing out in other clients are not treated as protocol regressions.

Native legacy DNS migration uses URI parsing to retain custom ports, IPv6 literals,
and the complete HTTPS path/query; invalid legacy addresses fail import rather than
being partially reconstructed. The old geosite-ru URL migration changes only the
remote rule-set URL. It keeps tags and every connection field untouched, including
passwords that happen to equal a rule-set name. Both defects have failing baseline
regressions. Subscription requests report the actual native core version, and
profile-title accepts standard and URL-safe Base64, including unpadded values.
Full native JSON profiles also retain their explicit `route.final`, or the native
first-outbound default when it was omitted. Import does not replace it with a
nonexistent generated selector. Direct/block targets are added only when absent,
so system rules introduced during migration have valid targets; existing tags and
outbounds remain intact. Generated URI/Clash/Xray profiles keep their generated
selector behavior. The user's runtime final-outbound override remains a separate
application setting.

### Stop/reload ownership, network generations, and cancellable SRS preparation

Reload captures the running profile and switch generation before queueing or
preparing rules. It shares the lifecycle mutex with start/teardown and checks the
original ticket before native reload, runtime-state commit, and Main-thread Started
publication. It cannot adopt a generation created by Stop. Persisting the selected
profile is part of the final Main-thread publication instead of an earlier IO write.
Unscoped BoxService Stop invalidates a pending start even before receiver registration.
Starting can transition to Stopping; active start/reload jobs and interface registration
are cancelled. A concurrent native CloseService interrupts native startup before
teardown waits on the Kotlin lifecycle lock. TUN release and command-server closure
are idempotent and best-effort so one failed cleanup does not strand Stopping.
Managed RPC Stop, revocation, and unexpected destruction use the same stop path.

Network callbacks enqueue without blocking Main/ConnectivityThread. Each Android
registration has a generation; late available/update/lost events cannot mutate a
replacement registration. Cancelled Get requests are removed, the last subscriber
cancels pending waits, registration errors return to their caller, and a broken
listener does not terminate the shared actor. A cancelled Start removes only its
own listener, not a later registration under the same key. Legacy fallback avoids
selecting the VPN itself as its underlying network.

Initial interface polling runs outside the network monitor lock. It retries a
snapshot replaced before publication and can be cancelled during Stop, including
late registrations arriving after Stop began. Offline probes also wait for usable
LinkProperties/interface index before returning to native startup; network availability
alone is insufficient. An old awaited network result does not overwrite the live
monitor snapshot after onLost.

Subscription fetches and SRS downloads share an explicit cancellation bridge: Close
cancels native HTTP context and unblocks JNI network work. Subscription retry and
per-record parsing check cancellation, so a close-induced connection error does not
turn into another retry or partial import. Lazy HTTP creation cannot leak a client
created concurrently with Close. The first Java SRS cancellation experiment failed:
HttpURLConnection.disconnect did not unblock an active read within 1.5 seconds. The
production implementation therefore uses the native client with its full-exchange
30-second deadline rather than relying on Java interruption/disconnect.

The additional stream-bounds patch applies an explicitly configured response limit
to WriteTo/WriteToWithProgress as well as buffered GetContent. SRS uses 16 MiB and
subscription text uses 8 MiB. A default/unset limit leaves other file downloads
unchanged. SRS writes use unique temporary files, fsync, native validation, and atomic
replace, and remove incomplete files on failure/cancellation. Bundled preset seeding
requires both the exact preset URL and binary format; a matching tag alone must not
substitute a different source. Cache filenames bound the user-supplied tag component.

JVM tests compile fresh copies of the production network/SRS adapters against
OS/JNI test doubles. They exercise the actual coroutine/channel/file orchestration,
including delayed LinkProperties, stale callbacks, Stop during initial interface wait,
and cancellation of a synthetic HTTP response. These test doubles are confined to
protocol-tests and do not enter the APK. Native Go HTTP tests independently cover
bounded file copies and cancellation of an active streamed read under race detection.
Physical Android lifecycle, JNI integration, and device Wi-Fi/mobile handoff remain
separate verification gates; passing these tests does not mark those gates complete.
