#!/bin/bash
set -euo pipefail

# Backport the XHTTP transport onto the pinned core; never silently build without it.
CORE_PATCH="$(dirname "$0")/patches/sing-box-1.14.2-xhttp.patch"
COMPAT_PATCH="$(dirname "$0")/patches/sing-box-1.14.2-import-compatibility.patch"
if git -C sing-box-core apply --reverse --check "$PWD/$CORE_PATCH" 2>/dev/null || git -C sing-box-core apply --reverse --check "$PWD/$COMPAT_PATCH" 2>/dev/null; then
    echo "XHTTP core patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$CORE_PATCH"
    git -C sing-box-core apply "$PWD/$CORE_PATCH"
fi

# Interop fixtures must finish workers before test teardown and avoid
# allocating future listener ports from the outgoing ephemeral range.
FIXTURE_PATCH="$(dirname "$0")/patches/sing-box-1.14.2-test-fixtures.patch"
if git -C sing-box-core apply --reverse --check "$PWD/$FIXTURE_PATCH" 2>/dev/null; then
    echo "Interop fixture patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$FIXTURE_PATCH"
    git -C sing-box-core apply "$PWD/$FIXTURE_PATCH"
fi

# URL tests must acknowledge completion, not merely enqueue background work.
PING_PATCH="$(dirname "$0")/patches/sing-box-1.14.2-ping-completion.patch"
if git -C sing-box-core apply --reverse --check "$PWD/$PING_PATCH" 2>/dev/null; then
    echo "Ping completion patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$PING_PATCH"
    git -C sing-box-core apply "$PWD/$PING_PATCH"
fi

# Explicit cold/warm HTTP probing on the same reusable transport.
MODE_PATCH="$(dirname "$0")/patches/sing-box-1.14.2-probe-mode.patch"
if git -C sing-box-core apply --reverse --check "$PWD/$MODE_PATCH" 2>/dev/null; then
    echo "Probe mode patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$MODE_PATCH"
    git -C sing-box-core apply "$PWD/$MODE_PATCH"
fi

# Preserve Xray certificate verification and bound concurrent XHTTP uploads.
if git -C sing-box-core apply --reverse --check "$PWD/$COMPAT_PATCH" 2>/dev/null; then
    echo "Import compatibility patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$COMPAT_PATCH"
    git -C sing-box-core apply "$PWD/$COMPAT_PATCH"
fi

VLESS_ENCRYPTION_PATCH=patches/sing-box-1.14.2-vless-encryption.patch
if git -C sing-box-core apply --reverse --check "$PWD/$VLESS_ENCRYPTION_PATCH" 2>/dev/null; then
    echo "VLESS encryption patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$VLESS_ENCRYPTION_PATCH"
    git -C sing-box-core apply "$PWD/$VLESS_ENCRYPTION_PATCH"
fi

CLIENT_OPTIONS_PATCH=patches/sing-box-1.14.2-client-options.patch
if git -C sing-box-core apply --reverse --check "$PWD/$CLIENT_OPTIONS_PATCH" 2>/dev/null; then
    echo "Client options patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$CLIENT_OPTIONS_PATCH"
    git -C sing-box-core apply "$PWD/$CLIENT_OPTIONS_PATCH"
fi

RULE_SET_FALLBACK_PATCH=patches/sing-box-1.14.2-rule-set-fallback.patch
if git -C sing-box-core apply --reverse --check "$PWD/$RULE_SET_FALLBACK_PATCH" 2>/dev/null; then
    echo "Rule-set fallback patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$RULE_SET_FALLBACK_PATCH"
    git -C sing-box-core apply "$PWD/$RULE_SET_FALLBACK_PATCH"
fi

REALITY_SPIDER_PATCH=patches/sing-box-1.14.2-reality-spider-x.patch
if git -C sing-box-core apply --reverse --check "$PWD/$REALITY_SPIDER_PATCH" 2>/dev/null; then
    echo "Reality spider_x patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$REALITY_SPIDER_PATCH"
    git -C sing-box-core apply "$PWD/$REALITY_SPIDER_PATCH"
fi

