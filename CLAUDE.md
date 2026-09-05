# CLAUDE.md — working notes for this repo

## What this is
Reverse-engineering the Ryze Wave smartwatch BLE protocol and building a replacement for the
vendor "Ryze Fit" Android app. Read `README.md` for the current state, `docs/PROTOCOL.md`
for the protocol, and `docs/wave_application_research.md` for the narrative of what mattered and why. Keep both updated as facts are confirmed on real hardware; mark anything
that comes only from decompiled code as *(from SDK, unverified)*.

## Hard rule
**Never edit files or folders outside this repo or `/tmp` without asking first.** That includes `/etc/bluetooth/main.conf`
(`tools/le_only.sh` edits it — Buzz approved this one on 2026-09-04, so it may be run as needed), `~/.config`, `~/tools`, package installs. Keep clones, caches and
state inside the repo.

## Working style
- Pre-approved on the USB phone: `adb install -r` of our builds, launching/foregrounding/force-stopping our app (and the vendor app), and `adb exec-out screencap -p` screenshots to judge results. Not approved: uninstalling other apps, clearing their data, factory resets.
- Anything slow (BLE connect/sync/keepalive tests, btmon or logcat captures, jadx, downloads) runs as a **background task**;
  foreground calls are for quick reads and edits only. Report when the background job finishes.

## Ground truth vs. inference
- **Verified on the watch**: MAC, BT name, classic SDP profiles (from `bluetoothctl info`).
- **From Gadgetbridge / dissector** (tested on other GloryFit watches, not ours): packet formats in `docs/PROTOCOL.md`.
- **From the decompiled Ryze Fit SDK** (`apk/jadx-out/sources/com/yc/pedometer/sdk/`): opcode table, D5 pairing handshake, feature bitmap.
- Nothing has been exchanged with the watch yet. Until a capture exists, treat every packet layout as a hypothesis.

## Where things live
- Decompiled app: `apk/jadx-out/sources/`. Key files:
  `com/yc/pedometer/sdk/WriteCommandToBLE.java` (command builders),
  `com/yc/pedometer/sdk/BluetoothLeService.java` (connect flow + response parser: `datePacketOperate` @ ~L2890, `datePacketOperate34F2` @ ~L3982),
  `com/yc/pedometer/sdk/DataProcessing.java` (payload decoders),
  `com/yc/pedometer/utils/UUIDUtils.java`, `com/yc/pedometer/column/GetFunctionList.java`.
- Reference clones (git-ignored, inside the repo): `tools/Gadgetbridge` (sparse checkout: gloryfit/no1f1/jyou only), `tools/Gadgetbridge-tools` (branch `jr-gb-dissector`). jadx 1.5.6 is in `tools/jadx/bin/jadx` (git-ignored). Nothing project-related lives outside the repo.
- Python: always use `.venv/bin/python` (has `bleak`). Do not install into the system python or the ardupilot venv on PATH.

## Hardware / environment
- Laptop BT adapter `hci0` (Intel, BT 5.2), BlueZ 5.72. `sudo` works without a password.
- Phone: Pixel 9a. Capture via HCI snoop log + `adb bugreport` (`tools/pull_btsnoop.sh`). No hardware sniffer.
- The watch only advertises BLE when the phone is not connected (`adb shell svc bluetooth disable` frees it; `enable` gives it back). When idle it advertises only every ~15 s, so scans need ~60 s.
- BlueZ 5.72 connects to this dual-mode watch over classic BT unless the adapter is LE-only: run `sudo tools/le_only.sh on` first (`off` afterwards, or the user's headset/phone audio cannot connect to the laptop). If a classic bond to the watch was created by accident, `bluetoothctl remove 78:02:B7:37:91:E5`.
- First GATT discovery is slow (~1 s per ATT request); the client uses a 90 s connect timeout and BlueZ caches the result.

## Safety rules for talking to the watch
- Read-only / harmless first: `A1` (version), `A2` (battery), `A3` (time), fetch commands (`xx FA`).
- Never send without explicit user go-ahead: `AD` (factory reset / delete all data), anything on the Realtek OTA/DFU UUIDs (`0000d0ff-…`, `00006287-…`), `26`/`27` (watch-face upload), `D5 03` (set password), `38 xx` (BT3/unpair).
- The watch's classic-BT audio link is independent of BLE; don't try to "fix" HFP/A2DP.

## The app (ryzeapp/)
- Gradle project, spec in `docs/APP.md`. Build: `cd ryzeapp && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --console=plain` (the default `java` is a JRE-only 21) (config ~20 s, deps cached in ~/.gradle from MyPulseApp). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then `pm grant` BLUETOOTH_CONNECT/SCAN, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS and the 12 `android.permission.health.*` permissions (pm grant works for Health Connect on Android 14+); `tools/app_smoke.sh` does all of it.
- Core contracts in `ryzeapp/app/src/main/java/au/buzz/ryzewave/core/`; `App.graph` is the service locator (`GraphFactory`).
- Force-stop Ryze Fit and RyzeBridge before testing the app (`adb shell am force-stop com.yc.ryzefit au.buzz.ryzebridge`); all three share the phone's GATT link.

## Android bridge
- `android/` builds without Gradle: `android/build.sh` (needs `tools/android-sdk`, installed by `android/sdk-install.sh`).
  Java only, minSdk 26, targetSdk 34, package `au.buzz.ryzebridge`. Runtime permissions are granted with `pm grant`.
- Drive it with `tools/bridge.py`; scripts are `;`-separated statements handled in `BleService.runScript`.
  Add new statements there rather than adding protocol logic to the app — the decoding lives in `ryzewave/protocol.py`.
- Buzz has a separate Android project at `~/MyPulseApp` (Gradle 8.14, Kotlin 1.9.22, compileSdk 35) — read-only reference for the toolchain, not part of this repo.

## Conventions
- `grep` on this machine is ugrep: a pattern that starts with `-` (e.g. `-->`) must be given with `-e`.
- Opcodes are written as hex bytes, e.g. `B2 FA`. First byte = command, second = sub-command; `FA` = start fetch, `FD` = end/ack, `AA` = query.
- Regenerate `captures/sdk_opcode_map.txt` with `tools/extract_opcodes.py` if the APK is re-decompiled.
- New captures go in `captures/` with a short `.md` next to them saying what the user did on the phone during the capture.

## Memory
Claude Code's persistent memory for this project lives **in the repo** at `memory/` (one fact per file, index in
`memory/MEMORY.md`). `~/.claude/projects/-home-buzz-RyzeWaveWatch/memory` is a symlink to it, so the memory
tooling keeps working; edit the files here, never under `~/.claude`.

## Git
The project is a local git repo (initialised 2026-09-05, branch `main`, no remote yet). `tools/Gadgetbridge` and
`tools/Gadgetbridge-tools` are submodules. Commit only when Buzz asks; never push — the GitHub remote will be a
*private* repo that Buzz creates after checking the layout himself.

## GPS data in the repo
Never commit real coordinates. Any GPS track that becomes a fixture or capture gets its latitude **and** longitude
shifted by constants of at least 1 km, with a different pair of constants for each log (Buzz, 2026-09-05). Record the
shift in the file's header comment or the capture's `.md`. Pulled databases stay git-ignored.
