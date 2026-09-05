#!/usr/bin/env bash
# Install the latest debug build of "Buzz's Ryze Wave" on the USB phone, launch it, wait, grab logs + a screenshot.
# Usage: tools/app_smoke.sh [--no-build] [wait_seconds]
# Output: captures/app_smoke_<ts>/{build.log,logcat.txt,screen.png,summary.txt}
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=au.buzz.ryzewave
WAIT=40
BUILD=1
for a in "$@"; do case "$a" in --no-build) BUILD=0;; [0-9]*) WAIT=$a;; esac; done
OUT="$ROOT/captures/app_smoke_$(date +%Y%m%d_%H%M%S)"; mkdir -p "$OUT"

if [ $BUILD = 1 ]; then
  (cd "$ROOT/ryzeapp" && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --console=plain > "$OUT/build.log" 2>&1)
  if ! grep -q "BUILD SUCCESSFUL" "$OUT/build.log"; then
    echo "BUILD FAILED — see $OUT/build.log"; grep -nE "^e: |error:|What went wrong" "$OUT/build.log" | head -20; exit 1
  fi
fi
APK="$ROOT/ryzeapp/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "no APK at $APK"; exit 1; }

adb shell am force-stop com.yc.ryzefit >/dev/null 2>&1
adb shell am force-stop au.buzz.ryzebridge >/dev/null 2>&1
adb shell am force-stop $PKG >/dev/null 2>&1
adb install -r "$APK" > "$OUT/install.log" 2>&1 || { echo "INSTALL FAILED"; cat "$OUT/install.log"; exit 1; }
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p >/dev/null 2>&1
done
# Health Connect permissions are platform permissions on Android 14+, so pm grant works (verified on the Moto g05 / Android 15)
for p in STEPS HEART_RATE OXYGEN_SATURATION DISTANCE SLEEP EXERCISE; do
  adb shell pm grant $PKG android.permission.health.WRITE_$p >/dev/null 2>&1
  adb shell pm grant $PKG android.permission.health.READ_$p >/dev/null 2>&1
done
# the workout's GPS track goes into Health Connect as an ExerciseRoute on the session (write-only, no READ_ twin needed)
adb shell pm grant $PKG android.permission.health.WRITE_EXERCISE_ROUTE >/dev/null 2>&1
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity > "$OUT/launch.log" 2>&1
sleep "$WAIT"
adb exec-out screencap -p > "$OUT/screen.png"
adb logcat -d -v time > "$OUT/logcat.txt"
{
  echo "package: $PKG   waited: ${WAIT}s"
  echo "pid: $(adb shell pidof $PKG 2>/dev/null || echo 'NOT RUNNING')"
  echo "top activity: $(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -1 | sed 's/^ *//')"
  echo "crashes:"; grep -a -A12 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -a -E "FATAL|Exception|at au.buzz" | head -20
  echo "app log lines:"; grep -a -E "RyzeWave|WatchGatt|WatchService|WatchApi|Workout|HealthConnect" "$OUT/logcat.txt" | grep -a -v "ActivityTaskManager" | tail -40 | cut -c1-160
} > "$OUT/summary.txt"
echo "smoke test done -> $OUT"; cat "$OUT/summary.txt"
