# Unified import architecture — 19 October 2026

The product goal is end-to-end compatibility, including execution. Repeating
parser exceptions for individual providers cannot implement missing wire features.
The work is split into shared application infrastructure and a verified execution
backend. The second part is in progress, not production support.

## Shared application path

`NewProfileViewModel` and `ProfileImportHandler` now publish new profiles through
`ProfileManager.createImported` → `ImportCreationCommit`.

1. Fetch/decode through the shared parser, with an 8 MiB allocation bound for
   local files, content providers and encoded profile payloads as well as HTTP.
2. Detect native sing-box objects/arrays in the same parser used by HTTP imports;
   local files must not bypass conversion just because they are valid JSON.
3. Validate the complete config using the actual native core.
4. Reserve a unique file, write and fsync it, then save its report.
5. Insert the row and allocate user order in a Room transaction. Do not use
   `MAX(id)+1` as a file reservation during concurrent network requests.
6. On ordinary failure before publication, remove the new config/report. The
   previously stored profiles are never overwritten. Do not cancel halfway
   through database publication and subsequently delete files owned by the row.
7. Selection, scheduler and report-notification failures after creation must not
   report that the durable profile failed to be created.

This is not an atomic transaction spanning SQLite and the filesystem. A process
crash before row insertion can leave an unreferenced prepared file. Cross-process
crash recovery and a filesystem sweep require a separately verified policy.

Creation failures distinguish native validation, config write, report write,
database insertion, subscription fetch/decode and no usable nodes. Cancellation
propagates rather than becoming a creation error. Report presentation uses the
same safe formatter for initial imports and refresh previews. A closed vocabulary
of capability reasons exposes a feature identifier without copying provider
exception values. Unknown reasons remain generic.

The 457-test JVM suite includes 15 new regressions over the 442-test baseline:
parallel unique files, staged failure cleanup, existing-file preservation,
cancellation before/during publication, write failure, native file detection,
size/cancellation bounds, capability details and secret-free report rendering.
These tests exercise production pure components; Room, Compose and JNI/device
behavior still require Android/CI checks.

## Execution backend decision

The owner now explicitly authorizes any suitable optimal architecture. Previous
single-core decisions remain historical context rather than a hard constraint on
this investigation. Existing production still uses pinned sing-box1.14.2 +24
patches. No second production engine is introduced by this application change.

| Candidate | Decision evidence required |
| --- | --- |
| Current native equivalents/backports | Exact preservation and positive/negative wire fixtures; no growing promise of arbitrary Xray extensions |
| Ready sing-box-lx fork | Pin revision, compare actual fields and Android API, run existing regression matrix; a README claim is insufficient |
| Official Xray executor behind sing-box routing/TUN | Native build and Go dependency compatibility; Android socket protection; authenticated loopback; DNS/UDP/node selection; shared probe and cancellation |

Jules session: https://jules.google.com/session/9381464609788511619
The task compares these candidates and must supply an isolated reproducible proof
and a reviewable PR. Any bridge must preserve user routing/DNS, never silently
fall back to direct, and pass wrong-key/auth/mask rejection as well as positive
traffic tests. Two independently loaded Go AARs must not be assumed compatible.

Primary implementation references inspected during the investigation:
- https://github.com/XTLS/libXray
- https://github.com/Leadaxe/sing-box-lx
- https://xtls.github.io/en/config/inbounds/tun.html

The two supplied screenshots show a generic creation failure and 13 unsupported
records, but no source configurations. Their exact required features and app
version are unknown. Do not claim those records are fixed until an original or
structurally equivalent sanitized subscription is tested through the complete
pipeline and a capable runtime.
