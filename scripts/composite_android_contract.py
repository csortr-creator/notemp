#!/usr/bin/env python3
"""Build against the actual production builder's contract, without copying tags."""
import json
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile
from xml.etree import ElementTree
from zipfile import ZipFile


def parse_build_info(text):
    settings = {}
    for line in text.splitlines():
        if line.startswith("\tbuild\t"):
            value, = shlex.split(line[len("\tbuild\t"):])
            key, separator, value = value.partition("=")
            if not separator or key in settings:
                raise ValueError("Invalid/duplicate Go build setting: " + key)
            settings[key] = value
    for key, expected in (("GOOS", "android"), ("GOARCH", "arm64"), ("CGO_ENABLED", "1")):
        if settings.get(key) != expected:
            raise ValueError("Unexpected production build setting: " + key)
    for key in ("-tags",):
        if not settings.get(key):
            raise ValueError("Missing production build setting: " + key)
    return settings


def aar_contract(path):
    with ZipFile(path) as archive:
        library = archive.read("jni/arm64-v8a/libbox.so")
        manifest = ElementTree.fromstring(archive.read("AndroidManifest.xml"))
    if library[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", library, 18)[0] != 183:
        raise ValueError("Expected actual AArch64 libbox.so")
    uses_sdk = manifest.find("uses-sdk")
    api = None if uses_sdk is None else uses_sdk.get("{http://schemas.android.com/apk/res/android}minSdkVersion")
    if api != "24":
        raise ValueError("Expected production main-variant Android API 24")
    with tempfile.TemporaryDirectory(prefix="libbox-contract-") as temporary:
        so = Path(temporary) / "libbox.so"
        so.write_bytes(library)
        info = subprocess.check_output(["go", "version", "-m", str(so)], text=True)
    return {"android_api": int(api), "library": "box", "settings": parse_build_info(info)}


def recorded_flags(path):
    matches = []
    for line in Path(path).read_text().splitlines():
        args = json.loads(line)["argv"]
        flags = {}
        iterator = iter(args[1:])
        for value in iterator:
            if not value.startswith("-"):
                continue
            key, equals, argument = value.partition("=")
            if not equals:
                argument = "true" if key in ("-v", "-trimpath") else next(iterator)
            flags[key] = argument
        if Path(flags.get("-o", "")).name == "libbox.aar" and flags.get("-androidapi") == "24":
            matches.append(flags)
    if len(matches) != 1:
        raise ValueError("Expected one actual production main-variant bind invocation")
    flags = matches[0]
    if flags.get("-libname") != "box" or not flags.get("-ldflags") or not flags.get("-tags"):
        raise ValueError("Incomplete production bind invocation")
    return flags


def bind_arguments(contract, output):
    flags = contract["invocation"]
    args = ["gomobile", "bind", "-v", "-o", str(output), "-target", "android/arm64",
            "-javapkg", "io.nekohasekai", "-androidapi", str(contract["android_api"]),
            "-libname", contract["library"], "-tags", flags["-tags"], "-ldflags", flags["-ldflags"]]
    if flags.get("-trimpath") == "true":
        args.append("-trimpath")
    args.extend(["-buildvcs=false", "./native", "github.com/sagernet/sing-box/experimental/libbox"])
    return args


def compare_contracts(reference, candidate):
    if reference["android_api"] != candidate["android_api"] or reference["library"] != candidate["library"]:
        raise ValueError("Android API/JNI library contract changed")
    for key in ("-tags", "-trimpath", "-compiler", "GOOS", "GOARCH", "GOARM64", "CGO_ENABLED"):
        if reference["settings"].get(key) != candidate["settings"].get(key):
            raise ValueError("Production build contract changed: " + key)
    for key in ("-tags", "-ldflags", "-trimpath"):
        if reference["invocation"].get(key) != candidate["invocation"].get(key):
            raise ValueError("Production invocation changed: " + key)


def main():
    if len(sys.argv) != 6 or sys.argv[1] != "build":
        raise SystemExit("Usage: composite_android_contract.py build REFERENCE_AAR CANDIDATE_AAR BIND_JSONL REPORT_JSON")
    _, reference, candidate, recorded, report = sys.argv[1:]
    reference = Path(reference).resolve()
    candidate = Path(candidate).resolve()
    contract = aar_contract(reference)
    contract["invocation"] = recorded_flags(recorded)
    if contract["settings"]["-tags"] != contract["invocation"]["-tags"]:
        raise ValueError("Production ELF tags do not match recorded builder invocation")
    command = bind_arguments(contract, candidate)
    subprocess.run(command, check=True)
    actual = aar_contract(candidate)
    # Go deliberately omits -ldflags from ELF metadata when -trimpath is set.
    # Preserve provenance from actual argv, without inventing a metadata value.
    actual["invocation"] = {k: command[command.index(k) + 1] for k in ("-tags", "-ldflags")}
    if "-trimpath" in command:
        actual["invocation"]["-trimpath"] = "true"
    compare_contracts(contract, actual)
    Path(report).write_text(json.dumps({"reference": contract, "candidate": actual, "candidate_argv": command}, indent=2) + "\n")
    print("Production contract matches: ELF feature tags, recorded linker argv, Android API and JNI library")


if __name__ == "__main__":
    main()
