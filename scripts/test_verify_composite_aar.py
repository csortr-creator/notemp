"""Regression checks for the Java API gate, using real javac/javap output."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_composite_aar import LIBBOX_ANCHORS, verify_libbox_api


class LibboxAPIGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.classes = self.root / "classes"
        self.classes.mkdir()
        self.sources = self.root / "src"
        self.sources.mkdir()
        for name in LIBBOX_ANCHORS:
            members = "public native int stable(String value); public static final int FLAG = 7;" if name == "Libbox" else ""
            (self.sources / (name + ".java")).write_text(
                "package io.nekohasekai.libbox; public class " + name + " {" + members + "}"
            )
        self.compile()
        self.reference = self.root / "reference.aar"
        with ZipFile(self.reference, "w") as aar:
            aar.writestr("classes.jar", self.jar())
        self.output = self.root / "output"
        self.output.mkdir()

    def compile(self):
        subprocess.run(["javac", "-d", str(self.classes), *map(str, self.sources.glob("*.java"))],
                       capture_output=True, text=True, check=True)

    def jar(self):
        jar = self.root / "classes.jar"
        with ZipFile(jar, "w") as archive:
            for path in self.classes.rglob("*.class"):
                archive.write(path, path.relative_to(self.classes))
        return jar.read_bytes()

    def verify(self):
        data = self.jar()
        (self.output / "classes.jar").write_bytes(data)
        verify_libbox_api(data, self.reference, self.output)

    def test_matching_surface(self):
        self.verify()
        self.assertIn("(Ljava/lang/String;)I", (self.output / "libbox-public-api.txt").read_text())

    def test_missing_class(self):
        (self.classes / "io/nekohasekai/libbox/TunOptions.class").unlink()
        with self.assertRaisesRegex(ValueError, "Missing legacy Libbox classes"):
            self.verify()

    def test_changed_return_descriptor(self):
        source = self.sources / "Libbox.java"
        source.write_text(source.read_text().replace("int stable", "long stable"))
        self.compile()
        with self.assertRaisesRegex(ValueError, "public API differs"):
            self.verify()

    def test_changed_inlined_constant(self):
        source = self.sources / "Libbox.java"
        source.write_text(source.read_text().replace("FLAG = 7", "FLAG = 8"))
        self.compile()
        with self.assertRaisesRegex(ValueError, "public API differs"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
