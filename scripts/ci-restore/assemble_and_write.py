#!/usr/bin/env python3
"""Assemble base64 parts into .github/workflows/build.yml."""
from pathlib import Path
import base64
import sys
root = Path(__file__).resolve().parent
parts = sorted(root.glob("build.yml.part*.b64"))
if not parts:
    print("no parts", file=sys.stderr)
    sys.exit(1)
data = "".join(p.read_text().strip() for p in parts)
raw = base64.b64decode(data)
out = root.parents[1] / ".github/workflows/build.yml"
out.write_bytes(raw)
print(f"wrote {out} ({len(raw)} bytes)")
text = raw.decode()
assert text.startswith("name: Build SFAxtnd"), text[:40]
assert "SFAxtnd-verification-arm64-debug.apk" in text
print("ok")
