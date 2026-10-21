# Development handoff — 21 October 2026

This file and [the migration plan](../XRAY_MIGRATION_PLAN.md) are the repository
entry points for continuing after a workspace or account change. Fetch the latest
`dev` before relying on recorded SHAs, CI status or external task state.

## Goal and working agreement

Improve subscription import and execution across panels through verified
capabilities, preserving original settings and clear diagnostics. Do not promise
arbitrary future protocol support or turn configuration acceptance into a claim
of working traffic. The owner prefers substantial completed work per iteration,
Russian progress updates, and autonomous fixes/reviews over repeated approvals.
The owner authorizes Codex to use Jules and merge reviewed PRs after relevant
checks. Jules itself must not merge or release. Production Xray activation and
release remain gated on the Android/runtime/device checks below.

## Verified checkpoint

Latest completed gate: PR43, code
`2b73e9efe85ac373b48b11d9b5c564b27482340f`, CI511/run39542101838 SUCCESS.
The gRPC callback now carries its instance's network identity and feature scope,
preserves the operation deadline/custom cancellation cause, and drops the first
request's context from the retained reconnect callback. gRPC connections use an
instance-owned resource registry; retirement cancels work, clears the registry
and closes resources outside locks. Late and duplicate candidates close without
leaking into a retired cache. Other live instances retain their connections.

Five new regression functions include real protected SOCKS/VLESS/gRPC traffic
with two owners, loss/recovery to another loopback server and Stop isolation.
Combined Go tests passed -race -count=3; the four source-ownership tests, JVM
lifecycle, full AAR/API/JNI and Android app/packaging gates passed. The custom-cause
regression failed on the previous draft and passed on this final version.
CI510 was superseded; use CI511 as final-code evidence. Next: split-HTTP/Xmux,
then Hysteria and ECH cache/request ownership; see xray-instance-isolation.md.
No Android device execution or production activation is claimed.

### Earlier checkpoint: PR42

Completed implementation: PR42, code
`320fbda6b597dc2f58735b0e8c49a2dedfaac461`. CI508/run39452695392, attempt 2,
completed SUCCESS. The first attempt could not retrieve Kotlin's Maven BOM;
the same unchanged code passed on retry. The third reproducible Xray patch routes
reviewed default/localhost, freedom and UDP fallback DNS through the session's
network provider, with epoch cancellation and no OS fallback for protected owners.
Direct +local DNS, SRV/TXT and unreviewed masks are rejected before unsafe network
work in the experimental protected executor. Import retention is unaffected.

Next implementation: gRPC dial callback owner/context preservation, then transport
cache ownership and retirement (gRPC, split-HTTP/Xmux, Hysteria, ECH). Required
checks include two independent instances at identical endpoints, stopping one
without disrupting the other, concurrent cache insertion/retirement, retries,
network loss/recovery and repeated Start/Stop resource bounds. Never reset global
caches on every bridge Stop. See xray-instance-isolation.md. Device execution,
SOCKS UDP/masks, performance and production executor selection remain open.

### Earlier checkpoint: PR38

Completed gate: PR38, branch `fix/composite-production-parity`, tested code
`1cb1b1cfea6059312b171af48e3229f9aab954a7`. CI498/run39430917424 completed SUCCESS.
It uses the actual production Android builder and SagerNet gomobile v0.1.13,
23 production feature tags, API 24 and libbox.so. Go redacts linker metadata under
trimpath, so the recorder delegates unchanged and preserves actual bind argv.
The complete 211-class Libbox Java API matched the production-built reference.
The real otherDebug app assembled; packaging contains the exact candidate native
bytes and no conflicting libgojni.so. Artifact12267684773 was downloaded and
independently checked (ELF/JNI, public API, actual argv/feature tags, DEX, APK native
identity, both cores and prepared Xray source provenance). Digests are in the plan.
The verification APK uses explicit `-PverifyCompositeJni=true` (arm64 only, native
bytes preserved). Ordinary builds retain their existing selection and packaging.
No Android execution, production Xray activation or release is claimed.

