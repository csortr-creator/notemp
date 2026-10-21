# Xray executor migration — 21 October 2026

Production Android still uses pinned sing-box1.14.2 plus24 patches. Xray remains
an isolated experimental module; no imported server or production probe uses it.

## Source pins

The bridge now uses official Xray tag `v26.9.9`, commit
`52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120`, expressed by Go as
`v1.260327.1-0.20260908222543-52a412d9e2f5`. The tag cannot be used directly as
`go get ...@v26.9.9` because this module does not have a `/v26` path. Resolving
its actual commit produces the canonical pseudo-version and checksum pin.
This source requires Go 1.27, independently of the production Android build.
It additionally applies three ordered reviewed patches: Hysteria close-race,
instance DNS/outbound isolation, and owned system DNS. Prepare
`xray-core/` with `python3 scripts/prepare_xray_core.py` before either Go module.
The source checksum, patch digest and full prepared source digests are verified;
the Go module cache stays immutable. This is a patched pin, not an unmodified tag.

The previous March pin has a reproducible race in XHTTP `WaitReadCloser.Set` /
`Read`. Upstream fixed it in September (commits77f98eba andeef6e63b). Use the
upstream revision rather than a local dependency-cache modification or a disabled
race detector. The composite harness uses the actual prepared, patched sing-box
core, not the obsolete checked-in `libbox/go.mod`.

## Validation contract

- Existing bridge lifecycle/policy tests cover authenticated loopback SOCKS TCP,
  wrong SOCKS passwords, serialized Start/Stop, cancellation, replacement,
  failed partial starts, and invalid listeners. Callers must generate credentials
  with a CSPRNG; fixed test credentials are synthetic.
- XHTTP tests run a real Xray VLESS server and the real client Bridge, with
  stream-up, extra padding112..1344 and a TCP `header-custom` mask. They inspect
  the built protobuf configuration, verify the configured header bytes and XHTTP
  options, and require a distinct destination reply. Wrong and absent masks must
  not pass that reply. Local SOCKS/setup errors fail the fixture. Modern Xray
  blocks private targets by default for VLESS; the fixture explicitly permits
  only its exact loopback destination/port, without changing production policy.
- UDP tests exercise real packet sockets and Xray's Salamander mask manager in
  both directions, plus wrong and absent masks. They verify receipt of an actual
  packet and inspect the built mask config. Salamander is obfuscation rather than
  authentication: mismatches may produce garbage instead of an explicit rejection.
  This is not a SOCKS UDP / VLESS UDP / QUIC end-to-end test.
- `protectedDialer` is a private session adapter. TCP/UDP and IP/domain fixtures
  verify socket callbacks, denied protection, resolver failure/cancellation,
  invalid resolver output and unsupported options. One permanent dispatcher is
  installed during package initialization; there is no per-instance global setter
  or reset API. It requires an explicit
  resolver for domains, resolves before creating UDP sockets, and rejects native socket
  options except already-consumed DNS strategy and IP scheduling settings. The future Android owner must
  protect and bind sockets AND provide DNS over the protected underlying network.
  These Linux callback tests are not Android/JNI protection evidence.
- A private `networkSession` now owns immutable callback generations: replacing
  the underlying network cancels registered DNS/dials synchronously and closes
  established sockets from that generation. Invalid updates preserve the current
  network; closing an old socket cannot close the replacement. UDP retains its
  required concrete `internet.PacketConnWrapper`, with ownership inside it. Six host regression tests cover replacement,
  in-flight DNS/protection, parent cancellation, concurrent Update/Dial/Close and
  invalid update rollback. The private context identity now selects this owner in
  real Xray dials, with Bridge Stop/startup cleanup and network replacement. Two
  simultaneous real SOCKS executors verify separate DNS/socket callbacks and
  distinct replies for the same hostname, stale-owner rejection and independent
  retirement. Stop cancels an actual blocked DNS request. Legacy host Start owns
  its own sockets explicitly; unknown contexts never fall back to it. XHTTP and
  Hysteria fixtures both use StartProtected with positive and denied protection.
  Android callbacks are still not connected.
  See `docs/xray-network-session.md`.
