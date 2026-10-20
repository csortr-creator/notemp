# Next gate: Android network ownership for Xray

This is an implementation handoff, not a claim that Android protection is wired.
Keep the production executor selection unchanged until the device gates pass.

## Existing entry points

- `libbox/xraybridge/bridge.go`: `StartProtected`, `UpdateNetwork`, `Stop` already
  own immutable network generations and retire DNS/dials/sockets before core Close.
- `libbox/composite_harness/native/native.go`: the bound facade still exposes only
  host `StartBridge`; it needs an explicit protected start/update API and a bound
  provider interface. Never silently reinterpret host start as protected execution.
- `app/src/main/java/io/nekohasekai/sfa/bg/DefaultNetworkMonitor.kt`: owns the live
  underlying network and guards stale listener registration. Its present listener
  belongs to Libbox; do not replace it with a second executor's listener.
- `app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt`: the existing auto-detect
  callback calls `protect(fd)` and discards its boolean. The new Xray adapter must
  check the result and also bind the socket to the selected underlying network.
- `app/src/main/java/io/nekohasekai/sfa/utils/OfflineOutboundProbe.kt`: shows
  `ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }`.
  The duplicate descriptor must close without taking ownership of Xray's fd.
- `app/src/main/java/io/nekohasekai/sfa/bg/LocalResolver.kt`: Android Q+ already uses
  DnsResolver with CancellationSignal; older Android uses Network.getAllByName.
  It reads the global network on each request, so it is not an immutable Xray
  generation provider. Do not reuse it unmodified for that contract.

## Required boundary

Create one provider per captured non-VPN `Network`. Socket callbacks must use that
captured network, check VpnService.protect, bind before connect/send, and propagate
failure. No default-network lookup, callback borrowing or direct fallback inside
an existing generation. Publish a new provider through UpdateNetwork when the
underlying network changes. Loss of the network must retire the old generation.

Expose cancellation across JNI explicitly. A lookup needs a unique request token
and a cancellation operation, or an equivalent owner-scoped completion object.
DNS must run on the captured Network. Q+ cancellation must cancel the actual
CancellationSignal and unblock the JNI request. For pre-Q blocking lookups, use a
bounded worker pool and discard late results after cancellation; acknowledge that
the platform call itself cannot be made interruptible by a Go context. Stop must
not wait for that worker to complete. Validate every returned IP in Go.

The existing Libbox ExchangeContext has an unexported Go context; constructing or
sharing it outside its owning package is not an adapter API. Define a dedicated
bound boundary instead of reaching into private fields or using reflection.

## Evidence needed before runtime activation

1. Host tests for the bound provider adapter: denied protection, DNS errors,
   cancellation while JNI lookup is pending, stale/duplicate completions, Stop
   and replacement, independent simultaneous owners. Preserve real XHTTP/Hysteria
   and legacy host tests in the combined dependency graph.
2. Rebuild the complete production-feature AAR; compare public Libbox API and
   native build contract; compile/package the Android app with the new JNI API.
3. Execute on Android: load JNI, real protect+bind, DNS cancellation, network loss,
   Wi-Fi/mobile handover and reload/Stop. Verify request traffic and failure cases,
   not only a bound Java class or a successful VPN service start.
4. Resolve or constrain upstream Xray process-global DNS/outbound-manager paths
   before accepting arbitrary full Xray JSON in simultaneous runtime/probe owners.
5. Wire original-import executor capability selection into both runtime and probes
   while preserving sing-box's TUN, DNS and user-routing ownership. Verify SOCKS UDP
   and required mask chains, then measure startup, transfer overhead and memory.
