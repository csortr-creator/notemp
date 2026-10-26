#!/usr/bin/env python3
"""Prepare the checksum-pinned Xray source plus reviewed local patches.

The Go module cache stays immutable. Existing output is reused only when its
recorded pin, patch and full source digests match; unknown output is preserved.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
MODULE = "github.com/xtls/xray-core"
VERSION = "v1.260327.1-0.20260908222543-52a412d9e2f5"
STAMP = ".notemp-source.json"


def source_hashes(root):
    return {
        p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(root.rglob("*"))
        if p.is_file() and ".git" not in p.relative_to(root).parts and p.name != STAMP
    }


def prepare():
    module_dir = ROOT / "libbox/xraybridge"
    if not re.search(r"^\s*" + re.escape(MODULE + " " + VERSION) + r"\s*$", (module_dir / "go.mod").read_text(), re.M):
        raise RuntimeError("Xray module version differs from the reviewed source pin")
    checksum = next(line.split()[2] for line in (module_dir / "go.sum").read_text().splitlines()
                    if line.startswith(MODULE + " " + VERSION + " "))
    patches = [ROOT / "patches" / name for name in (
        "xray-hysteria-session-close.patch", "xray-instance-dialer-features.patch",
        "xray-owned-system-dns.patch", "xray-grpc-owned-resources.patch",
        "xray-xmux-owned-mux.patch", "xray-xmux-owned-dialer-a.patch",
        "xray-xmux-owned-dialer-b.patch", "xray-xmux-owned-client.patch",
        "xray-xmux-owned-tests.patch")]
    identity = {"module": MODULE, "version": VERSION, "sum": checksum,
                "patches": [{"path": p.relative_to(ROOT).as_posix(),
                             "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in patches]}
    output = ROOT / "xray-core"
    if output.exists() or output.is_symlink():
        if output.is_symlink() or not (output / STAMP).is_file():
            raise RuntimeError("Existing xray-core is not an owned prepared tree; preserved")
        metadata = json.loads((output / STAMP).read_text())
        if metadata.get("identity") != identity or metadata.get("sources") != source_hashes(output):
            raise RuntimeError("Existing prepared Xray source differs; preserved for review")
        print("Verified existing prepared Xray source:", VERSION)
        return
    env = os.environ.copy()
    env["GOWORK"] = "off"
    with tempfile.TemporaryDirectory(prefix="notemp-xray-download-") as download_dir:
        downloaded = json.loads(subprocess.check_output(
            ["go", "mod", "download", "-json", MODULE + "@" + VERSION], cwd=download_dir, env=env, text=True))
    if downloaded.get("Sum") != checksum or downloaded.get("Version") != VERSION:
        raise RuntimeError("Downloaded Xray does not match the committed Go checksum")
    with tempfile.TemporaryDirectory(prefix=".xray-core-prepare-", dir=ROOT) as staging:
        tree = Path(staging) / "source"
        shutil.copytree(downloaded["Dir"], tree, copy_function=shutil.copyfile)
        for directory in [tree, *(p for p in tree.rglob("*") if p.is_dir())]:
            directory.chmod(directory.stat().st_mode | stat.S_IWUSR)
        subprocess.run(["git", "init", "-q", str(tree)], check=True)
        for patch in patches:
            subprocess.run(["git", "apply", "--check", str(patch)], cwd=tree, check=True)
            subprocess.run(["git", "apply", str(patch)], cwd=tree, check=True)
        (tree / STAMP).write_text(json.dumps({"identity": identity, "sources": source_hashes(tree)}, sort_keys=True))
        tree.rename(output)
    print("Prepared checksum-pinned Xray source plus reviewed race/isolation patches:", VERSION)


if __name__ == "__main__":
    prepare()
