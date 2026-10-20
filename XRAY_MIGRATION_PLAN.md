# Xray executor migration — 20 October 2026

Production Android still uses pinned sing-box1.14.2 plus24 patches. Xray remains
an isolated experimental module; no imported server or production probe uses it.

## Source pins

The bridge now uses official Xray tag `v26.9.9`, commit
`52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120`, expressed by Go as
`v1.260327.1-0.20260908222543-52a412d9e2f5`. The tag cannot be used directly as
`go get ...@v26.9.9` because this module does not have a `/v26` path. Resolving
its actual commit produces the canonical pseudo-version and checksum pin.
This source requires Go1.27, independently of the production Android build.
It now additionally uses the reviewed local Hysteria close-race patch. Prepare
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
  resolver for domains, resolves before creating UDP sockets, and rejects socket
  options until their semantics are implemented. The future Android owner must
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
  detector and three repetitions. Local validation passed all23 bridge test
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

## Remaining integration gates

1. The isolated AAR cross-build/export gate is complete. Verify JNI loading and
   lifecycle on Android; cross-compilation is not device execution.
2. Implement session-owned socket protection, underlying-network binding and DNS
   in the Android/native owner, including cancellation and network replacement.
   Never install an unowned global dialer or silently fall back to direct.
3. Verify SOCKS UDP relay, domain handling and required mask chains end to end;
   the present fixtures cover only the stated TCP and UDP-manager subsets.
4. Connect capable-executor selection to original imports, runtime and probes
   while preserving sing-box TUN/DNS/user routing and shared Stop/reload behavior.
5. Run device scenarios and measure startup, idle/active RSS and transfer overhead
   before enabling the executor in production or releasing it.

The earlier draft's concrete review is retained in
`docs/xray-executor-review-2026-10-20.md`; PR32's corrections are merged.