# Preserve the fingerprint's hybrid share for current Xray Reality servers.
REALITY_MLKEM_PATCH=patches/sing-box-1.14.2-reality-mlkem.patch
if git -C sing-box-core apply --reverse --check "$PWD/$REALITY_MLKEM_PATCH" 2>/dev/null; then
    echo "Reality ML-KEM compatibility patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$REALITY_MLKEM_PATCH"
    git -C sing-box-core apply "$PWD/$REALITY_MLKEM_PATCH"
fi


# Literal FinalMask header-custom for plain VLESS TCP.
FINALMASK_TCP_PATCH=patches/sing-box-1.14.2-finalmask-tcp-header.patch
if git -C sing-box-core apply --reverse --check "$PWD/$FINALMASK_TCP_PATCH" 2>/dev/null; then
    echo "FinalMask TCP header patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$FINALMASK_TCP_PATCH"
    git -C sing-box-core apply "$PWD/$FINALMASK_TCP_PATCH"
fi

# Reuse the reviewed Vision/Encryption adapter and pinned browser presets.
SUBSCRIPTION_COMPAT_PATCH=patches/sing-box-1.14.2-subscription-compatibility.patch
if git -C sing-box-core apply --reverse --check "$PWD/$SUBSCRIPTION_COMPAT_PATCH" 2>/dev/null; then
    echo "Subscription runtime compatibility patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$SUBSCRIPTION_COMPAT_PATCH"
    git -C sing-box-core apply "$PWD/$SUBSCRIPTION_COMPAT_PATCH"
fi

# Explicit Reality hybrid option used by Mihomo-compatible panel subscriptions.
REALITY_HYBRID_PATCH=patches/sing-box-1.14.2-reality-hybrid-option.patch
if git -C sing-box-core apply --reverse --check "$PWD/$REALITY_HYBRID_PATCH" 2>/dev/null; then
    echo "Reality hybrid option patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$REALITY_HYBRID_PATCH"
    git -C sing-box-core apply "$PWD/$REALITY_HYBRID_PATCH"
fi

# Retain failed probe results for display without admitting them to URLTest selection.
PROBE_FAILURE_PATCH=patches/sing-box-1.14.2-probe-failure-display.patch
if git -C sing-box-core apply --reverse --check "$PWD/$PROBE_FAILURE_PATCH" 2>/dev/null; then
    echo "Probe failure display patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$PROBE_FAILURE_PATCH"
    git -C sing-box-core apply "$PWD/$PROBE_FAILURE_PATCH"
fi

# REALITY compatibility version, with version-floor and XHTTP regression coverage.
REALITY_VERSION_PATCH=patches/sing-box-1.14.2-reality-client-version.patch
if git -C sing-box-core apply --reverse --check "$PWD/$REALITY_VERSION_PATCH" 2>/dev/null; then
    echo "Reality client version patch already applied"
else
    git -C sing-box-core apply --check "$PWD/$REALITY_VERSION_PATCH"
    git -C sing-box-core apply "$PWD/$REALITY_VERSION_PATCH"
fi

# Получаем строку, где кончается класс HTTPClient
LINE=$(grep -n "override fun close()" app/src/main/java/io/nekohasekai/sfa/utils/HTTPClient.kt | cut -d: -f1)

# Вставляем новую функцию перед close()
sed -i "${LINE}i\\
\\
private fun detectModeFromConfig(root: JSONObject): SubscriptionRouting.Mode {\\
    val outbounds = root.optJSONArray(\"outbounds\") ?: return SubscriptionRouting.Mode.NORMAL\\
    for (i in 0 until outbounds.length()) {\\
        val tag = outbounds.optJSONObject(i)?.optString(\"tag\") ?: continue\\
        if (SubscriptionRouting.isWhitelistBypassTag(tag)) {\\
            return SubscriptionRouting.Mode.WHITELIST_BYPASS\\
        }\\
    }\\
    return SubscriptionRouting.Mode.NORMAL\\
}" app/src/main/java/io/nekohasekai/sfa/utils/HTTPClient.kt

# Меняем buildSingBoxConfig на версию с SubscriptionRouting
sed -i 's/return root.toString(2)/SubscriptionRouting.apply(root, detectModeFromConfig(root))\n        return root.toString(2)/' app/src/main/java/io/nekohasekai/sfa/utils/HTTPClient.kt

echo "✅ Патч применён"

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
