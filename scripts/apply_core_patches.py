#!/usr/bin/env python3
"""Replay the stack on a shadow tree and recognize only exact applied prefixes.

Per-patch reverse checks are insufficient: later patches modify earlier hunks.
A clean replay also rejects a broken late patch before touching the real tree.
Only paths owned by this series are compared; unrelated fixture files stay intact.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PINNED_HEAD = "af6e64c3b69e6132ebaee0e1a3d24e93903f6709"
ROOT = Path(__file__).resolve().parent.parent


def git(directory, *args):
    return subprocess.run(
        ["git", "-C", str(directory), *args],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout


def read_series(directory):
    names = [line.strip() for line in (directory / "series").read_text().splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if not names or len(names) != len(set(names)):
        raise ValueError("Patch series must be nonempty with unique entries")
    if any(not re.fullmatch(r"sing-box-1\.14\.2-[a-z0-9-]+\.patch", n) for n in names):
        raise ValueError("Invalid patch filename in series")
    patches = [directory / name for name in names]
    for patch in patches:
        if not patch.is_file():
            raise ValueError(f"Required patch missing: {patch.name}")
    return patches


def patch_paths(patches):
    paths = set()
    for patch in patches:
        stats = git(ROOT, "apply", "--numstat", str(patch.resolve())).decode().splitlines()
        if not stats:
            raise ValueError(f"No file changes in {patch.name}")
        for line in stats:
            name = line.split("\t", 2)[-1]
            # This maintained series has no renames or unusual path quoting.
            if (not re.fullmatch(r"[A-Za-z0-9_./-]+", name) or Path(name).is_absolute()
                    or any(part in ("..", ".git") for part in Path(name).parts)):
                raise ValueError(f"Unsupported patch path in {patch.name}")
            paths.add(name)
    return sorted(paths)


def snapshot(directory, paths):
    result = {}
    for name in paths:
        path = directory / name
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError(f"Symlink in patch-owned path: {name}")
        result[name] = path.read_bytes() if path.exists() else None
    return result


def apply_series(core, patches, expected_head):
    if git(core, "rev-parse", "HEAD").decode().strip() != expected_head:
        raise ValueError("Core HEAD does not match the pinned revision")
    paths = patch_paths(patches)
    current = snapshot(core, paths)
    with tempfile.TemporaryDirectory(prefix="sfa-patch-preview-") as temporary:
        preview = Path(temporary)
        # Use blobs from HEAD, including paths absent until an additive patch.
        tracked = set(git(core, "ls-tree", "-r", "--name-only", "-z", "HEAD", "--", *paths)
                      .decode().rstrip("\0").split("\0"))
        for name in paths:
            if name in tracked:
                path = preview / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(git(core, "show", f"HEAD:{name}"))
        prefix = 0 if snapshot(preview, paths) == current else None
        for index, patch in enumerate(patches, 1):
            git(preview, "apply", str(patch.resolve()))
            if snapshot(preview, paths) == current:
                prefix = index
        if prefix is None:
            raise ValueError("Core patch-owned files differ from every valid series prefix; "
                             "preserve/review local edits before rebuilding")
        # Detect edits that happened during preflight. No resets or file replacement.
        if snapshot(core, paths) != current:
            raise ValueError("Core changed during patch preflight")
        for patch in patches[prefix:]:
            git(core, "apply", str(patch.resolve()))
        if snapshot(core, paths) != snapshot(preview, paths):
            raise ValueError("Patched core differs from the validated result")
    print(f"Core patches: {len(patches)}/{len(patches)}; previously applied {prefix}")


def main():
    if len(sys.argv) != 2:
        raise ValueError("Usage: apply_core_patches.py CORE_DIRECTORY")
    apply_series(Path(sys.argv[1]).resolve(), read_series(ROOT / "patches"), PINNED_HEAD)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Core patch error: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr.decode(errors="replace"), file=sys.stderr)
        sys.exit(1)
