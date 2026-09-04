#!/usr/bin/env bash
# Force the laptop's Bluetooth adapter into LE-only mode (or back to dual) by setting
# ControllerMode in /etc/bluetooth/main.conf and restarting bluetoothd.
#
# Why: the Ryze Wave is a dual-mode device with a public address. BlueZ 5.72 prefers the
# classic (BR/EDR) bearer for such devices, which lands on the watch's audio side and
# exposes no GATT. With the adapter in LE-only mode BlueZ has to connect over BLE.
# Side effect: classic devices (headsets, phone audio) cannot connect to the laptop while "le".
#
# Usage: sudo tools/le_only.sh on|off|status
set -euo pipefail
CONF=/etc/bluetooth/main.conf
case "${1:-status}" in
  on)
    sudo sed -i -E 's/^\s*#?\s*ControllerMode\s*=.*/ControllerMode = le/' "$CONF"
    grep -q "^ControllerMode = le" "$CONF" || sudo sed -i 's/^\[General\]/[General]\nControllerMode = le/' "$CONF"
    # Discovery scans from other clients (e.g. GNOME Settings' Bluetooth panel) default to a 100 % duty cycle
    # (11.25 ms interval / 11.25 ms window) and starve an active LE connection until it hits supervision timeout.
    # Drop discovery scanning to ~19 % duty while we are in LE-only mode.
    grep -q "^ScanIntervalDiscovery" "$CONF" || sudo sed -i 's/^\[LE\]/[LE]\nScanIntervalDiscovery = 0x0060\nScanWindowDiscovery = 0x0012/' "$CONF"
    sudo systemctl restart bluetooth
    ;;
  off)
    sudo sed -i -E 's/^\s*ControllerMode\s*=.*/#ControllerMode = dual/' "$CONF"
    sudo sed -i -E '/^Scan(Interval|Window)Discovery = 0x00(60|12)$/d' "$CONF"
    sudo systemctl restart bluetooth
    ;;
esac
sleep 1
echo "main.conf: $(grep -E '^\s*#?\s*ControllerMode' "$CONF" | head -1)"
bluetoothctl show | grep -E "Powered|Discovering" | tr -d '\t' | paste -sd' '
