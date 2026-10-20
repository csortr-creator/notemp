#!/usr/bin/env python3
"""Verify the experimental AAR's real ABI and generated API, without a device."""
from io import BytesIO
from pathlib import Path
import struct
import sys
from zipfile import ZipFile


def verify(aar, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with ZipFile(aar) as archive:
        library = archive.read("jni/arm64-v8a/libgojni.so")
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
    (destination / "libgojni.so").write_bytes(library)
    (destination / "classes.jar").write_bytes(classes)
    print("Composite AAR: arm64 JNI and generated Java API present; device execution is unverified")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_composite_aar.py AAR OUTPUT_DIRECTORY")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
