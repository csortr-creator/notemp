#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Validate the entire overlapping stack before changing the core. Safe to rerun
# after a completed or interrupted patch step; never overwrite manual core edits.
python3 "$ROOT/scripts/apply_core_patches.py" "$ROOT/sing-box-core"

# Патчим box.go для удаления panic recovery (// TODO: remove catch error)
BOX_GO=""
if [ -f "libbox/box.go" ]; then
    BOX_GO="libbox/box.go"
elif [ -f "sing-box-core/box.go" ]; then
    BOX_GO="sing-box-core/box.go"
fi

if [ -n "$BOX_GO" ]; then
    awk '/\/\/ TODO: remove catch error/ {
        in_defer = 1
        next
    }
    in_defer {
        if ($0 ~ /\}\(\)/) {
            in_defer = 0
        }
        next
    }
    { print }' "$BOX_GO" > "${BOX_GO}.tmp" && mv "${BOX_GO}.tmp" "$BOX_GO"
    echo "✅ Патч $BOX_GO применён"
else
    echo "⚠️ Файл box.go не найден для патчинга!"
fi
