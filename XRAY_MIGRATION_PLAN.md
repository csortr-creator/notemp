# Xray executor migration — verified state, 20 October 2026

Production Android still uses pinned sing-box1.14.2 +24 patches. This directory
is an isolated Go module; it is not linked into the production AAR or used by
imports/probes. Merging its PR does not enable Xray support in the APK.

## Implemented and checked

- Embedded Xray v1.260327.0 can run an authenticated loopback SOCKS endpoint
  and pass a synthetic TCP response through a freedom outbound.
- `Bridge` serializes Start/Stop, rejects a second Start, uses the parent
  context, closes failed partial starts, and retires instances before restart.
  Cancellation of an old context cannot stop a replacement instance.
- Input policy requires literal loopback SOCKS inbounds, password auth and
  passwords of at least32bytes (maximum255); ancillary API/metrics/reverse
  endpoints are rejected. The caller must generate credentials with a CSPRNG;
  fixture passwords are synthetic constants, not random production secrets.
- Race tests repeat three times: context cancellation, repeated/concurrent Stop,
  restart, failed startup with an occupied listener and rejected inbound policies.
- Existing XHTTP tests are config/startup smoke tests only.
- CI checks this module in a separate Go1.26 job. This is not a composite build.

## Corrections to the original PoC claims

The former `TestBridgeFinalMask` contained no FinalMask configuration. It has
been renamed `TestBridgeTCPDialerProxyConfigSmoke`; its startup success proves
neither mask execution nor a working dialerProxy reference. The pinned Xray does contain FinalMask implementation in
`infra/conf/transport_internet.go` and `transport/internet/finalmask`; the absence
of a separately named file is not evidence of missing support. The bridge tests
still do not exercise those masks, so their compatibility is not verified.

No test here currently proves UDP traffic, XHTTP data exchange, mask chains,
wrong-key/mask rejection, Android socket protection or JNI integration. Socket
protection is not inherited merely because the app also uses sing-box. RSS and
latency overhead have not been measured.

## Required next gates

1. Pin an official Xray revision with the actually required wire features, then
   prove TCP/UDP and domain handling through real positive/negative fixtures.
   Do not assume a newer version tag exists in the Go module proxy: the queried
   module list currently ends at v1.260327.0; verify upstream tag/commit explicitly.
2. Build sing-box and Xray in one Go dependency graph/native library. Separate
   go.mod files do not prove dependency or gvisor compatibility in a composite AAR.
3. Connect every Xray socket to Android protection and the current underlying
   network, including DNS/UDP and network replacement. Verify on Android/JNI.
4. Route original Xray outbounds through authenticated loopback while preserving
   sing-box TUN/DNS/user routing. Share node selection, probe, reload and Stop;
   never silently fall back to direct on backend failure.
5. Integrate this contract into the common import pipeline and regression matrix;
   measure startup, idle/active RSS and transfer overhead before release.

Follow-up task: https://jules.google.com/session/15780583428840000315
No automatic release or merge approval follows from this document.