- `libbox/composite_harness` imports actual sing-box `experimental/libbox` and
  Xray in one graph, constructs sing-box while Xray runs, and closes both. This
  establishes host dependency/initialization compatibility. The separate native
  facade now also exposes serialized lifecycle and configuration calls to gomobile.
  The bridge suite is also repeated under this combined dependency graph, so its
  wire/protection behavior is checked after dependency version selection.
- CI runs the isolated bridge suite and the composite host proof with the race
  detector and three repetitions. Local validation passed all 23 bridge test
  functions and three composite initializations. Do not report a new CI run as
  passed until it ends.

## Isolated Android AAR

`libbox/composite_harness/native` exposes `ExecutorFacade` and its factory in
`io.nekohasekai.composite`. Failed starts can retry, duplicate starts preserve the
active listener, and concurrent starts/stops have one serialized owner. Host tests
exercise these behaviors with the actual combined sing-box/Xray dependency graph.

The isolated workflow pins the mobile tools, Android SDK and NDK, builds arm64
with QUIC/uTLS, checks the AArch64 ELF and generated Java API, and verifies JNI
exports before uploading the AAR. Dispatch with `isolated_aar_only=true` and
`publish_release=false`; APK and release jobs are skipped for this dispatch.

CI487 cross-compilation and ELF/Java checks passed. Its final symbol check failed
because the runner lacked ripgrep; the workflow now uses runner-provided grep.
[CI488](https://github.com/csortr-creator/notemp/actions/runs/39389947689) passed
on source commit `f9847b4d4e511ec215fc78acd02acea808b3fbce`, including exported JNI
checks and [artifact upload](https://github.com/csortr-creator/notemp/actions/runs/39389947689/artifacts/12249461293).
This facade is not linked into production. An AAR build does not establish Android
loading, protected sockets or VPN traffic.

The CI488 artifact predates the Hysteria ownership/race fix.
[CI490](https://github.com/csortr-creator/notemp/actions/runs/39393352721) passed
on `c711fa29d29219675954bee1529977f7c9fb5bdf` with the checksum-pinned, locally
patched Xray source. Its [AAR artifact](https://github.com/csortr-creator/notemp/actions/runs/39393352721/artifacts/12251475013)
also contains prepared source provenance and actual ELF Go build metadata.
The real host Hysteria regression verifies authenticated SOCKS TCP through
certificate-verified QUIC/Salamander to an HTTP destination, explicit DNS/socket
callbacks and denied protection. The adapter's old UDP wrapper panicked in this
path; the upstream cleanup race is fixed under the existing session-manager lock.
See `docs/xray-network-session.md` for the subprocess ownership boundary and the
separate, still-unresolved upstream server certificate hot-reload race.

## Owned dispatcher and complete Java API — 20 October 2026

[PR36](https://github.com/csortr-creator/notemp/pull/36) adds a permanent dispatcher,
private per-Bridge identities, owned explicit host execution, StartProtected and
serialized network replacement. Stop cancels pending DNS/dials and closes sockets
before core Close. Missing/stopped contexts fail closed. Real SOCKS fixtures prove
two independent owners for the same hostname, stale-context rejection after
restart, network replacement and cancellation of a pending DNS request. XHTTP and
Hysteria exchanges use the actual StartProtected path with explicit DNS and denied
protection controls. All 25 bridge tests passed with -race -count=3, standalone and
in the actual combined graph. The isolated native job in
[CI492](https://github.com/csortr-creator/notemp/actions/runs/39401850912) passed on
`b6a22014e543649a896ed8b81965334569c2ee99`; the separate ordinary APK job was still
building its unchanged production Libbox at the checkpoint, not yet a claimed pass.

[PR37](https://github.com/csortr-creator/notemp/pull/37) binds the entire Libbox
package alongside the facade. It builds a separate standalone reference using
the same pinned sources/toolchain and compares every public Java signature, JVM
descriptor and constant. Negative gate tests detect missing classes, changed
return descriptors and changed inlined constants.
[CI493](https://github.com/csortr-creator/notemp/actions/runs/39402388375) passed on
`40bffc52b790c364ed1bebd036038625bbb691ab`, including race tests, both Android
cross-builds, complete API comparison and actual JNI exports.
[Artifact12256456399](https://github.com/csortr-creator/notemp/actions/runs/39402388375/artifacts/12256456399)
was downloaded and independently checked: 211 Libbox classes, exact public API
report, AArch64 ELF, Libbox and facade JNI symbols, both cores in Android/arm64
build metadata and source provenance identical to the locally tested Xray tree.
Archive SHA-256: `fd81bed576a29136f91fcd188dc6fa2a1bdbcd0412c2d96ad83b4e85f48ecba6`.
The final documentation checkpoint may have a later SHA; no artifact is claimed
to have been built from an untested documentation commit.

## Production feature contract and application packaging — 20 October 2026

[PR38](https://github.com/csortr-creator/notemp/pull/38) replaces the limited-tag
reference with the real sing-box Android builder, restricted to arm64 but using
its actual main-variant configuration and pinned SagerNet gomobile v0.1.13. The
candidate inherits all 23 feature tags and linker settings from that invocation,
including production runtime GODEBUG defaults. API 24 and the libbox.so JNI name
match. Go omits linker flags from build metadata under trimpath: the recorder
captures actual argv and delegates unchanged; tags are independently checked in
ELF metadata. No manually duplicated feature list or invented metadata is used.

[CI498](https://github.com/csortr-creator/notemp/actions/runs/39430917424) completed
SUCCESS on `1cb1b1cfea6059312b171af48e3229f9aab954a7`. All 211 Libbox Java classes,
public descriptors and constants matched the production-built reference. Actual
Libbox and facade JNI exports passed. The real otherDebug app compiled and packaged
with the candidate. The explicit verification-only Gradle flag restricts the build
to arm64 and preserves exact native bytes; ordinary builds keep their prior ABI/
packaging settings. The APK contains the tested native library and valid DEX,
without a conflicting old libgojni.so. This is a test APK, not a production update
or Android execution/traffic proof. Production still selects sing-box.

[Artifact12267684773](https://github.com/csortr-creator/notemp/actions/runs/39430917424/artifacts/12267684773)
was downloaded and independently verified: actual ELF/JNI, public API report,
production argv and all 23 tags, API 24, both cores, Xray source provenance and exact
APK native identity. Archive SHA-256:
`f032007a71c39475dfa61e7c7155d1c7ee30c481aac76af2f364b7f83320ca4a`.
Native libbox.so SHA-256:
`6a21d4c4f3eb7f19cbe4222e085896edb3c3e40895ff796592b90746cd5fbb60`.
The final documentation-only SHA can be newer than the tested code above.

Validation also includes the combined bridge/native race suite with three
repetitions and 11 Java API/contract/recorder/packaging test functions. Locally,
20 existing sing-box PanelRealityXHTTP/virtual-host cases passed with -race in the
actual Go 1.27 composite module graph against the v26.3.27 server, through read-only
Go test overlays. The minClientVer case labels are not modern server versions.
The initial CI497 metadata check failed on the known trimpath redaction; CI498
verifies its real-argv correction. CI492, 494 and495 also finished successfully.

## Remaining integration gates

1. Execute JNI loading and lifecycle on Android. Production-feature API comparison
   and application compilation/packaging are complete; these do not prove device
   loading, working VPN traffic or production runtime activation.
2. Implement the explicit protected JNI provider and session-owned Android socket
   protection AND underlying-network binding, protected DNS and cancellation.
   Verify network handover; see [the concrete adapter contract](docs/xray-android-network-adapter.md).
   Review upstream process-global DNS/outbound managers before admitting arbitrary
   full Xray JSON in simultaneous runtime/probe instances.
3. Verify SOCKS UDP relay, domain handling and required mask chains end to end;
   the present fixtures cover only the stated TCP and UDP-manager subsets.
4. Connect capable-executor selection to original imports, runtime and probes
   while preserving sing-box TUN/DNS/user routing and shared Stop/reload behavior.
5. Run device scenarios and measure startup, idle/active RSS and transfer overhead
   before enabling the executor in production or releasing it.

The earlier draft's concrete review is retained in
`docs/xray-executor-review-2026-10-20.md`; PR32's corrections are merged.

### 2026-10-20 — protected JNI network boundary

Implemented owner-scoped asynchronous JNI DNS completion/cancellation and explicit
protected facade start/network replacement. Added an immutable Android provider
in the composite verification source set (protect + bind, Q+ cancellable DNS,
bounded legacy DNS). Host regression tests cover denied protection, real SOCKS
traffic, replacement closing sockets, pending DNS cancellation and stale replies.
Production executor activation remains gated on real Android execution/handover,
upstream global-manager isolation, UDP and performance verification.

Validation for this gate: PR39, code `460e37158e5067508cd319e2a6bc2b10c48aebb0`,
CI501/run39436646595 succeeded (full production-feature AAR, exact public Libbox
API, new JNI methods, Android app compilation and packaged-native identity).
Artifact12271933250 contains the verification debug APK. Independent archive
inspection confirmed XrayNetworkProvider and the new JNI provider API in DEX,
and exact AAR/APK native equality. No Android device execution is claimed.
Next gate: lifecycle/handover integration and device execution, with upstream
process-global DNS/outbound-manager isolation still required before activation.

### 2026-10-20 — network loss / handover lifecycle

Implemented explicit protected-network suspend/resume, a generation-guarded
lifecycle controller, and a verification-only Android owner with a keyed network
listener, bounded event queue and cancellation-safe cleanup. Observable phases
separate registration/waiting from a running bridge. Production activation still
requires device JNI/protect/bind/handover tests, upstream global-manager isolation,
UDP traffic and performance evidence. Validation is recorded after CI completion.

PR40 validation: final code `4462934fe38b427c715c9195bdbf93aaf82785ca`,
CI504/run39441971420 succeeded: seven JVM lifecycle tests, combined Go tests
with -race -count=3, full production-feature AAR, exact Libbox API/JNI checks,
Android owner compilation and packaged-native equality. CI503 was superseded
before completion to add preservation of sockets on repeated network events.
The Android owner remains verification-only. Next: device execution of JNI,
protect/bind, loss/recovery/reload and cancellation; constrain upstream global
managers before production executor selection and UDP/performance validation.

### 2026-10-20 — per-instance DNS and outbound features

Replaced Xray core's implicit process-wide DNS/outbound-manager assignment with
instance context scopes, updated every internal LookupForIP path and dialerProxy,
and preserved the owner's scope/socket identity for detached DNS contexts. Empty
and retired owners cannot borrow legacy or another instance's managers. Close
invalidates the scope and rejects late DNS results. See docs/xray-instance-isolation.md.
The source preparer now verifies an ordered two-patch chain; four source-ownership
regressions and five feature-isolation regressions were added. Device execution,
protected config/DNS-path constraints, cache lifetime, UDP and activation remain open.

PR41 validation: code `108bd8fb0a2aff61bd880f6a48a0fc45a56c4e1f`,
CI506/run39446460854 passed. Combined Go tests including five new isolation
regressions passed -race -count=3; four source-preparation tests passed, alongside
the JVM lifecycle and complete production-feature AAR/API/JNI/Android packaging
gates. Independent inspection of artifact12276359097 confirmed both committed
patch hashes in provenance, the contextual lookup compiled into JNI and exact
AAR/APK native equality. This closes the reviewed DNS/outbound-manager isolation
gate; protected config/DNS-path constraints and remaining activation gates stay open.


### 2026-10-20 — owned system DNS (current gate)

Implemented a third reproducible Xray patch for session-owned default/localhost,
freedom and UDP-fallback DNS. Protected A/AAAA uses the Android network provider
and shares epoch cancellation with socket operations. Missing/retired owners fail
closed. Direct +local DNS, SRV/TXT and unreviewed masks are rejected in protected
execution before unsafe network work. Import retention is unchanged. Real SOCKS
DNS/handover regressions and the combined Go suite passed -race -count=3; all four
source-preparation regressions passed. Android CI evidence will follow separately.
See docs/xray-instance-isolation.md for the exact scope.
Next: preserve gRPC dial callback owner context and retire owner-scoped transport
caches. Device JNI/protect/bind/handover, UDP/masks, performance and production
activation remain unverified; do not activate the executor based on host tests.


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


### 2026-10-21 — gRPC owner/cache implementation

Added a fourth ordered source patch for per-instance gRPC resources and callback
context ownership. Retirement cancels pending work and closes cached clients;
late/duplicate creations cannot leak into the retired cache. The cached callback
uses instance base values and retains the operation's deadline/cancellation.
Five new regression functions include real two-owner protected SOCKS/VLESS/gRPC
traffic with loss/recovery and Stop isolation. See xray-instance-isolation.md.
Next transport targets: split-HTTP/Xmux, Hysteria and ECH cache/request lifetime.
Android device and production activation gates remain open. CI evidence pending.
