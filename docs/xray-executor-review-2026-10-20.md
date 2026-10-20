# Xray executor review — 20 October 2026

Production remains on sing-box. The isolated Xray module must pass these gates
before its integration into the Android runtime; the existing module's startup
tests do not establish wire compatibility or Android protection.

## Findings in the Jules wire/protection draft

Reviewed activity snapshot from session
https://jules.google.com/session/15780583428840000315, before PR publication.
These findings describe that draft, not merged production code.

1. `UseAndroidSocketProtection` replaces a process-global dialer without session
   ownership. A late reset can replace another session's active dialer. Installation
   and restoration require an explicit owner and concurrency tests.
2. TCP hostname resolution and UDP `ResolveUDPAddr` use the system resolver outside
   the protector. The UDP path opens a socket before resolving and leaks it on a
   resolution error. Protection must cover DNS as well as data sockets, close all
   failed partial operations, preserve required socket options, and bind the current
   underlying network. A successful localhost TCP test proves none of these paths.
3. The wire draft puts `finalmask` at the root of the JSON. Validate the actual
   built `streamSettings` mask configuration rather than trusting a decoder which
   can ignore unknown fields. Use the pinned source schema in
   `infra/conf/transport_internet.go`.
4. The negative mask test compares a 14-byte slice with the 13-byte text
   `GET / HTTP/1.1`; the equality cannot hold. It can also panic on short reads.
   A wrong-mask test must fail if the exact echo payload crosses the connection,
   and distinguish setup failure from a mask rejection. The positive control must
   use the same path. Add a missing-mask control and a genuine UDP exchange before
   claiming those capabilities.
5. Editing a module dependency does not establish a composite build. An executable
   harness must import and instantiate both pinned implementations in the same
   dependency graph. Record its command and limitations separately from AAR/JNI
   device evidence.

Detailed corrections were sent to Jules. No production integration or release is
approved by a passing draft test or a completed Jules session.

## Verified production lifecycle regression coverage

`protocol-tests/xhttp/sfa_pending_close_test.go` now additionally exercises
cancellation before `GotConn` and concurrent response arrival with repeated Close.
CI repeats all four response-lifetime tests ten times with the race detector.
The existing pending-close patch remains unchanged.

Local evidence: all four tests passed ten repetitions under `-race`; the resource
cleanup test passed in the same repeated run. Removing the pending-close patch
reproduced a blocked Read, a surviving RoundTrip, and an accepted late response.
The wider wire suite could not complete in this container because creating its
netlink sockets returns `operation not permitted`; it is not recorded as passed.
