# Xray network ownership contract

`libbox/xraybridge/network_session.go` is a private Go building block. It is not
installed in Xray's process-global system dialer or connected to Android JNI.
It makes network retirement testable before those integrations.

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
normal `net.Dialer` cancellation semantics. UDP wrappers preserve `net.PacketConn`
for future QUIC/mask callers.

Host regression coverage uses real TCP/UDP sockets and blocked DNS/protection
callbacks. It checks exchange after dial-context cancellation, retirement of
blocked reads, successful replacement, negative TCP protection after retirement,
UDP cancellation, concurrent Update/Dial/Close and invalid update rollback. The
standalone and combined sing-box/Xray graphs run these tests with the race detector.

Remaining gates: install one explicitly owned Xray dispatcher safely, attach the
Android protector/network/resolver, verify network replacement on device and wire
this owner into Bridge Stop/reload/probe. A host callback test is not Android socket
protection evidence and does not enable Xray in the production APK.
