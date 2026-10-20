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
  uses sing-box in production. CI491/run39394300376 on merge `d85eff6` was still
  running at the last check. Check it; do not assume success from this record.

## Reproduce the native checks

Use Go1.27. Prepare Xray from the repository root:

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

The Hysteria regression changes the upstream global dialer only in a dedicated
test subprocess. It is not an Android/production dispatcher implementation.
The immutable fixture certificate uses `oneTimeLoading=true`; a separate upstream
server certificate hot-reload race is known and is not fixed by the closed-flag
patch. Keep the race detector and TLS verification enabled.

## Active Jules work and next gates

Session `620463814939411899`:
<https://jules.google.com/session/620463814939411899>

Last observed state: `IN_PROGRESS`, no published PR. Task: an explicitly owned,
protected Xray dispatcher integrated with Bridge lifecycle, with real domain,
denial, concurrent-session, Stop/cancellation and network-replacement tests.
Jules was notified of merge `d85eff6`, the required source preparation, and the
Hysteria regression. Do not duplicate that task without checking its state.

Early candidate installed a global dispatcher in `init` but registered no session
for plain `Bridge.Start`; the existing SOCKS test reproduced EOF. An IP-only
positive fixture did not establish DNS propagation. Those findings were sent to
Jules. Do not merge that candidate blindly, install/reset global dialers on every
Start/Stop, or add implicit direct fallback for unknown/stopped owners. Check the
actual final diff and run legacy plus new wire tests in both dependency graphs.

Next: protected dispatcher ownership; Android JNI loading, protect/bind and DNS
over the selected underlying network; network handover; SOCKS UDP/required mask
chains; executor selection for import/runtime/probes; device/performance checks.
Existing sing-box TUN/DNS/user-routing behavior must remain intact.

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