All 25 bridge tests and combined/native proof passed three race-enabled repetitions;
4 Java API gate tests plus 7 build/packaging/recorder tests passed. Locally, 20
sing-box XHTTP/Reality and virtual-host cases passed under the actual combined
Go 1.27 module graph against the legacy v26.3.27 server (not a modern-server claim).
CI492, 494 and495 also finished SUCCESS. Next concrete adapter contract:
[Android network adapter](xray-android-network-adapter.md).

PR36 merged as `91d6866f36fcf22cec724e304c394c2d3d5423f9`.
Latest reviewed code: PR36 `b6a2201` (owned dispatcher) and PR37 `40bffc5`
(full Libbox binding/API gate). CI492's isolated native job passed; its separate
ordinary APK job was still building at this checkpoint. CI493/run39402388375
completed SUCCESS and artifact12256456399 was independently verified: 211 legacy
Libbox classes, exact public API, actual arm64 ELF/JNI, both cores and the same
prepared Xray source provenance. Archive digest and precise gates are recorded
in XRAY_MIGRATION_PLAN.md. All 25 bridge tests and the combined/native graph passed
three race-enabled repetitions. Do not mistake the earlier facade-only AAR for
the current complete-reference-API artifact. These gates do not enable Xray in APKs.

Earlier checkpoints:

- PR33 merged as `621ac1ea756b3e069f871f95a96f9aeb3b5f07de`: separate composite
  Android arm64 AAR and serialized JNI facade. This does not enable Xray in APKs.
- PR34 merged as `d85eff6891b28b3e5964dc151f6edc150bd2fd16`: preserve Xray's
  concrete UDP wrapper and patch the upstream Hysteria cleanup closed-flag race.
  Tested code is `c711fa29d29219675954bee1529977f7c9fb5bdf`; final `fe687b9`
  changes only migration documentation.
