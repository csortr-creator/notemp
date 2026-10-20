import copy
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from composite_android_contract import bind_arguments, compare_contracts, parse_build_info
from verify_composite_apk import verify


class BuildContractTest(unittest.TestCase):
    def setUp(self):
        self.settings = parse_build_info('''library: go1.27.2
	build	-tags=with_quic,with_utls,with_gvisor
	build	-ldflags="-s -w -X runtime.godebugDefault=multipathtcp=0,tlssha1=1"
	build	-trimpath=true
	build	GOOS=android
	build	GOARCH=arm64
	build	CGO_ENABLED=1
''')
        self.contract = {"android_api": 24, "library": "box", "settings": self.settings}

    def test_builder_flags_are_preserved_as_single_arguments(self):
        args = bind_arguments(self.contract, "candidate.aar")
        self.assertEqual(args[args.index("-ldflags") + 1], self.settings["-ldflags"])
        self.assertEqual(args[args.index("-tags") + 1], self.settings["-tags"])
        self.assertIn("-trimpath", args)
        compare_contracts(self.contract, copy.deepcopy(self.contract))

    def test_missing_feature_fails(self):
        candidate = copy.deepcopy(self.contract)
        candidate["settings"]["-tags"] = "with_quic,with_utls"
        with self.assertRaisesRegex(ValueError, "-tags"):
            compare_contracts(self.contract, candidate)

    def test_changed_linker_semantics_fail(self):
        candidate = copy.deepcopy(self.contract)
        candidate["settings"]["-ldflags"] = "-s -w"
        with self.assertRaisesRegex(ValueError, "-ldflags"):
            compare_contracts(self.contract, candidate)

    def test_unidentified_build_fails(self):
        with self.assertRaisesRegex(ValueError, "GOOS"):
            parse_build_info("library: go1.27.2\n")


class PackagedCandidateTest(unittest.TestCase):
    def test_actual_candidate_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aar = root / "candidate.aar"
            with ZipFile(aar, "w") as archive:
                archive.writestr("jni/arm64-v8a/libbox.so", b"candidate-native-bytes")
            directory = root / "apk"
            directory.mkdir()
            for name, payload, conflict, success in (
                ("correct", b"candidate-native-bytes", False, True),
                ("stale", b"old-library", False, False),
                ("conflict", b"candidate-native-bytes", True, False),
            ):
                with self.subTest(name=name):
                    apk = directory / "candidate.apk"
                    with ZipFile(apk, "w") as archive:
                        archive.writestr("classes.dex", b"fixture-dex")
                        archive.writestr("lib/arm64-v8a/libbox.so", payload)
                        if conflict:
                            archive.writestr("lib/arm64-v8a/libgojni.so", b"conflicting-runtime")
                    if success:
                        verify(aar, directory)
                    else:
                        with self.assertRaises(ValueError):
                            verify(aar, directory)


if __name__ == "__main__":
    unittest.main()
