# Experimental composite Android facade

This package imports the real sing-box `experimental/libbox` and the isolated
Xray bridge, exposing string/error lifecycle methods that gomobile can bind.
It is not linked into the production app. StartBridge is an isolated experiment;
Android socket protection, DNS/network ownership and TUN integration are not wired.

The facade serializes calls, publishes state only after a successful start, and
supports idempotent Stop and retry after failure. Host tests verify both engines,
listener cleanup, duplicate starts and concurrent ownership. Generated Java/JNI
bindings alone do not prove device execution or working VPN traffic.

To run the AAR gate, dispatch Build SFAxtnd with `isolated_aar_only=true` and
`publish_release=false`. This selects the isolated AAR job and skips APK/release
jobs. It pins Go through the module, x/mobile tools through an exact version,
NDK28.2.13676358 and Android35, and prepares the same pinned/ patched sing-box as
the host proof. The artifact is `composite-arm64.aar`, separate from production.
The Xray tree is prepared with `scripts/prepare_xray_core.py`: the same official
module checksum plus the reviewed Hysteria close-race patch used by host tests.
Artifacts include source provenance and actual ELF Go build metadata.

The gate checks host behavior, cross-compiles arm64 with QUIC/uTLS, validates the
AAR's actual arm64 ELF and Java classes, and inspects exported JNI symbols. It
uploads the AAR only after those checks pass. This is cross-build/JNI-generation
evidence; Android device, socket protection and VPN runtime tests remain pending.
