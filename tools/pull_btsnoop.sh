#!/usr/bin/env bash
# Pull the Bluetooth HCI snoop log off an Android phone via adb.
#
# One-time phone setup (Pixel / stock Android):
#   1. Settings > About phone > tap "Build number" 7x to enable Developer options
#   2. Settings > System > Developer options > "Enable Bluetooth HCI snoop log" -> Enabled
#   3. Toggle Bluetooth OFF then ON (the setting only takes effect after a BT stack restart)
#   4. Developer options > "USB debugging" -> on, plug in USB, accept the RSA prompt
#   5. Use the Ryze Fit app normally (pair, sync, change a setting, start a workout...)
#   6. Run this script. Output: captures/btsnoop_<timestamp>.log (open in Wireshark)
set -euo pipefail
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/captures"
mkdir -p "$OUT_DIR"
TS=$(date +%Y%m%d_%H%M%S)
adb wait-for-device
echo "[*] Generating bugreport (takes ~1-2 min) ..."
adb bugreport "$OUT_DIR/bugreport_$TS.zip"
echo "[*] Extracting btsnoop logs ..."
unzip -o -j "$OUT_DIR/bugreport_$TS.zip" 'FS/data/misc/bluetooth/logs/btsnoop_hci.log*' -d "$OUT_DIR/tmp_$TS" 2>/dev/null || {
  echo "[!] No btsnoop_hci.log inside bugreport. Is HCI snoop logging enabled and was BT toggled after enabling?"; exit 1; }
for f in "$OUT_DIR/tmp_$TS"/btsnoop_hci.log*; do
  base=$(basename "$f")
  mv "$f" "$OUT_DIR/${base%.log}_$TS.log"
  echo "    -> $OUT_DIR/${base%.log}_$TS.log"
done
rmdir "$OUT_DIR/tmp_$TS"
rm -f "$OUT_DIR/bugreport_$TS.zip"
echo "[*] Done. Decode with: tools/decode_btsnoop.py <file>   or open in Wireshark with the gloryfit.lua dissector."
