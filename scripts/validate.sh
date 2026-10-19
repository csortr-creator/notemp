#!/usr/bin/env bash
# SFAxtnd pre-push validation (aligns with .github/workflows/build.yml where possible).
#
# Exit codes:
#   0 — all executed required checks PASS (no FAIL/SKIP/UNVERIFIED recorded)
#   1 — at least one check FAIL
#   2 — requested mode incomplete (SKIP/UNVERIFIED without FAIL)
#
# --core uses an isolated temp clone of SagerNet/sing-box v1.14.2 and never
# modifies the caller's working tree or any existing sing-box-core directory.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

STATUS_FAIL=0
STATUS_UNVERIFIED=0
declare -a REPORT_LINES=()

usage() {
  cat <<'USAGE'
Usage: scripts/validate.sh --parser|--core|--android|--all [--help]

  --parser   bash ./gradlew -p protocol-tests test --no-daemon --stacktrace
  --core     Isolated clean sing-box v1.14.2 + required patches; optional go tests
  --android  bash ./gradlew assembleOtherDebug if libbox.aar + SDK available
  --all      parser, core, android in sequence

Statuses: PASS | FAIL | SKIP | UNVERIFIED
Exit: 0 = pass, 1 = fail, 2 = incomplete / unverified
USAGE
}

log() { printf '%s\n' "$*"; }

record() {
  local name="$1" status="$2" detail="${3:-}"
  REPORT_LINES+=("$status  $name${detail:+ — $detail}")
  case "$status" in
    FAIL) STATUS_FAIL=1 ;;
    SKIP|UNVERIFIED) STATUS_UNVERIFIED=1 ;;
  esac
  log "[$status] $name${detail:+ — $detail}"
}

