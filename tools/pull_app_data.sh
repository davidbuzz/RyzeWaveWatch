#!/bin/bash
# Pull the app's local data off a phone (debug build only: uses run-as):
#   tools/pull_app_data.sh <adb serial> <capture name>
# Writes captures/<name>/{ryzewave.db, gpx/, logcat.txt, summary.txt}. The Room DB is copied together with its
# -wal/-shm files, then checkpointed on the laptop so the .db alone is complete.
set -u
SERIAL=$1; NAME=$2; PKG=au.buzz.ryzewave
OUT=captures/$NAME; mkdir -p "$OUT/gpx"
A="adb -s $SERIAL"
echo "model: $($A shell getprop ro.product.model) android $($A shell getprop ro.build.version.release)" | tee "$OUT/summary.txt"
for f in ryzewave.db ryzewave.db-wal ryzewave.db-shm; do
  $A exec-out run-as $PKG cat databases/$f > "$OUT/$f" 2>/dev/null
  [ -s "$OUT/$f" ] || rm -f "$OUT/$f"
done
ls -l "$OUT" | grep ryzewave | awk '{print "db:", $5, $9}' | tee -a "$OUT/summary.txt"
if [ -f "$OUT/ryzewave.db" ]; then
  sqlite3 "$OUT/ryzewave.db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1; rm -f "$OUT/ryzewave.db-wal" "$OUT/ryzewave.db-shm"
  for t in workout track_point steps_hour hr_sample spo2_sample sleep_stage; do
    echo "rows $t: $(sqlite3 "$OUT/ryzewave.db" "select count(*) from $t")" | tee -a "$OUT/summary.txt"
  done
fi
$A pull "/sdcard/Android/data/$PKG/files/gpx" "$OUT/" >/dev/null 2>&1 && ls "$OUT/gpx" | tee -a "$OUT/summary.txt"
$A logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null
echo "logcat lines: $(wc -l < "$OUT/logcat.txt"); app lines: $(grep -cE 'WatchGatt|WorkoutService|WorkoutController|GraphFactory|HealthConnect' "$OUT/logcat.txt")" | tee -a "$OUT/summary.txt"
$A shell dumpsys batterystats --charged $PKG 2>/dev/null | head -60 > "$OUT/batterystats.txt"
echo "done -> $OUT"
