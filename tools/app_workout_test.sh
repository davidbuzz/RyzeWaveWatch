#!/usr/bin/env bash
# Drive a short workout in "Buzz's Ryze Wave" on the USB phone: Workout tab -> Start -> wait -> Stop, capturing the
# watch traffic (FD 11 / FD 01 <hr> / FD 44 / FD 00) from logcat and screenshots before/during/after.
# Usage: tools/app_workout_test.sh [seconds]   (default 45)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECS=${1:-45}
OUT="$ROOT/captures/app_workout_$(date +%Y%m%d_%H%M%S)"; mkdir -p "$OUT"
TAP="$ROOT/tools/app_tap.sh"
adb shell am start -n au.buzz.ryzewave/.MainActivity >/dev/null 2>&1; sleep 2
adb logcat -c
"$TAP" "Workout" >/dev/null; sleep 1.5
adb exec-out screencap -p > "$OUT/1_before.png"
"$TAP" "Start workout" || { echo "no Start workout button"; "$TAP" --list | head -30; exit 1; }
sleep 8; adb exec-out screencap -p > "$OUT/2_started.png"
sleep $((SECS - 8 > 0 ? SECS - 8 : 1)); adb exec-out screencap -p > "$OUT/3_running.png"
"$TAP" "Stop" >/dev/null 2>&1 || "$TAP" "Finish" >/dev/null 2>&1 || "$TAP" "End" >/dev/null 2>&1
sleep 3; adb exec-out screencap -p > "$OUT/4_stopped.png"
adb logcat -d -v time > "$OUT/logcat.txt"
{
  echo "workout test, ${SECS}s"
  echo "crashes: $(grep -ac 'FATAL EXCEPTION' "$OUT/logcat.txt")"
  echo "--- watch traffic (FD / E5) as logged by the app:"
  grep -aE "WatchGatt|WatchApi|Workout" "$OUT/logcat.txt" | grep -aiE "fd11|fd44|fd00|fd01|fd22|fd33|sport|workout|hr=|location|gps|fix" | head -60 | cut -c1-160
  echo "--- UI nodes at the end:"; "$TAP" --list | cut -c1-90 | head -25
} > "$OUT/summary.txt"
echo "done -> $OUT"; cat "$OUT/summary.txt"