resolve_java() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -x "${JAVA_HOME}/bin/javac" ]]; then
      export PATH="${JAVA_HOME}/bin:${PATH}"
      return 0
    fi
    return 1
  fi
  local candidate
  for candidate in \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/temurin-17 \
    /usr/lib/jvm/microsoft-17-jdk
  do
    if [[ -x "${candidate}/bin/javac" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="${JAVA_HOME}/bin:${PATH}"
      return 0
    fi
  done
  if command -v javac >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

need_java() {
  if resolve_java; then
    log "Java: $(java -version 2>&1 | head -1)"
    log "javac: $(javac -version 2>&1)"
    return 0
  fi
  return 1
}

run_gradle() {
  # Do not chmod tracked gradlew; invoke via bash.
  if [[ ! -f "$ROOT/gradlew" ]]; then
    return 127
  fi
  (cd "$ROOT" && bash ./gradlew "$@")
}

run_parser() {
  log "=== --parser (protocol-tests) ==="
  if ! need_java; then
    record "parser" "UNVERIFIED" "JDK with javac not found (CI uses Java 17)"
    return
  fi
  if [[ ! -f "$ROOT/gradlew" ]]; then
    record "parser" "FAIL" "gradlew missing"
    return
  fi
  set +e
  run_gradle -p protocol-tests test --no-daemon --stacktrace
  local rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    record "parser" "PASS" "bash ./gradlew -p protocol-tests test"
  else
    record "parser" "FAIL" "gradle exit $rc"
  fi
}

apply_patches_isolated() {
  python3 "$ROOT/scripts/apply_core_patches.py" "$1"
}

go_error_is_infra_only() {
  local logf="$1"
  if ! grep -qE '502 Bad Gateway|proxy\.golang|reading http://|download go[0-9]|toolchain@|go: downloading go[0-9]' "$logf"; then
    return 1
  fi
  if grep -qE '^--- FAIL:|FAIL\t.*\[build failed\]|undefined: |syntax error:' "$logf"; then
    return 1
  fi
  return 0
}

run_core() {
  log "=== --core (isolated sing-box v1.14.2 + patches) ==="
  if ! command -v git >/dev/null 2>&1; then
    record "core" "UNVERIFIED" "git not found"
    return
  fi
  if [[ ! -d "$ROOT/patches" ]]; then
    record "core" "FAIL" "patches/ directory missing"
    return
  fi

  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/sfaxtnd-core-XXXXXX")"
  cleanup_core() { rm -rf "$tmp"; }
  trap cleanup_core EXIT

  log "Isolated workdir: $tmp (removed on exit; repo tree untouched)"
  set +e
  git clone --depth 1 --branch v1.14.2 https://github.com/SagerNet/sing-box.git "$tmp/sing-box-core"
  local clone_rc=$?
  set -e
  if [[ $clone_rc -ne 0 ]]; then
    record "core" "UNVERIFIED" "clone SagerNet/sing-box v1.14.2 failed"
    trap - EXIT
    cleanup_core
    return
  fi

  set +e
  apply_patches_isolated "$tmp/sing-box-core"
  local patch_rc=$?
  set -e
  if [[ $patch_rc -ne 0 ]]; then
    record "core-patches" "FAIL" "required patch missing or apply failed"
    trap - EXIT
    cleanup_core
    return
  fi
  record "core-patches" "PASS" "all required patches applied on clean v1.14.2"

  if ! command -v go >/dev/null 2>&1; then
    record "core-go-tests" "SKIP" "go not found; patch-only"
    trap - EXIT
    cleanup_core
    return
  fi

  set +e
  local go_log
  go_log="$(mktemp)"
  (
    cd "$tmp/sing-box-core"
    go test -count=1 -timeout 3m -ldflags=-checklinkname=0 \
      -tags=with_quic,with_utls \
      ./option ./common/tls ./transport/v2ray ./transport/vlessenc
  ) >"$go_log" 2>&1
  local go_rc=$?
  set -e
  if [[ $go_rc -eq 0 ]]; then
    record "core-go-tests" "PASS" "go test option/tls/v2ray/vlessenc"
  elif go_error_is_infra_only "$go_log"; then
    tail -8 "$go_log" || true
    record "core-go-tests" "UNVERIFIED" "Go module/toolchain fetch unavailable (see log tail)"
  else
    tail -30 "$go_log" || true
    record "core-go-tests" "FAIL" "go test exit $go_rc (compile/test or ambiguous; not classified as infra)"
  fi
  rm -f "$go_log"

  if [[ -n "${XHTTP_XRAY_BINARY:-}" && -x "${XHTTP_XRAY_BINARY}" ]]; then
    set +e
    (
      cd "$tmp/sing-box-core"
      XHTTP_XRAY_BINARY="$XHTTP_XRAY_BINARY" go test -count=1 -timeout 5m \
        -ldflags=-checklinkname=0 -tags=with_quic,with_utls \
        ./transport/v2rayxhttp
    )
    local x_rc=$?
    set -e
    if [[ $x_rc -eq 0 ]]; then
      record "core-xhttp-interop" "PASS" "go test ./transport/v2rayxhttp with XHTTP_XRAY_BINARY"
    else
      record "core-xhttp-interop" "FAIL" "XHTTP interop go test exit $x_rc"
    fi
  else
    record "core-xhttp-interop" "SKIP" "set XHTTP_XRAY_BINARY to a local verified xray binary to run; no auto-download"
  fi

  trap - EXIT
  cleanup_core
}

run_android() {
  log "=== --android (CI: assembleOtherDebug after libbox.aar) ==="
  if ! need_java; then
    record "android" "UNVERIFIED" "JDK with javac not found"
    return
  fi
  if [[ ! -f "$ROOT/gradlew" ]]; then
    record "android" "FAIL" "gradlew missing"
    return
  fi
  if [[ ! -f "$ROOT/app/libs/libbox.aar" ]]; then
    record "android" "UNVERIFIED" "app/libs/libbox.aar missing"
    return
  fi
  if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
    if [[ ! -f "$ROOT/local.properties" ]] && [[ ! -d "${HOME}/Android/Sdk" ]]; then
      record "android" "UNVERIFIED" "Android SDK not detected"
      return
    fi
  fi

  set +e
  run_gradle assembleOtherDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx4096m -XX:+UseG1GC" --stacktrace
  local rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    record "android" "PASS" "bash ./gradlew assembleOtherDebug"
  else
    record "android" "FAIL" "gradle exit $rc"
  fi
}

print_report() {
  log ""
  log "======== validate.sh report ========"
  local line
  if [[ ${#REPORT_LINES[@]} -eq 0 ]]; then
    log "(no checks recorded)"
  else
    for line in "${REPORT_LINES[@]}"; do
      log "$line"
    done
  fi
  log "==================================="
  if [[ $STATUS_FAIL -ne 0 ]]; then
    log "Result: FAIL"
    return 1
  fi
  if [[ $STATUS_UNVERIFIED -ne 0 ]]; then
    log "Result: UNVERIFIED (not a full pass)"
    return 2
  fi
  log "Result: all executed checks PASS"
  return 0
}

main() {
  if [[ $# -eq 0 ]]; then
    usage
    exit 2
  fi
  case "${1:-}" in
    --parser) run_parser ;;
    --core) run_core ;;
    --android) run_android ;;
    --all)
      run_parser
      run_core
      run_android
      ;;
    --help|-h) usage; exit 0 ;;
    *) log "Unknown option: $1"; usage; exit 2 ;;
  esac
  print_report
  exit $?
}

main "$@"
