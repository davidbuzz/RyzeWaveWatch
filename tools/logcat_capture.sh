#!/usr/bin/env bash
# Continuously record the phone's logcat (all tags, threadtime format) to captures/.
# The Ryze Fit SDK prints every BLE packet as  LogSync: APK--->BLE4 = <hex>  /  BLE--->APK4 = <hex>
# (4 = 33F1/33F2 command channel, 5 = 34F1/34F2 data channel).
# Usage: tools/logcat_capture.sh            (Ctrl-C to stop)
#        then: .venv/bin/python tools/parse_logcat.py captures/logcat_live_<ts>.txt
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/captures"
mkdir -p "$DIR"
OUT="$DIR/logcat_live_$(date +%Y%m%d_%H%M%S).txt"
adb wait-for-device
echo "[*] recording to $OUT  (Ctrl-C to stop)"
exec adb logcat -v threadtime > "$OUT"
