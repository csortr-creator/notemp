#!/usr/bin/env python3
"""Verify the experimental AAR's real ABI and generated API, without a device."""
from io import BytesIO
from pathlib import Path
import struct
import subprocess
import tempfile
import sys
from zipfile import ZipFile


LIBBOX_ANCHORS = ("Libbox", "SetupOptions", "PlatformInterface", "CommandServer", "TunOptions", "LocalDNSTransport")


def public_api(classes_jar, names):
    # Descriptors catch parameter/return changes; constants catch inlined values.
    result = subprocess.run(["javap", "-public", "-s", "-constants", "-classpath", str(classes_jar),
                             *sorted(names)], text=True, capture_output=True, check=True)
    return "\n".join(line for line in result.stdout.splitlines() if not line.startswith("Compiled from ")) + "\n"


def verify_libbox_api(classes, reference_aar, destination):
    with ZipFile(reference_aar) as archive:
        reference_classes = archive.read("classes.jar")
    with ZipFile(BytesIO(reference_classes)) as reference:
        names = {n for n in reference.namelist() if n.startswith("io/nekohasekai/libbox/") and n.endswith(".class")}
    for anchor in LIBBOX_ANCHORS:
        if "io/nekohasekai/libbox/" + anchor + ".class" not in names:
            raise ValueError("Incomplete legacy reference: " + anchor)
    with ZipFile(BytesIO(classes)) as jar:
        missing = names - set(jar.namelist())
    if missing:
        raise ValueError("Missing legacy Libbox classes: " + ", ".join(sorted(missing)))
    java_names = {name[:-6].replace("/", ".") for name in names}
    with tempfile.TemporaryDirectory(prefix="libbox-api-") as temporary:
        baseline = Path(temporary) / "reference.jar"
        baseline.write_bytes(reference_classes)
        expected = public_api(baseline, java_names)
    actual = public_api(destination / "classes.jar", java_names)
    if actual != expected:
        (destination / "libbox-expected-api.txt").write_text(expected)
        (destination / "libbox-actual-api.txt").write_text(actual)
        raise ValueError("Legacy Libbox public API differs from the pinned standalone reference")
    (destination / "libbox-public-api.txt").write_text(actual)
    print(f"Complete legacy Libbox API verified: {len(names)} classes, public descriptors and constants")


def verify(aar, destination, reference_aar):
    destination.mkdir(parents=True, exist_ok=True)
    with ZipFile(aar) as archive:
        library = archive.read("jni/arm64-v8a/libbox.so")
        classes = archive.read("classes.jar")
        archive.read("AndroidManifest.xml")
    if library[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", library, 18)[0] != 183:
        raise ValueError("Expected a 64-bit little-endian AArch64 ELF library")
    with ZipFile(BytesIO(classes)) as jar:
        facade = jar.read("io/nekohasekai/composite/ExecutorFacade.class")
        factory = jar.read("io/nekohasekai/composite/Composite.class")
    for method in (b"checkSingboxConfig", b"startBridge", b"stopBridge"):
        if method not in facade:
            raise ValueError("Missing generated facade method: " + method.decode())
    if b"newExecutorFacade" not in factory:
        raise ValueError("Missing generated facade factory")
    (destination / "libbox.so").write_bytes(library)
    (destination / "classes.jar").write_bytes(classes)
    verify_libbox_api(classes, reference_aar, destination)
    print("Composite AAR: arm64 JNI and generated Java API present; device execution is unverified")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: verify_composite_aar.py AAR OUTPUT_DIRECTORY LIBBOX_REFERENCE_AAR")
    verify(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
