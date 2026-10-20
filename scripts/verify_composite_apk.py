#!/usr/bin/env python3
"""Prove that Gradle packaged the candidate native library into the test APK."""
from pathlib import Path
import hashlib
import sys
from zipfile import ZipFile


def verify(aar, directory):
    with ZipFile(aar) as archive:
        expected = archive.read("jni/arm64-v8a/libbox.so")
    apks = list(directory.glob("*.apk"))
    if not apks:
        raise ValueError("No candidate application APK was built")
    for path in apks:
        with ZipFile(path) as archive:
            libraries = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
            target = "lib/arm64-v8a/libbox.so"
            if libraries.count(target) != 1 or any(n.endswith("/libgojni.so") for n in libraries):
                raise ValueError("Missing/duplicate/conflicting composite JNI in " + path.name)
            if archive.read(target) != expected:
                raise ValueError("Packaged JNI differs from tested candidate: " + path.name)
            if not any(n.startswith("classes") and n.endswith(".dex") for n in archive.namelist()):
                raise ValueError("Application DEX is missing")
        print(path.name + ": candidate JNI SHA-256 " + hashlib.sha256(expected).hexdigest())


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_composite_apk.py CANDIDATE_AAR APK_DIRECTORY")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
