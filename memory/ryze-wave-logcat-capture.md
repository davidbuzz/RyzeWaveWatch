---
name: ryze-wave-logcat-capture
description: The Ryze Fit app logs every BLE packet in hex to logcat, so adb logcat is the primary capture method (no HCI snoop needed)
metadata:
  type: project
---

The UTE SDK inside Ryze Fit has `LogSync.DEBUG = true` in the release build, so with the Pixel 9a on USB debugging, `adb logcat` shows every packet: `LogSync: APK--->BLE4 = <HEX>` (phone→watch on 33F1) and `BLE--->APK4 = <HEX>` (watch→phone on 33F2); suffix 5 = the 34F1/34F2 data channel. LogConnect/LogSports/LogHome also print decoded state (feature bitmap words as `isSupportN =…,function =<int>`, GPS fixes, HR).

**Why:** discovered 2026-09-04; it gives a decoded, timestamped trace for free, far easier than HCI snoop + Wireshark.
Since the evening of 2026-09-04 the better capture is our own bridge: `adb logcat -v time -s RyzeBridge:*` shows `TX/RX <char> <hex>` for everything on the link, including the vendor app's traffic when both are connected; `tools/parse_logcat.py` decodes both formats.

**How to apply:** run `tools/logcat_capture.sh` (continuous) and `tools/parse_logcat.py` in the repo. Logcat ring buffer was raised to 32 MB with `adb logcat -G 32M` (resets on reboot). HCI snoop remains optional for GATT reads. See [[ryze-wave-project]].
