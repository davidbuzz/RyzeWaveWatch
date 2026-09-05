---
name: ryze-wave-project
description: "Goal, hardware identity and key protocol facts for the Ryze Wave smartwatch reverse-engineering project (started 2026-09-04)"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8cfe5ce-cab3-47da-bf23-26c8acc106be
  modified: 2026-09-04T08:34:43.799Z
---

Buzz bought a Ryze Wave smartwatch (Australian brand, models RZ-WADA/B/C) and wants to replace the vendor "Ryze Fit" app (Android package `com.yc.ryzefit`) with their own. Work started 2026-09-04.

Established facts:
- Watch MAC `78:02:B7:37:91:E5`, BT name `Ryze Wave(ID-91E5)`. OUI = ShenZhen Ultra Easy Technology. Dual-mode: classic HFP/A2DP/AVRCP/PBAP/SPP for audio+calls, BLE GATT for health/settings.
- Protocol is the UTE/YC "GloryFit" family (SDK `com.yc.pedometer`). Gadgetbridge has a GloryFit driver (PR #5063, José Rebelo) with the same UUIDs: svc 0x55FF (33F1 write / 33F2 notify) and svc 0x56FF (34F1 / 34F2); APK also references 0x57FF. Buzz tried Gadgetbridge and it did not work for them in practice.
- App bundles the Realtek DFU SDK (`com.realsil.sdk.dfu`) so the SoC is Realtek RTL8763-family.
- The UTE SDK runs a `D5` password handshake before any other command when feature-bitmap bit0 (read of char 33F1) is set: `D5 02` asks the watch to display a code, `D5 01 <pw XOR 'UTE8'>` authenticates on reconnect, watch answers `D5 01` on success. Gadgetbridge never sends D5, which is the leading theory for why it failed for Buzz. Documented in the repo's docs/PROTOCOL.md.
- Record layouts: F7/B2 history records carry the date right after the opcode (`F7 yyyy MM dd HH <12 hr>`); Gadgetbridge's `FETCH_DATA=0x07` check is really the year's high byte. Our codec in `ryzewave/protocol.py` uses the correct offsets (tests in `tests/`).
- 2026-09-04 19:07 first contact with our own client (`python -m ryzewave`, bleak): read feature bitmap (FL1=0x4BA1D4, **no password needed**), version RH280RGAV008949, battery, and a full history sync (steps B2, HR F7, SpO2 34, sleep 31/32) all decoded correctly. Linux needs `tools/le_only.sh on` (BlueZ picks classic bearer otherwise) and connecting via the cached BlueZ device object (idle watch advertises every ~15 s; bleak scans miss it).
- Laptop BlueZ links to the watch die after 5-20 s with HCI supervision timeout (0x08) even at 50 cm; not distance, not the watch's classic radio, not scan duty cycle. Workaround/direction chosen 2026-09-04 evening: a headless Android app `android/` (RyzeBridge, package au.buzz.ryzebridge) on the Pixel that holds the GATT link and is driven over adb by `tools/bridge.py`; the phone's stack is stable with this watch. Buzz suggested this.
- 2026-09-04 19:34 RyzeBridge works: `tools/bridge.py info` through the Pixel connected in 20 ms (LE-bonded after Buzz accepted a pairing prompt), MTU 247, same feature bitmap, version, battery. Ryze Fit shares the phone's GATT link if running, so bridge.py force-stops it first.
- Verified through the phone bridge 2026-09-04 19:34-19:38: 2-minute link hold with no drops, full sync in 4 s, workout mode (`FD 11 01 01` start, `FD 01 <hr>` per second, `FD 44` updates, `FD 00 01 01` stop) with real wrist HR 82-97 bpm. Bare `E5 11` got no reply; the app sends `D6 01` first.
- Vendor distance model (PedometerUtils.calculateDistance): km = steps × height_cm × 0.418 / 1e5, or gender factors 0.410/0.415 walk, 0.546/0.505 run when FL2&0x1000 (set on Ryze Wave). Watch sends no distance for daily steps; workout distance/pace come from phone GPS and are pushed to the watch with FD 44. SpO2 spot test from the phone works (34 11 → ack → spurious 34 00 FF FF → 34 11 → ~57 s → 34 00 00 <spo2>); dynamic HR (D6 02 + E5 11) works; static HR (D6 01) reported nothing.
- End of 2026-09-04 session state: laptop main.conf back to dual mode (`tools/le_only.sh off`), phone Bluetooth ON and LE-bonded to the watch, Ryze Fit installed but force-stopped, RyzeBridge installed (au.buzz.ryzebridge), watch classic Bluetooth switched back ON (`38 02 01`). All spot features verified through the phone: sync, dynamic HR, SpO2 (~60 s), workout with per-second HR.
- 2026-09-04 23:20: the USB phone was swapped for a **Moto g05 (Android 15, SDK 35)**; Pixel 9a no longer attached. New phone starts unpaired with the watch and without Ryze Fit; RyzeBridge reinstalled there and connected first try (no pairing prompt). Buzz turned the Pixel's Bluetooth OFF at ~23:25 so it cannot compete for the watch. The Moto has Health Connect 2026.08 installed, location mode high-accuracy, and no vendor app.
- Laptop link-drop research (30-agent workflow): not the app/protocol; best-supported causes are the Intel AX201's 2M-PHY switch on a marginal RF link (adv RSSI -67..-83 dBm at 50 cm) and the kernel's 420 ms initial supervision timeout. Cheap tests noted in captures/bluez_linkdrop_notes.md (`btmgmt phy LE1MTX LE1MRX`, USB dongle). Phone remains the platform.
- First app build 2026-09-05 00:02 (`ryzeapp/`): assembleDebug green, 187 unit tests pass, installed on the Moto g05, WatchService connects and syncs on launch, dashboard shows battery/firmware/last sync, History charts render. Build needs `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (default java is a JRE).
- 2026-09-05 00:29: Health Connect export verified from the app on the Moto (172 records). Settings has Watch/Profile/Sampling/Stride/Health Connect sections; known UI bugs: profile fields revert while editing, SpO2 chips overflow, Export button squashed (docs/APP.md 'Known issues').
- 2026-09-05 00:36: review/fix pass applied 33 of 36 findings (BLE Ready-on-dead-link race, Health Connect export cursor, GPS spike filter, FINE-location gating, cancellation handling); rebuilt, installed, connected + synced, no crashes. Remaining UI bugs listed in docs/APP.md 'Known issues'.
- Buzz's profile in the app (real values kept OUT of the repo since the 2026-09-06 privacy scrub; docs use 175/75/40 placeholders); the UI-fix verifier temporarily set 183/113/51 and it was restored to 182/114/50 afterwards.
- 2026-09-05 00:52: Workout on the phone verified indoors (start, live HR from the watch, stop); GPS poor indoors so distance 0. Remaining real-world test: an outdoor walk for distance/pace/stride calibration.
- 2026-09-05 01:02 build 3: workouts export to Health Connect on stop (Exercise category visible), TX/RX packet log at INFO (the Moto drops DEBUG logs system-wide: log.tag=I), HR mean unified, chart labels clamped; 202 unit tests; all independently verified on the phone at 01:05. App state at end of night: build 3 installed on the Moto, connected to the watch, Health Connect export on, profile 182/114/50.
- 2026-09-05: project put into a local git repo (branch main, Gadgetbridge + Gadgetbridge-tools as submodules, apk/tools/builds ignored). Buzz will verify and push to a PRIVATE GitHub repo himself — do not push or add a remote.
- Phone was a Pixel 9a earlier; plan is to capture HCI snoop logs via adb rather than a hardware sniffer.

**Why:** the user explicitly wants to reverse engineer and write their own app; these identifiers are needed every session.
**How to apply:** start from the Gadgetbridge GloryFit code and the decompiled APK in the project's `apk/jadx-out` rather than re-researching. See [[ryze-wave-tooling]].

- 2026-09-05: build 6 = phone notifications to the watch, verified end to end (commit after b40739e). Sport ids known:
  FD 48 list (70) = watch menu order; 1 = Outdoor Running (all workouts so far), 0x23 Outdoor Walking, 2 Cycling.
  Realtime workout push is `FD <sportType> <hr> …` (14 B) — app decoder only matched type 1 (fix pending), see
  docs/APP.md "Sport types". Wrist check of names for 0x23/0x02 asked of Buzz.
- 2026-09-05 ~09:00: build 7 committed (ceb43b4: sport picker, HC exercise type by sport id, FD <type> decoder).
  First real outdoor walk (Pixel 9a, build 6) analysed: 155 m / 158 s, steady, cross-checks agree; watch hourly
  steps looked low (78) — asked Buzz which hand held the phone. Buzz is away for hours with the watch; a
  watch-independent batch workflow (track plot + HC route, export hygiene, sleep card, find-phone ringer,
  settings/icon/release polish) is running on the Moto. Tools: tools/pull_app_data.sh, tools/analyse_workout.py.
- 2026-09-05 ~11:30: build 8 = track plot + HC ExerciseRoute (route visible in Health Connect), export ledger
  (Room v3, fingerprints, stride re-export), sleep card + hypnogram, find-phone ringer (debug trigger), MAC field
  fix, launcher icon, signed release build (keystore git-ignored at ryzeapp/keystore/). 324 unit tests. Track plot
  showed a poor first anchor + Doppler-credit over-count on the real walk (docs/APP.md "Tracker finding").
- 2026-09-05 15:15: Pixel 9a got the 11:43 debug build (build 8 + tracker refinement, then uncommitted); synced and Health Connect route permission granted. Buzz is back with the watch; it links to the Pixel, so the Moto cannot reach it.
- 2026-09-05 15:30: tracker refinement (per-fix Doppler integral, first-anchor wait, re-anchor) FAILED independent re-verification: wait-integral dropped (-45 m/start at 3 m/s), speed-less-receiver deadlock at 0 m, creeping-receiver stop cost (+33 % at 25 m), spike speeds integrated. Fix-and-verify loop running; NOT committed. The Pixel's 11:43 build carries the draft tracker — reinstall when the fixed build exists.
