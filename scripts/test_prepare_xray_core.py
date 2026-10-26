"""Exercise source ownership and the ordered patch chain without downloading Go."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepared_xray", Path(__file__).with_name("prepare_xray_core.py"))
prepared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepared)


class PrepareXrayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "repo"
        self.cache = Path(self.directory.name) / "cache"
        self.cache.mkdir()
        (self.cache / "fixture.go").write_text("package old\n")
        module = self.root / "libbox/xraybridge"
        module.mkdir(parents=True)
        (module / "go.mod").write_text(f"require (\n {prepared.MODULE} {prepared.VERSION}\n)\n")
        (module / "go.sum").write_text(f"{prepared.MODULE} {prepared.VERSION} h1:fixture\n")
        patches = self.root / "patches"
        patches.mkdir()
        for name, before, after in (
            ("xray-hysteria-session-close.patch", "old", "middle"),
            ("xray-instance-dialer-features.patch", "middle", "owned"),
            ("xray-owned-system-dns.patch", "owned", "resolved"),
            ("xray-grpc-owned-resources.patch", "resolved", "grpc"),
            ("xray-xmux-owned-mux.patch", "grpc", "mux"),
            ("xray-xmux-owned-dialer-a.patch", "mux", "dialera"),
            ("xray-xmux-owned-dialer-b.patch", "dialera", "dialerb"),
            ("xray-xmux-owned-client.patch", "dialerb", "client"),
            ("xray-xmux-owned-tests.patch", "client", "hysteria"),
            ("xray-hysteria-owned-client-a.patch", "hysteria", "hysteriaa"),
            ("xray-hysteria-owned-client-b.patch", "hysteriaa", "final"),
        ):
            (patches / name).write_text(
                f"diff --git a/fixture.go b/fixture.go\n--- a/fixture.go\n+++ b/fixture.go\n"
                f"@@ -1 +1 @@\n-package {before}\n+package {after}\n"
            )
        self.root_patch = patch.object(prepared, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.download = patch.object(prepared.subprocess, "check_output", return_value=json.dumps({
            "Dir": str(self.cache), "Sum": "h1:fixture", "Version": prepared.VERSION,
        }).encode()).start()
        self.addCleanup(patch.stopall)

    def test_ordered_patches_and_cache_immutability(self):
        prepared.prepare()
        tree = self.root / "xray-core"
        self.assertEqual("package final\n", (tree / "fixture.go").read_text())
        self.assertEqual("package old\n", (self.cache / "fixture.go").read_text())
        identity = json.loads((tree / prepared.STAMP).read_text())["identity"]
        self.assertEqual(11, len(identity["patches"]))
        self.assertTrue(all(len(p["sha256"]) == 64 for p in identity["patches"]))
        prepared.prepare()
        self.download.assert_called_once()

    def test_modified_source_is_preserved_and_rejected(self):
        prepared.prepare()
        source = self.root / "xray-core/fixture.go"
        source.write_text("package modified\n")
        with self.assertRaisesRegex(RuntimeError, "preserved"):
            prepared.prepare()
        self.assertEqual("package modified\n", source.read_text())

    def test_changed_patch_requires_explicit_repreparation(self):
        prepared.prepare()
        patch_file = self.root / "patches/xray-instance-dialer-features.patch"
        patch_file.write_text(patch_file.read_text() + "\n")
        with self.assertRaisesRegex(RuntimeError, "preserved"):
            prepared.prepare()
        self.assertEqual("package final\n", (self.root / "xray-core/fixture.go").read_text())

    def test_unowned_output_is_not_replaced(self):
        tree = self.root / "xray-core"
        tree.symlink_to(self.cache, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, "preserved"):
            prepared.prepare()
        self.download.assert_not_called()
        self.assertTrue(tree.is_symlink())


if __name__ == "__main__":
    unittest.main()
