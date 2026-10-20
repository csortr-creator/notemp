# Xray network ownership contract

`libbox/xraybridge/dispatcher.go` installs one permanent Xray system dialer during
package initialization, before any executor starts. It selects a registered
`networkSession` by a private context identity. Missing, nil and stopped identities
fail closed; a socket is never assigned to the currently active or newest Bridge.
Stop removes the identity and cancels/closes its owner before calling core Close.
All startup failures retire partially registered owners. UpdateNetwork is serialized
with Start/Stop and only replaces callbacks on a live protected executor.

`Bridge.StartProtected` requires explicit protection and DNS callbacks. The older
`Bridge.Start` remains an explicitly requested host-only executor, with its own
registered owner and Xray default socket semantics. It is not an Android entry
point. Its availability never provides a fallback for unidentified protected dials.
The owner tracks the host executor's sockets too; host DNS retains upstream host
semantics. Android JNI/network callbacks are still not connected.
A session receives an explicit socket protector and domain resolver. Each Update
creates one immutable generation of these callbacks. The Android owner must later
provide callbacks that protect and bind the socket to the selected underlying
network, and resolve domains on that protected network. A missing callback is an
error; there is no automatic system DNS or direct fallback.

Network replacement retires the old generation before publishing the update to
other callers. Registered dials inherit the caller context and receive synchronous
cancellation under the owner lock. Old DNS/protection work therefore cannot return
a usable connection after retirement. Completed connections belong to the network
generation and outlive the temporary Dial context; replacement, session Close or
parent cancellation closes them. An invalid callback update leaves the live
generation intact. Late closes of retired connections cannot affect new sockets.

No callback or socket Close runs while the owner lock is held. In-flight callbacks
must respect cancellation; an arbitrary callback that blocks forever is not made
interruptible by this owner. OS operations already in flight are subject to the
normal `net.Dialer` cancellation semantics. UDP preserves Xray's concrete
`internet.PacketConnWrapper`, with ownership inside its `PacketConn` field.
Preserving only the interface caused the pinned Hysteria transport to panic when
type-switching on `*sessionPacketConn`. QUIC/masks unwrap the outer wrapper, so
retirement checks and closure must remain on the inner packet connection too.

Host regression coverage uses real TCP/UDP sockets and blocked DNS/protection
callbacks. It checks exchange after dial-context cancellation, retirement of
blocked reads, successful replacement, negative TCP protection after retirement,
UDP cancellation, concurrent Update/Dial/Close and invalid update rollback. The
standalone and combined sing-box/Xray graphs run these tests with the race detector.

`TestNetworkSessionHysteriaTransport` additionally runs real authenticated SOCKS
TCP through Xray Hysteria2, certificate-verified QUIC and a Salamander mask to an
HTTP destination with a distinct response. A synthetic domain must reach the
explicit resolver; the socket callback must run. The denied-protection control
must deliver no request to the destination, and runs only after positive success.
The client uses `StartProtected` and the same permanent dispatcher; it does not
replace the global hook. The subprocess still bounds the real QUIC fixture.
Raw Xray server fixtures declare separate host owners. XHTTP additionally exercises
protected domain resolution with a real header mask and an explicit denied-socket
negative control after the positive exchange.

`TestProtectedBridgeOwnersAndNetworkReplacement` starts two real SOCKS executors
simultaneously. The same synthetic hostname/port reaches different loopback IPs
and distinct replies using their respective resolver/protector callbacks. Updating
one owner closes its old socket and reaches the new IP. Stop/restart rejects its
old context, and the other executor keeps working. `TestProtectedBridgeStopCancelsPendingDNS`
blocks an actual Xray resolver call and requires Stop to cancel it and release the
SOCKS request promptly. These tests do not prove Android protection or SOCKS UDP.

That real transport fixture also exposed an upstream race: Hysteria's cleanup
loop read `udpSessionManager.closed` outside the mutex used by its writer. Prepare
the pinned dependency with `python3 scripts/prepare_xray_core.py` before running
either module. The script verifies the committed module checksum, copies source
without editing the module cache, applies `patches/xray-hysteria-session-close.patch`
and `patches/xray-instance-dialer-features.patch`, and records the ordered patch
chain and full source digests. Unknown or modified existing output is preserved
and rejected. Both standalone and composite modules replace Xray with that same
prepared tree. AAR artifacts include its source provenance and ELF build metadata.

The immutable fixture certificate uses `oneTimeLoading=true`. The upstream server
certificate hot-reload ticker independently races its certificate slice; this
fixture does not exercise hot reload, and no race detector is disabled. The bridge
does not accept TLS server inbounds. Production Xray activation remains gated.

Remaining gates: attach the Android protector/network/resolver and JNI lifecycle,
verify network replacement on device, and connect runtime/probe executor selection. A host callback test is not Android socket
protection evidence and does not enable Xray in the production APK.

The proven client fixtures have no Xray-managed DNS/routing or socket-option
strategy. Upstream `internet.InitSystemDialer` still stores DNS/outbound managers
in process globals, outside this socket dispatcher. Isolating those managers (or
constraining the generated client config) is a separate gate before accepting
arbitrary full Xray JSON in simultaneous runtime/probe instances. Do not infer
that arbitrary upstream resolver/routing paths inherit this adapter automatically.
