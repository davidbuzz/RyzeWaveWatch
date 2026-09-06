#!/usr/bin/env bash
# Use a phone's camera as a test rig: take a photo of whatever it is pointed at (the watch face on a stand),
# pull it, strip all metadata (the phone stamps GPS into every shot), and delete it from the phone.
#   tools/watch_cam.sh <serial> <out.jpg>          one shot (opens the camera, shoots, goes home)
#   tools/watch_cam.sh <serial> <dir/> <n> <secs>   n shots, one every <secs> seconds, named 001.jpg 002.jpg ...
#   tools/watch_cam.sh open <serial>                 leave the camera open (for a sequence driven by another script)
#   tools/watch_cam.sh shot <serial> <out.jpg>       one shot with the camera already open (~2 s)
#   tools/watch_cam.sh close <serial>                back to the home screen
# Needs: Google Camera (Pixel). Shutter is the volume-down key; the first shot after launch takes ~3 s.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CAM=/sdcard/DCIM/Camera
open_cam() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb shell am start -n com.google.android.GoogleCamera/com.android.camera.CameraLauncher >/dev/null 2>&1 || \
    adb shell am start -a android.media.action.STILL_IMAGE_CAMERA >/dev/null 2>&1
  sleep 5   # viewfinder + autofocus settle
}
MODE=""
case "${1:-}" in open|shot|close) MODE="$1"; shift;; esac
SERIAL="$1"; OUT="${2:-}"; N="${3:-1}"; EVERY="${4:-5}"
export ANDROID_SERIAL="$SERIAL"
case "$MODE" in
  open)  open_cam; exit 0;;
  close) adb shell input keyevent KEYCODE_HOME >/dev/null; exit 0;;
esac
[ "$MODE" = shot ] || open_cam
shot() {   # $1 = destination file
  local before after new
  before=$(adb shell "ls $CAM 2>/dev/null | wc -l" | tr -d '\r')
  adb shell input keyevent KEYCODE_VOLUME_DOWN
  for _ in 1 2 3 4 5 6 7 8; do sleep 0.5; after=$(adb shell "ls $CAM 2>/dev/null | wc -l" | tr -d '\r'); [ "$after" -gt "$before" ] && break; done
  [ "$after" -gt "$before" ] || { echo "no photo appeared" >&2; return 1; }
  sleep 1   # let the file finish writing
  new=$(adb shell "ls -t $CAM | head -1" | tr -d '\r')
  adb pull "$CAM/$new" "$1.raw" >/dev/null 2>&1 && adb shell rm -f "$CAM/$new"
  "$ROOT/.venv/bin/python" - "$1" <<'EOF'
import sys
from PIL import Image
raw = sys.argv[1] + ".raw"
im = Image.open(raw).convert("RGB")
clean = Image.new("RGB", im.size); clean.paste(im)     # fresh image: no EXIF, no GPS, no camera model
clean.save(sys.argv[1], "JPEG", quality=90, optimize=True)
EOF
  rm -f "$1.raw"
  echo "$1"
}
if [ "$N" -le 1 ]; then shot "$OUT"; else
  mkdir -p "$OUT"; for i in $(seq 1 "$N"); do shot "$OUT/$(printf '%03d' "$i").jpg"; [ "$i" -lt "$N" ] && sleep "$EVERY"; done
fi
[ "$MODE" = shot ] || adb shell input keyevent KEYCODE_HOME >/dev/null
