# Reproducible core patches

`patches/series` is the single ordered list used by `patch.sh`, CI and
`scripts/validate.sh --core`. The core remains pinned to sing-box v1.14.2,
commit `af6e64c3b69e6132ebaee0e1a3d24e93903f6709`.

`python3 scripts/apply_core_patches.py CORE_DIRECTORY` first reconstructs the
complete series on a temporary tree from the pinned HEAD. It compares all
patch-owned files with each exact prefix of that series, then applies only the
remaining patches. This supports clean builds, completed reruns and interrupted
builds between patches, including overlapping hunks. It requires Python 3 and Git.

A broken late patch fails during preview, before the working core is modified.
Manual changes to patch-owned files fail with a reviewable error; the tool never
resets them. Unrelated fixture files are preserved. This does not promise recovery
from arbitrary corruption during a single Git file write or concurrent editing.

The old build-time insertion of `detectModeFromConfig` into `HTTPClient.kt` was
removed: it was unused after parsing moved into `SubscriptionContentParser` and
accumulated on repeated runs. Import/routing code now stays exactly as committed.
The pre-existing `box.go` panic-recovery transformation remains unchanged.

Run the offline checks after changing a patch or the patching tools:

```sh
SFA_PATCH_TEST_CORE=/absolute/path/to/pinned/core python3 scripts/test_core_patches.py -v
```

The real-series test covers all 25 prefixes of the current 24-patch stack, reruns
each completed result, and compares the final bytes. Additional synthetic tests
cover broken late patches, local edits, wrong HEAD, symlinks and invalid manifests.
CI runs these checks before the production patch step and native wire tests.

The JVM suite also compiles the production `ServiceBinder` against test-only
LiveData/AIDL/RemoteCallbackList doubles. Its five lifecycle tests cover queued
status/alert cancellation, late callbacks, failed recipients, reentrant close and
100 service instances without accumulated observers. These are JVM regressions;
physical Android/JNI lifecycle checks remain separate.
