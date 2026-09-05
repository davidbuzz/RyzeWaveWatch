#!/bin/bash
# Fast incremental build + install of the debug app, no unit tests:
#   tools/fastbuild.sh [serial]              -> :app:installDebug on that phone (default: $ANDROID_SERIAL or the only device)
#   tools/fastbuild.sh --test <TestClass>    -> run ONE unit-test class only (e.g. --test PacketParseTest)
#   tools/fastbuild.sh --apk                 -> just assembleDebug (no install)
# The Gradle daemon stays warm (gradle.properties: 3 h idle timeout), so after the first run a one-file edit rebuilds in
# seconds. Run the full suite (`cd ryzeapp && ./gradlew :app:testDebugUnitTest`) before committing, not on every edit.
set -e
cd "$(dirname "$0")/../ryzeapp"
T0=$(date +%s.%N)
case "${1:-}" in
  --test)  ./gradlew :app:testDebugUnitTest --tests "*${2}*" --console=plain -q 2>&1 | grep -vE "^w:|warning" | tail -15; RES=$? ;;
  --apk)   ./gradlew :app:assembleDebug --console=plain -q 2>&1 | grep -vE "^w:|warning" | tail -5; RES=$? ;;
  *)       SERIAL="${1:-${ANDROID_SERIAL:-}}"; [ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"
           ./gradlew :app:installDebug --console=plain -q 2>&1 | grep -vE "^w:|warning" | tail -5; RES=$?
           [ -n "$SERIAL" ] && echo "installed on $SERIAL ($(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r'))" ;;
esac
printf 'done in %.1f s\n' "$(echo "$(date +%s.%N) - $T0" | bc)"
exit ${RES:-0}
