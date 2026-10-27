# Xray device gate matrix

Host CI and transport-ownership patches are **not** device evidence.
Production executor selection stays on sing-box until every **required**
device scenario below passes on a real Android device (or emulator with a
working non-VPN underlying network and VpnService.protect).

Baseline code: branch `dev`, prepare chain **17** ordered Xray patches
(including ECH cache + DOH client ownership). Host proof: combined Go
`-race`, JVM lifecycle tests, production-feature AAR/API/JNI, isolated
Xray wire/composite job.

## Already closed on host (do not re-claim as device)

| Area | Evidence |
|------|----------|
| Instance DNS/outbound scope | PR41–42, isolation doc |
| Protected dialer + resolver fail-closed | `protect_test.go` |
| Bridge suspend/update/Stop | `lifecycle_test.go`, `network_session_test.go` |
| gRPC / Xmux / Hysteria / ECH ownership | ordered patches + unit tests |
| Composite verification Kotlin compile | `XrayNetworkOwner`, `XrayNetworkProvider` |
| Packaged JNI symbols (verification path) | CI AAR/APK identity checks |
| Verification service harness | `XrayVerificationService` (composite only) |

## Required device scenarios

Build the **composite verification** APK/AAR path only (`-PverifyCompositeJni=true`
or workflow isolated composite). Do not flip production executor selection.

### D1 — Protect and bind (TCP)

1. Start protected bridge with a minimal SOCKS→proxy config over cellular/Wi‑Fi
   (not the VPN network).
2. Confirm every outbound fd is `VpnService.protect`’d **and** bound to the
   captured `Network` before connect.
3. Failure of protect or bind must fail the dial (no silent OS routing into the
   VPN interface).
4. Pass: stable TCP download through the proxy; log/metrics show no
   XRAY_SOCKET_PROTECTION_DENIED on the success path.

### D2 — Protected DNS

1. Domain destinations only (no pre-resolved IPs).
2. Lookups must use the provider A/AAAA path for the **captured** network.
3. After `Stop` or network loss, in-flight lookups cancel; no OS DNS fallback
   for protected owners.
4. Pass: resolution works on the underlying network; post-Stop lookups fail.

### D3 — Network loss and handover

1. Start on Wi‑Fi; transfer traffic.
2. Disable Wi‑Fi so the underlying network is lost → generation retires;
   new dials fail until recovery.
3. Reconnect (possibly different network) → `UpdateBridgeNetwork` / owner
   restart with a **new** provider; old sockets do not leak.
4. Stale callbacks after `Stop` must be ignored (`BridgeNetworkLifecycle` token).
5. Pass: no crash; phase transitions waiting→running→suspended/failed→running;
   Libbox’s own `DefaultNetworkListener` registration remains intact.

### D4 — Dual owner isolation

1. Two protected bridges with distinct owners (or sequential start of A then B
   with overlapping lifetimes if the harness allows).
2. Stop A while B continues real traffic.
3. Pass: B streams stay up; A resources close; no cross-owner dialer/DNS reuse.

### D5 — UDP / mask (if claimed)

1. Real UDP exchange through protected path (not only TCP localhost).
2. Positive and negative finalmask controls with exact payload checks
   (see executor review notes: no short-slice equality false pass).
3. Pass only if both success and rejection paths behave as specified.

### D6 — Performance smoke (non-blocking for first device green)

1. Rough throughput and reconnect latency vs sing-box baseline on the same
   device and server.
2. Record numbers; do not gate activation solely on this until D1–D4 pass.

## Explicit non-goals until gates pass

- Changing production `BoxService` / executor selection to Xray.
- Replacing Libbox network monitor listener with the experimental owner.
- Publishing a user-facing “Xray core” release.

## Recording results

For each scenario note: device model, Android API, APK/AAR SHA, config class
(no secrets), pass/fail, and log excerpts without credentials. Append a dated
subsection to this file or to `docs/xray-android-network-adapter.md` after a
successful device run.

## Device runbook — Realme GT 6 (verification APK)

Build on a machine with the composite candidate AAR path (see CI isolated composite
and `-PverifyCompositeJni=true`). Package id: `io.nekonasekai.sfaxtnd`.

1. Install the verification APK (arm64-v8a).
2. ColorOS: disable battery optimization for the app; grant notification permission.
3. Grant VPN once:
   ```bash
   adb shell am start -n io.nekonasekai.sfaxtnd/io.nekohasekai.sfa.bg.XrayVerificationPrepareActivity
   ```
   Accept the system VPN dialog.
4. Push a policy-valid fixture (loopback SOCKS + password ≥32 chars, freedom or
   reviewed outbound only). Example shape (replace pass/port; no secrets in git):
   ```json
   {
     "log": {"loglevel": "warning"},
     "inbounds": [{
       "listen": "127.0.0.1", "port": 11080, "protocol": "socks",
       "settings": {
         "auth": "password", "udp": true,
         "accounts": [{"user": "fixture", "pass": "xray_fixture_password_0123456789abcdef"}]
       }
     }],
     "outbounds": [{"protocol": "freedom"}]
   }
   ```
   Prefer app-specific storage (no storage permission):
   ```bash
   adb push fixture.json /sdcard/Android/data/io.nekonasekai.sfaxtnd/files/xray-fixture.json
   ```
5. Start verification owner:
   ```bash
   adb shell am start-foreground-service \
     -n io.nekonasekai.sfaxtnd/io.nekohasekai.sfa.bg.XrayVerificationService \
     -a io.nekohasekai.sfa.action.XRAY_VERIFICATION_START \
     --es config_path xray-fixture.json
   ```
   (`config_path` is resolved against the app files dir and external files dir.)
6. Logs:
   ```bash
   adb logcat -s XrayVerification:I
   ```
   Expect `phase=WAITING_FOR_NETWORK` then `phase=RUNNING` on Wi‑Fi.
7. D1 smoke: SOCKS client to `127.0.0.1:11080` with fixture user/pass; traffic must
   leave on underlying Wi‑Fi (protect/bind). No `XRAY_SOCKET_PROTECTION_DENIED`.
8. Stop:
   ```bash
   adb shell am start-foreground-service \
     -n io.nekonasekai.sfaxtnd/io.nekohasekai.sfa.bg.XrayVerificationService \
     -a io.nekohasekai.sfa.action.XRAY_VERIFICATION_STOP
   ```

Production Connect button remains sing-box. Do not record a pass for D1 until
`phase=RUNNING` and a real TCP exchange succeed without protection errors.
