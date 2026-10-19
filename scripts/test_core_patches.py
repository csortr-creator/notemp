#!/usr/bin/env python3
"""Offline regression checks for patch preflight, resumability and local edits."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("apply_core_patches", Path(__file__).with_name("apply_core_patches.py"))
patcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patcher)


def git(core, *args):
    return patcher.git(core, *args)


def apply(core, patches, head):
    with contextlib.redirect_stdout(io.StringIO()):
        patcher.apply_series(core, patches, head)


class CorePatchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.core = self.root / "core"
        self.core.mkdir()
        git(self.core, "init", "-q")
        (self.core / "file.txt").write_text("base\n")
        git(self.core, "add", ".")
        git(self.core, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base")
        self.head = git(self.core, "rev-parse", "HEAD").decode().strip()
        self.patches = []
        for index, (before, after) in enumerate((("base", "first"), ("first", "last"))):
            path = self.root / f"patch-{index}.patch"
            path.write_text(f"--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-{before}\n+{after}\n")
            self.patches.append(path)

    def test_clean_apply_and_rerun_overlapping_hunks(self):
        apply(self.core, self.patches, self.head)
        apply(self.core, self.patches, self.head)
        self.assertEqual("last\n", (self.core / "file.txt").read_text())

    def test_resume_applied_prefix_and_preserve_unrelated_files(self):
        git(self.core, "apply", str(self.patches[0]))
        unrelated = self.core / "unrelated.txt"
        unrelated.write_text("keep\n")
        apply(self.core, self.patches, self.head)
        self.assertEqual("last\n", (self.core / "file.txt").read_text())
        self.assertEqual("keep\n", unrelated.read_text())

    def test_broken_late_patch_does_not_apply_earlier_patch(self):
        self.patches[1].write_text(self.patches[1].read_text().replace("-first", "-wrong"))
        with self.assertRaises(subprocess.CalledProcessError):
            apply(self.core, self.patches, self.head)
        self.assertEqual("base\n", (self.core / "file.txt").read_text())

    def test_local_edit_is_rejected_without_reset(self):
        (self.core / "file.txt").write_text("manual edit\n")
        with self.assertRaisesRegex(ValueError, "differ from every valid"):
            apply(self.core, self.patches, self.head)
        self.assertEqual("manual edit\n", (self.core / "file.txt").read_text())

    def test_wrong_revision_is_rejected_without_mutation(self):
        with self.assertRaisesRegex(ValueError, "pinned revision"):
            apply(self.core, self.patches, "0" * 40)
        self.assertEqual("base\n", (self.core / "file.txt").read_text())

    def test_symlink_is_rejected_without_touching_target(self):
        target = self.root / "private.txt"
        target.write_text("private\n")
        (self.core / "file.txt").unlink()
        (self.core / "file.txt").symlink_to(target)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            apply(self.core, self.patches, self.head)
        self.assertEqual("private\n", target.read_text())

    def test_series_rejects_duplicate_missing_or_escaping_entries(self):
        series = self.root / "series"
        for content in ("", "same.patch\nsame.patch\n", "../outside.patch\n", "sing-box-1.14.2-missing.patch\n"):
            with self.subTest(content=content):
                series.write_text(content)
                with self.assertRaises(ValueError):
                    patcher.read_series(self.root)

    @unittest.skipUnless(os.environ.get("SFA_PATCH_TEST_CORE"), "Set SFA_PATCH_TEST_CORE to the pinned offline core")
    def test_real_series_resumes_every_prefix_and_reruns(self):
        patches = patcher.read_series(patcher.ROOT / "patches")
        expected = None
        for prefix in range(len(patches) + 1):
            with self.subTest(prefix=prefix):
                core = self.root / f"real-{prefix}"
                subprocess.run(["git", "clone", "-q", "--shared", "--no-checkout", os.environ["SFA_PATCH_TEST_CORE"], str(core)], check=True)
                git(core, "checkout", "-q", patcher.PINNED_HEAD)
                for patch in patches[:prefix]:
                    git(core, "apply", str(patch.resolve()))
                (core / "unrelated-fixture.txt").write_text("preserve me\n")
                apply(core, patches, patcher.PINNED_HEAD)
                result = patcher.snapshot(core, patcher.patch_paths(patches))
                if expected is None:
                    expected = result
                self.assertEqual(expected, result)
                apply(core, patches, patcher.PINNED_HEAD)
                self.assertEqual(result, patcher.snapshot(core, patcher.patch_paths(patches)))
                self.assertEqual("preserve me\n", (core / "unrelated-fixture.txt").read_text())


if __name__ == "__main__":
    unittest.main()
