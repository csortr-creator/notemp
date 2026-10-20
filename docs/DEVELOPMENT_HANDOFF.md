# Development handoff — 20 October 2026

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

Latest completed gate: PR38, branch `fix/composite-production-parity`, tested code
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
and applies `patches/xray-hysteria-session-close.patch`. It never edits the Go
module cache. It verifies source and patch digests when reusing the output and
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
