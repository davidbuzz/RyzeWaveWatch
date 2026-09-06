#!/bin/bash
# Fast incremental build + install of the debug app, no unit tests:
#   tools/fastbuild.sh [serial]              -> :app:installDebug on that phone (default: $ANDROID_SERIAL or the only device)
#   tools/fastbuild.sh --test <TestClass>    -> run ONE unit-test class only (e.g. --test PacketParseTest)
#   tools/fastbuild.sh --apk                 -> just assembleDebug (no install)
# Prints Kotlin errors (e: lines) and FAILS LOUDLY (non-zero exit, no "installed" line) when Gradle fails.
# The Gradle daemon stays warm (gradle.properties: 3 h idle timeout), so after the first run a one-file edit rebuilds
# in seconds. Run the full suite (`cd ryzeapp && ./gradlew :app:testDebugUnitTest`) before committing, not per edit.
set -o pipefail
cd "$(dirname "$0")/../ryzeapp" || exit 2
T0=$(date +%s.%N)
LOG=$(mktemp)
run() { ./gradlew "$@" --console=plain -q > "$LOG" 2>&1; RES=$?; grep -E "^e:|error:|FAILED|What went wrong|Execution failed|> Task .* FAILED|tests completed" "$LOG" | grep -vE "^w:" | head -20; return $RES; }
case "${1:-}" in
  --test)  run :app:testDebugUnitTest --tests "*${2}*"; RES=$? ;;
  --apk)   run :app:assembleDebug; RES=$? ;;
  *)       SERIAL="${1:-${ANDROID_SERIAL:-}}"; [ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"
           run :app:installDebug; RES=$?
           if [ $RES -eq 0 ]; then echo "installed on ${SERIAL:-default device} ($(adb ${SERIAL:+-s $SERIAL} shell getprop ro.product.model | tr -d '\r'))"; else echo "NOT installed: build failed (exit $RES)"; fi ;;
esac
rm -f "$LOG"
printf '%s in %.1f s\n' "$([ $RES -eq 0 ] && echo OK || echo FAILED)" "$(echo "$(date +%s.%N) - $T0" | bc)"
exit $RES
