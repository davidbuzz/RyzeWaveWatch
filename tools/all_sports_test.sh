#!/usr/bin/env bash
# End-to-end, on the USB phone, for EVERY sport the watch offers (or a subset): pick the sport in the app's own
# picker, start a workout, let it run, stop it, then read the stored row back over adb and check the sport that
# was saved is the one that was chosen. Drives the real UI (uiautomator taps), so it works on the release build.
#
#   tools/all_sports_test.sh [serial] [seconds-per-workout] [sport name ...]
#   tools/all_sports_test.sh ZY... 20                       every sport, 20 s each (~40 min)
#   tools/all_sports_test.sh ZY... 30 "Rower" "Swimming"    just those
#
# Output: captures/all_sports_<ts>/{results.tsv,log.txt,<sport>.png}. Exit 1 if any sport failed.
# With the watch linked, the app asks it (FD AA) which sport it opened; a disagreement is a failure too.
# Needs: the app installed and permitted, location ON (the app refuses to start otherwise), the phone unlocked.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-${ANDROID_SERIAL:-}}"; shift || true
SECS="${1:-20}"; shift || true
export ANDROID_SERIAL="$SERIAL"
PKG=au.buzz.ryzewave
TAP="$ROOT/tools/app_tap.sh"
OUT="$ROOT/captures/all_sports_$(date +%Y%m%d_%H%M%S)"; mkdir -p "$OUT"
LOG="$OUT/log.txt"; RES="$OUT/results.tsv"
S="${TMPDIR:-/tmp}/all_sports.$$"; mkdir -p "$S"
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }

# ---- the sport list, straight from the Kotlin so it cannot drift
mapfile -t ALL < <(grep -oE '0x[0-9A-Fa-f]{2} to "[^"]+"' "$ROOT/ryzeapp/app/src/main/java/au/buzz/ryzewave/protocol/SportTypes.kt" | sed -E 's/0x([0-9A-Fa-f]{2}) to "(.*)"/\1\t\2/')
declare -A ID_OF; for line in "${ALL[@]}"; do ID_OF["${line#*	}"]=$((16#${line%%	*})); done
if [ $# -gt 0 ]; then SPORTS=("$@"); else SPORTS=(); for line in "${ALL[@]}"; do SPORTS+=("${line#*	}"); done; fi
say "phone $SERIAL, ${#SPORTS[@]} sports, ${SECS}s each -> $OUT"

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$S/ui.xml" 2>/dev/null; }
# centre of the first node whose text equals $1 (exact), or empty
find_exact() { python3 - "$1" "$S/ui.xml" <<'EOF'
import re, sys
want, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding='utf-8', errors='replace').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1) == want:
        x1,y1,x2,y2 = map(int, b.groups()); print(f"{(x1+x2)//2} {(y1+y2)//2}"); break
EOF
}
# bounds of the "All sports" dialog list: from the Close button up to the title
dialog_box() { python3 - "$S/ui.xml" <<'EOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
ys = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1) in ("All sports", "Close"):
        x1,y1,x2,y2 = map(int, b.groups()); ys.append((t.group(1), y1, y2, x1, x2))
d = {k: v for k, *v in ys}
if "All sports" in d and "Close" in d:
    top = d["All sports"][1] + 10; bottom = d["Close"][0] - 10; x = (d["All sports"][2] + d["All sports"][3]) // 2
    print(f"{x} {top} {bottom}")
EOF
}
tap_xy() { adb shell input tap "$1" "$2"; sleep 0.8; }
# scroll the dialog until $1 is visible and tap it; returns 1 if never found
pick_in_dialog() {
  local name="$1" tries=0 box x top bottom xy
  while [ $tries -lt 16 ]; do
    dump; xy=$(find_exact "$name")
    if [ -n "$xy" ]; then tap_xy $xy; return 0; fi
    box=$(dialog_box); [ -z "$box" ] && return 1
    read -r x top bottom <<<"$box"
    # drag up inside the list. A quick 300 ms drag scrolls the Compose list; a slow 600 ms one was ignored.
    adb shell input swipe "$x" "$((bottom - 80))" "$x" "$((top + 80))" 300; sleep 0.7
    tries=$((tries + 1))
  done
  return 1
}
tap_text() { dump; local xy; xy=$(find_exact "$1"); [ -n "$xy" ] && tap_xy $xy; }

