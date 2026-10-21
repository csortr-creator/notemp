# Experimental composite Android facade

This package binds the real sing-box experimental/libbox API alongside Xray's
serialized facade. The facade's StartBridge remains an explicit host experiment.
Protected JNI networking, Android execution and production executor selection
are not connected by this build gate.

Dispatch Build SFAxtnd with isolated_aar_only=true and publish_release=false.
The isolated job prepares pinned/patched sources, runs the combined/native race
proof three times, and installs the production SagerNet mobile tools v0.1.13.
It calls the actual sing-box Android builder for the arm64 reference, then derives
the candidate's full 23-tag feature set and linker settings from actual bind argv.
The recorder delegates unchanged. Go redacts linker metadata under trimpath;
the report distinguishes recorded invocation flags from actual ELF metadata.
Both use API 24 and libbox.so. No manual feature-list copy is required.

The gate compares every Libbox Java class, public JVM descriptor and constant,
checks actual arm64 ELF/JNI and both cores, then assembles the real otherDebug
application against the candidate. Verification-only -PverifyCompositeJni=true
selects arm64 and preserves native bytes, allowing exact APK/AAR JNI comparison
and rejecting a conflicting old runtime. Ordinary build settings stay the same.
Artifacts include the AAR, debug test APK, public API, native build contract,
recorded reference argv, Go build metadata and prepared Xray source provenance.
This debug test APK is not a production update or device/traffic test.

CI498/run39430917424 passed on code 1cb1b1c, with 211 reference Libbox classes and
an independently checked APK/native payload. Later owner-context and gRPC lifecycle
checks passed in CI511/run39542101838 (PR43). See the run's source provenance and
build-contract artifacts for exact digests, and docs/xray-android-network-adapter.md
for the JNI/protect/bind/DNS boundary. Preserve the race detector and TLS verification.