- [CI490](https://github.com/csortr-creator/notemp/actions/runs/39393352721)
  passed. [AAR artifact12251475013](https://github.com/csortr-creator/notemp/actions/runs/39393352721/artifacts/12251475013)
  was downloaded and independently checked: archive digest, actual ELF/Java/JNI,
  both cores, Android/arm64 and prepared Xray replacement. Its source provenance
  exactly matched locally tested source/patch digests. Artifacts expire.
- All23 bridge tests and the combined bridge/harness/facade graph passed three
  repetitions with the race detector. Real Hysteria2 TCP-over-QUIC, verified TLS,
  Salamander, explicit DNS/socket callbacks and denied protection are covered.
  This is not SOCKS UDP relay or Android socket-protection evidence.
- CI489 passed; [APK artifact12250108266](https://github.com/csortr-creator/notemp/actions/runs/39390790725/artifacts/12250108266)
  uses sing-box in production. CI491/run39394300376 on merge `d85eff6` also
  finished successfully (verified before this iteration).

## Reproduce the native checks

Use Go 1.27. Prepare Xray from the repository root:

```sh
python3 scripts/prepare_xray_core.py
```

The script downloads the exact official module version/checksum recorded in
`libbox/xraybridge/go.mod` and `go.sum`, copies source into ignored `xray-core/`,
and applies the ordered `patches/xray-hysteria-session-close.patch` and
`patches/xray-instance-dialer-features.patch`, followed by
`patches/xray-owned-system-dns.patch` and
`patches/xray-grpc-owned-resources.patch`. It never edits the Go module
cache. It verifies source and patch digests when reusing the output and
rejects/preserves unknown or modified output. Do not remove those guards.

Prepare `sing-box-core/` at upstream `v1.14.2`, exact commit
`af6e64c3b69e6132ebaee0e1a3d24e93903f6709`, then:

```sh
python3 scripts/apply_core_patches.py sing-box-core
go -C libbox/xraybridge test -mod=readonly -race -timeout 2m -count=3 ./...
go -C libbox/composite_harness test -mod=readonly -race -timeout 2m -ldflags=-checklinkname=0 -tags=with_quic,with_utls -count=3 github.com/sagernet/sing-box/xraybridge ./...
```

Run these suites sequentially: some legacy fixtures use fixed local ports.
The actual workflow prepares both trees. Dispatch `.github/workflows/build.yml`
with `isolated_aar_only=true`, `publish_release=false` for the separate AAR gate;
it skips APK/release jobs. Normal `dev` pushes run the ordinary builds.

The permanent dispatcher is installed once during package initialization.
The Hysteria subprocess now uses StartProtected without replacing that hook.
Host-owned raw Xray servers and protected client executors share the same dispatcher.
This is not Android protection evidence.
The immutable fixture certificate uses `oneTimeLoading=true`; a separate upstream
server certificate hot-reload race is known and is not fixed by the closed-flag
patch. Keep the race detector and TLS verification enabled.

## Active Jules work and next gates

Session `620463814939411899`:
<https://jules.google.com/session/620463814939411899>

Codex took over the incomplete candidate on 20 October. Jules was asked to stop
editing/creating a duplicate PR and provide findings. When reading activities,
follow every nextPageToken: pageSize=100 is not necessarily the latest page.

The reviewed implementation installs one permanent dispatcher during package init,
registers private per-Bridge context identities, and rejects missing/stopped owners.
Both explicit host Start and StartProtected have owned socket lifetimes; protected
callbacks never fall back to the host path. Stop retires DNS/dials/sockets before
core Close. UpdateNetwork is serialized with lifecycle. Startup errors clean up.

New real SOCKS tests prove two simultaneous owners resolve the same hostname to
different destinations, replace a network, reject stale/missing contexts and keep
the other owner alive. Stop cancels an actual pending resolver call. Real XHTTP
and Hysteria transport tests use StartProtected, explicit DNS and denied-socket
controls. Run both standalone and combined dependency graphs with -race -count=3.
Do not replace/reset the process hook in tests or add unowned direct fallback.

Next: implement the explicit protected JNI provider and immutable Android network
callbacks described in xray-android-network-adapter.md. The production-feature AAR,
Java API comparison and real app packaging gate are complete; JNI loading and
protect/bind/DNS execution on Android are not. Then network handover, SOCKS UDP and
required mask chains, original-import runtime/probe executor selection, and device/
performance gates. Keep sing-box TUN/DNS/user-routing ownership intact.

## Recover authorized API access

Credentials are deliberately absent. Obtain current keys separately from the
owner or the new session's authorized secret environment; account changes do not
transfer old session credentials automatically. Never print keys or commit them.

GitHub repository: `csortr-creator/notemp`, branch `dev`.
Base: `https://api.github.com/repos/csortr-creator/notemp`.
Use HTTPS with `Authorization: Bearer <GitHub PAT>` and a `User-Agent` header.
Inspect `/pulls`, `/actions/runs`, run `/jobs` and `/artifacts`. Create PRs with
`POST /pulls`; update their descriptions with `PATCH /pulls/{number}`. Merge only
after review/tests through `PUT /pulls/{number}/merge`, sending the exact reviewed
head SHA and `merge_method=merge`; require the response's `merged=true`.
For signed artifact/log redirects, do not forward the GitHub authorization header
to the redirected host. Use an ephemeral Git credential/header configuration for
fetch/push; never put the PAT in a remote URL or a tracked file. Preserve unrelated
dirty worktrees and prefer fast-forward synchronization after a verified merge.

Jules base: `https://jules.googleapis.com/v1alpha`, header
`X-Goog-Api-Key: <Jules key>`. Inspect `GET /sessions/{id}` and
`GET /sessions/{id}/activities`; examine actual code artifacts and PRs, not just
status messages. Send authorized task updates via `POST /sessions/{id}:sendMessage`
with a JSON `prompt`. Source is `sources/github/csortr-creator/notemp` with starting
branch `dev`. New implementation tasks can use `requirePlanApproval=false` and
`automationMode=AUTO_CREATE_PR`, with explicit scope, tests and no merge/release.
Never send GitHub/Jules keys or provider subscription credentials in task prompts.

CleanAPIs base: `https://cleanapis.com/v1`; Bearer authentication. Metadata access
to `/models` was checked. An attempted external source-code review was rejected
by automatic approval review because service/token permission did not establish
permission to transmit those source files. Do not retry or bypass that rejection
without specific authorization. The completed code review/tests were local.

### 2026-10-20 — protected JNI provider work

`libbox/composite_harness/native/network.go` exposes NetworkProvider,
LookupRequest and LookupCancellation; the facade adds StartProtectedBridge and
UpdateBridgeNetwork. See docs/xray-android-network-adapter.md for callback rules.
Android XrayNetworkProvider is currently in src/compositeVerification, included
only by verifyCompositeJni=true. Keep ordinary runtime/executor selection unchanged
until device tests and the remaining isolation/UDP/performance gates pass. No
credentials belong in this handoff or any public repository file.

PR39 verification: CI501 https://github.com/csortr-creator/notemp/actions/runs/39436646595
passed for code `460e37158e5067508cd319e2a6bc2b10c48aebb0`; any later PR39
commit updates documentation only. Artifact12271933250 includes the verification
debug APK, whose DEX contains XrayNetworkProvider and NetworkProvider. Its JNI
matches the candidate AAR byte for byte, SHA256
`410f4dca3d04694c7f7dc8359b247647c8cf91ab677bb8553f3541854957acd0`.
Host combined tests passed -race -count=3; renamed cancellation callback tests
also passed -race -count=3. CI500 deliberately failed the existing API gate because
Cancel structurally matched two Libbox classes; CancelLookup fixes the cause.
Do not weaken this gate. The experimental Android provider is compiled but not
connected to the production service. No phone networking/device test has run.

Next implementation: `BridgeNetworkLifecycle.kt` is the JVM-testable owner state
machine; `XrayNetworkOwner.kt` is its verification-only Android adapter. Native
`SuspendBridgeNetwork` retires the current network while retaining the core for
UpdateBridgeNetwork recovery. The Android owner exposes phase as StateFlow; start
registers desired execution, so callers must not treat its return as connectivity.
Do not wire this into production until the outstanding device/isolation gates.

PR40 final code `4462934fe38b427c715c9195bdbf93aaf82785ca` passed CI504:
https://github.com/csortr-creator/notemp/actions/runs/39441971420
Seven BridgeNetworkLifecycleTest scenarios and the combined Go suite (-race,
-count=3) passed, along with complete AAR/API/JNI, Android Kotlin compilation and
APK native identity checks. Any later PR40 commit only records this evidence.
Repeated notifications for the same live Network do not replace its generation;
a loss followed by recovery on that same Network does replace it. CI503 was
cancelled after its JVM stage to verify this final adjustment in CI504 instead.
No Android device JNI, connectivity/handover or production activation is claimed.

Instance feature isolation work: patches/xray-instance-dialer-features.patch
introduces context-scoped DNS/outbound pairs, explicit retirement, contextual DNS
lookups and owner-preserving detached DNS. scripts/prepare_xray_core.py applies
both reviewed patches and records their ordered hashes. Do not hand-edit a prepared
core or the module cache. See docs/xray-instance-isolation.md for regression coverage
and the remaining protected client-config/DNS-path and transport-cache audit gates.

PR41 final code `108bd8fb0a2aff61bd880f6a48a0fc45a56c4e1f` passed CI506:
https://github.com/csortr-creator/notemp/actions/runs/39446460854
The five feature-isolation regressions and combined Go suite passed -race -count=3;
four source-ownership tests, JVM lifecycle, AAR/API/JNI and Android packaging checks
passed. Artifact12276359097 was independently inspected: both patch hashes match
committed bytes, LookupForIPContext is compiled into JNI, APK JNI equals AAR JNI.
Native SHA256: b31b154fff89a8beb90c18ff6afc38f71bda6812a546cf6384e9f8bdf828b514.
Subsequent PR41 changes only record evidence in documentation. DNS/outbound feature
isolation is addressed for the reviewed paths; do not interpret this as arbitrary
full Xray JSON being safe for Android. Next audit: protected client-config/direct-DNS
paths and transport-cache lifetime, followed by device/UDP/performance/activation.


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
Android device and production activation gates remain open. Final CI511 evidence is recorded below.


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
