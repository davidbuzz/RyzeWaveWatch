---
name: phone-testing-approval
description: Buzz explicitly permits installing built APKs on the USB-connected Pixel, launching/foregrounding the app, and taking screenshots to judge results
metadata:
  type: feedback
---

Standing approval (2026-09-04): pushing a newly built .apk to the phone over USB (`adb install -r`), commanding the app to come to the front (`am start` / `am force-stop`), and taking screenshots (`adb exec-out screencap -p`) to check whether the app looks and behaves acceptably are all explicitly permitted, without asking each time. Earlier approvals also cover toggling the phone's Bluetooth over adb and force-stopping the vendor app.

Also (2026-09-04, Buzz stepping away): proceed autonomously, and if help is needed, **make the watch vibrate** to get his attention (`tools/bridge.py find`, i.e. `AB 00 00 00 01 02 07 01`). Use it sparingly — only when genuinely blocked.

**Why:** the app iteration loop (build → install → launch → screenshot → fix) is the way Buzz wants the app developed and verified on the real phone and watch.
**How to apply:** run that loop freely in background tasks; still avoid anything destructive on the phone (uninstalling other apps, clearing their data, factory resets) without asking. Related: [[no-edits-outside-project]], [[run-slow-tasks-in-background]].

- END-OF-WORK STANDING INSTRUCTION (2026-09-05): the very last action of any work session must be to build the latest debug APK and install it on BOTH phones — the Moto g05 (MOTO_SERIAL) and the Pixel 9a (PIXEL_SERIAL) — with permissions granted on each (tools/app_smoke.sh --no-build per serial, or adb install -r + pm grant). Do this after all commits/merges are done. Requested so Buzz always has the newest build on both devices.
