# Xray Composite Core Migration Plan

## Problem Statement
The current monolithic `sing-box` core is facing compatibility limitations. Some specific configurations (like `FinalMask` and `XHTTP extra`) natively supported by `Xray-core` are missing or incompletely implemented in `sing-box`. Applying individual patches is not a scalable approach for the future.

## Approach
This Proof of Concept (PoC) explores embedding `Xray-core` alongside `sing-box` within a single Go composite AAR library, running in-process via an isolated loopback SOCKS bridge interface (`xraybridge`). This approach ensures compatibility while reusing `sing-box`'s established mechanisms (Android TUN, DNS, and Routing).

## Proof of Concept
The PoC introduces a new package `libbox/xraybridge`:
1.  **Bridge Interface (`bridge.go`)**: Manages the lifecycle of an embedded `Xray-core` instance using JSON configurations decoded into `Xray-core`'s native structs.
2.  **Validation (`bridge_test.go`)**: Contains functional tests ensuring that the embedded core can correctly spin up a local proxy, route traffic (TCP/UDP), handle edge cases, and stop cleanly.
3.  **Module Isolation**: Created as a separate module to bypass strict dependency conflicts (like `gvisor` compatibility) in `sing-box`. Later we can merge both into one single build execution by mapping `replace` directives properly across the workspace or building them together as a composite AAR via `gomobile`.

## JNI / Android Contract
For integration with the UI (and specifically avoiding overlaps with ProfileManager UI layers handled by Codex), Android will initiate the loopback interface on demand using JNI methods exposed via a future extension to `adapter` and `box.go`. The underlying process is shielded using Android socket protection inherited from the sing-box instance.

## Performance Profile
The added overhead primarily involves an additional TCP hop via loopback (127.0.0.1) and a slight bump in RSS (memory) due to instantiating dual networking stacks for specifically delegated nodes. `sing-box` will still act as the primary endpoint.

## Exact Baseline
Baseline SHA: `d2735f4d1fcc4f754086b8c4acb54eddf38c4a11`