row_for_start() {   # newest workout row (id, sportType, duration, distance) via run-as (release builds are debuggable)
  adb exec-out run-as $PKG cat databases/ryzewave.db > "$S/db" 2>/dev/null
  adb exec-out run-as $PKG cat databases/ryzewave.db-wal > "$S/db-wal" 2>/dev/null; [ -s "$S/db-wal" ] || rm -f "$S/db-wal"
  adb exec-out run-as $PKG cat databases/ryzewave.db-shm > "$S/db-shm" 2>/dev/null; [ -s "$S/db-shm" ] || rm -f "$S/db-shm"
  sqlite3 "$S/db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1
  sqlite3 "$S/db" "select id, sportType, durationSeconds, cast(distanceMeters as int), ifnull(steps,''), (select count(*) from track_point where workoutId=workout.id) from workout order by start desc limit 1;" 2>/dev/null
}

printf 'sport\tid\tsaved_sport\twatch_says\tduration_s\tdistance_m\tsteps\tpoints\tresult\n' > "$RES"
fail=0
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
for name in "${SPORTS[@]}"; do
  id="${ID_OF[$name]:-}"
  if [ -z "$id" ]; then say "SKIP unknown sport '$name'"; continue; fi
  before=$(row_for_start | cut -d'|' -f1)
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  tap_text "Workout"; sleep 1
  # the picker: a chip row with the popular sports and a "More…" chip that opens the "All sports" dialog.
  # The tab can take a moment to render after a stop, so try twice before giving up.
  if ! tap_text "More…"; then sleep 1.5; tap_text "Workout"; sleep 1; tap_text "More…" || { say "$name: no 'More…' chip on the Workout tab"; dump; }; fi
  if ! pick_in_dialog "$name"; then say "FAIL $name: not found in the picker"; printf '%s\t%d\t\t\t\t\t\t\tNOT_IN_PICKER\n' "$name" "$id" >> "$RES"; fail=1; tap_text "Close"; continue; fi
  tap_text "Close"; sleep 0.5
  if ! tap_text "Start workout"; then say "FAIL $name: no Start button (location off?)"; printf '%s\t%d\t\t\t\t\t\t\tNO_START\n' "$name" "$id" >> "$RES"; fail=1; adb exec-out screencap -p > "$OUT/$(echo "$name" | tr ' /' '__').png"; continue; fi
  adb logcat -c 2>/dev/null
  sleep "$SECS"
  adb exec-out screencap -p > "$OUT/$(echo "$name" | tr ' /' '__').png"
  # what the WATCH says it is doing (FD AA after start, logged by the app); n/a when no watch is linked
  watch=$(adb logcat -d 2>/dev/null | grep -oE 'watch confirms sport [0-9]+ open|watch reports state=[0-9]+ type=[0-9]+|watch did not answer the sport query' | tail -1)
  case "$watch" in
    "watch confirms sport $id open") wsays="$id";;
    "watch reports"*) wsays="$(echo "$watch" | grep -oE 'type=[0-9]+' | cut -d= -f2)";;
    "") wsays="n/a";;
    *) wsays="?";;
  esac
  tap_text "Stop"; sleep 3
  row=$(row_for_start); IFS='|' read -r rid rsport rdur rdist rsteps rpts <<<"$row"
  if [ "$rid" = "$before" ]; then say "FAIL $name: no new workout row"; printf '%s\t%d\t\t\t\t\t\t\tNO_ROW\n' "$name" "$id" >> "$RES"; fail=1; continue; fi
  if [ "$rsport" != "$id" ]; then r="WRONG_SPORT"; fail=1
  elif [ "$wsays" != "n/a" ] && [ "$wsays" != "$id" ]; then r="WATCH_DISAGREES"; fail=1
  else r=OK; fi
  say "$r $name (id $id): saved sport=$rsport watch=$wsays dur=${rdur}s dist=${rdist}m steps=$rsteps points=$rpts"
  printf '%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$id" "$rsport" "$wsays" "$rdur" "$rdist" "$rsteps" "$rpts" "$r" >> "$RES"
done
say "done: $(grep -c $'\tOK$' "$RES") OK, $(grep -vc -E $'\tOK$|^sport' "$RES") failed -> $RES"
rm -rf "$S"
exit $fail
