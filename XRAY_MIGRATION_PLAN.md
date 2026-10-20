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
- `protectedDialer` is a private adapter experiment. TCP/UDP and IP/domain fixtures
  verify socket callbacks, denied protection, resolver failure/cancellation,
  invalid resolver output and unsupported options. There is no global setter or
  reset API; no process-global Xray dialer is modified. It requires an explicit
  resolver for domains, resolves before creating UDP sockets, and rejects socket
  options until their semantics are implemented. The future Android owner must
  protect and bind sockets AND provide DNS over the protected underlying network.
  These Linux callback tests are not Android/JNI protection evidence.
- `libbox/composite_harness` imports actual sing-box `experimental/libbox` and
  Xray in one graph, constructs sing-box while Xray runs, and closes both. This
  establishes host dependency/initialization compatibility, not a composite AAR.
- CI runs the isolated bridge suite and the composite host proof with the race
  detector and three repetitions. Local validation passed all16 bridge test
  functions and three composite initializations. Do not report a new CI run as
  passed until it ends.

## Remaining integration gates

1. Build a composite Android AAR/JNI entry point and check gomobile/toolchain
   compatibility. Host initialization is not an Android build.
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
`docs/xray-executor-review-2026-10-20.md`; PR32 is being corrected against it.
