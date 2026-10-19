# Reality compatibility regression

Pinned sing-box v1.14.2 strips the fingerprint's X25519MLKEM768 share;
Xray 26.9.9 rejects the resulting ClientHello. The core patch only removes
that filtering. Authentication, certificate verification and subscription
parameters retain their existing behavior.

The separate version-policy patch advertises the Reality compatibility version
26.9.9. A real provider rejected the older 26.3.27 value, while changing only
the version to 26.9.9 restored HTTPS with both fingerprints and test origins.
The minimum-version regression explicitly covers both server floors, valid
reconnections, and rejection of wrong keys and short IDs. XHTTP covers both
floors, padding 112–1344, and auto/stream-up with Chrome/Firefox.

The fixture runs official, checksum-pinned Xray 26.3.27 and 26.9.9 locally.
Valid connections must complete HTTPS through VLESS and VLESS/Vision;
wrong public keys and short IDs must fail. The origin certificate is
explicitly trusted only for the nested test HTTPS request.

Xray starts listening before its asynchronous camouflage probe completes.
The connection deadline permits its five-second probe interval, with no
retry of the connection/assertions. Loopback allow rules apply only to the
synthetic Xray fixture, never to application routing.

Auth/plain runs under -race. Vision runs separately because the existing
sing-vmess Vision implementation uses unsafe field-offset reflection that
triggers checkptr under -race. No global checkptr suppression is used.

Baseline regression: current Xray rejects valid Reality before the patch;
after the patch both versions complete HTTPS (three repetitions each).
The Android/provider behavior still requires device verification.
