# Ryze Wave application research notes

Technical notes on everything that turned out to matter while reverse-engineering the Ryze Wave smartwatch and
building "Buzz's Ryze Wave", the replacement for the vendor's Ryze Fit app. Written 2026-09-05 after two days of
work. Each fact is tagged by how we know it:

- **[captured]** — seen on the wire between a phone (or the laptop) and this watch, and reproduced by our own code.
- **[vendor SDK]** — learned by studying the behaviour of the vendor app's Bluetooth library. No decompiled code is
  reproduced here; the notes describe what the app does, in our words. The decompiled tree is kept locally and is
  not part of the repository.
- **[Gadgetbridge]** — from the open-source Gadgetbridge GloryFit driver and its Wireshark dissector (submodules under
  `tools/`), which speak the same protocol to sibling watches.

The precise byte tables live in `PROTOCOL.md`; the app design in `APP.md`; the plan in `PLAN.md`. This document is
the narrative: what we needed, why, and what nearly went wrong.

---

## 1. The watch

| | |
|---|---|
| Product | Ryze Wave (Ryze Above, Australia), models RZ-WADA/B/C, 1.43" AMOLED 466×466, 300 mAh, IP68 |
| Bluetooth name / address | `Ryze Wave(ID-91E5)` / `78:02:B7:37:91:E5` — a public address shared by both radios [captured] |
| Address vendor (OUI) | ShenZhen Ultra Easy Technology, the ODM [captured] |
| System-on-chip | Realtek RTL8763-family. Inferred from the Realtek DFU/OTA library and a Realtek AES helper bundled in the vendor app, and from Realtek's OTA service UUIDs on the watch [vendor SDK, captured] |
| Firmware string | `RH280RGAV008949` (reply to the version command) [captured] |
| Vendor app | "Ryze Fit", Android package `com.yc.ryzefit` (v1.3.8), a white-label of the UTE / Shenzhen Youhong "yc pedometer" SDK. Other white-labels of the same SDK: GloryFit, DayDay Band, and many AliExpress watches [vendor SDK] |

**Two radios, two jobs** [captured]. Classic Bluetooth (BR/EDR) carries the standard audio profiles: Handsfree,
A2DP, AVRCP and phone-book access, plus SPP. That is how calls are answered and music plays; nothing to reverse
there. Bluetooth Low Energy carries a proprietary GATT protocol for everything else: health data, settings,
notifications, workouts. The whole project is about the BLE side.

## 2. Finding the protocol family

The first decisive clue was the vendor app's package name prefix `com.yc.`, which points at the UTE SDK. The app's
GATT UUID table confirmed it [vendor SDK]: service `0x55FF` with characteristics `0x33F1` (write) and `0x33F2`
(notify) for commands, service `0x56FF` with `0x34F1`/`0x34F2` for a second "data" channel, and `0x57FF` for
payments (unused). Gadgetbridge's GloryFit driver uses exactly these UUIDs, so we had a working open-source
reference for the basic shape of the protocol from day one [Gadgetbridge].

That reference was invaluable and also wrong in places (section 6), which is why every later claim was checked
against captures from our own watch.

## 3. Connecting: what a client must do

[captured on Android; the laptop path in section 12]

1. Connect over LE (`connectGatt` with the LE transport on Android), discover services, request MTU 247 (the watch
   grants it), enable notifications on `33F2` and `34F2`.
2. **Read** characteristic `33F1`. This is not a write-only endpoint: a GATT read returns a 20-byte **feature
   bitmap** [vendor SDK, captured]. Read `34F1` too: two bytes give the maximum packet length (244), followed by a
   second six-word feature bitmap in the same 3-byte layout; on this watch only its word 1 is non-zero (0x400000).
3. Send the "settings burst": time (`A3`), user profile (`A9`), continuous HR on/off (`F7 01/02`), SpO2 auto-sampling
   and its time window (`34 03`, `34 04`), reminders, canned replies, a language-list query, and so on. The vendor
   app sends about forty commands here; ours sends the ones that matter.
4. Fetch history (section 6).

Packets are raw bytes with no framing or checksum; the first byte is the opcode, and most replies echo the opcode.
Commands are sent one at a time; the watch answers each before the next is sent. Both channels can carry
notifications at any time (section 7).

### The feature bitmap

The 20 bytes are seven big-endian words read from the **end** of the buffer: a 2-byte word 7 first, then six
3-byte words 6 down to 1 [vendor SDK]. On the Ryze Wave: `FL1=0x4BA1D4 FL2=0x005D78 FL3=0xFED921 FL4=0x756EDF
FL5=0x0C3943 FL6=0x642A21 FL7=0x00080A` [captured]. The bits we act on:

| Word & bit | Meaning | Ryze Wave |
|---|---|---|
| FL1 bit 0 | watch requires the password handshake (section 4) | clear |
| FL4 bit 13 (0x2000) | history fetches take a "since" timestamp | set |
| FL4 bit 18 (0x40000) | sleep is fetched with `31 01` and staged `32` records | set |
| FL5 bit 1 | the watch accepts the per-second workout metric push (`FD 44`) | set |
| FL5 bit 2 | account-ID pairing variant | clear |
| FL2 bit 12 (0x1000) | gender-specific stride factors in the vendor distance formula | set |

Gadgetbridge never reads this characteristic. That alone explains several of its differences.

## 4. The password handshake we did not need

The SDK contains a pairing handshake keyed on opcode `D5` [vendor SDK]: the phone asks the watch to display a code
(`D5 02`), the user types it into the app, and on later connections the phone proves it knows the code by sending it
XOR-ed with the string `UTE8` (`D5 01 …`). The watch answers `D5 01` for success, `D5 02` when a code is required,
`D5 FF` for a wrong code. It only applies when feature bit FL1.0 is set. Our watch has it clear, and the vendor app
indeed goes straight from the MTU exchange to the time command with no `D5` at all [captured]. The code is kept in
our client for sibling watches that do need it, and it remains the leading theory for why Gadgetbridge fails on
some GloryFit watches ("some sort of key that unlocks pairing" in its issue tracker).

## 5. Command conventions and the opcode map

- Sub-command bytes are consistent across families [Gadgetbridge, vendor SDK]: `FA` starts a history fetch, `FD`
  ends it (or acknowledges), `AA` queries, `01/02` toggles.
- The command channel `33F1/33F2` carries almost everything. The data channel `34F1/34F2` carries bulk or
  multi-packet payloads: sleep stages, contacts, canned messages, the sport list, the classic-Bluetooth control.
- The SDK exposes roughly 160 send methods (158 in `captures/sdk_opcode_map.txt`) covering about 90 distinct
  opcode bytes [vendor SDK]. Families we use: `A1/A2/A3/A9` (device and profile),
  `B2/B1` (steps), `F7/E5/D6` (heart rate), `34` (SpO2), `31/32` (sleep), `FD` (workouts), `38` (classic radio),
  `AB` (vibrate / find watch). Families we identified but left for later: `C5/C6` notifications (UTF-16 text in
  chunks with an app-type byte), `CA/CB` weather, `51` alarms, `46` canned replies, `37` contacts and SOS, `26/27`
  watch-face upload, `28` ECG, `C7/C8/3E/55` blood pressure, `24` temperature, `44` mood/stress, `29` menstrual
  cycle, `60/68` Alipay / WeChat Pay. Several of those correspond to sensors this watch does not have; the SDK is
  shared across many products and the feature bitmap says which parts apply.

## 6. History records and the pitfalls in them

All verified on 2026-09-04 by fetching from our watch and checking against the clock [captured].

**Steps**: `B2 FA` starts the fetch; each 18-byte record is one hour: date and hour, total steps, and separate
walking and running counts with their start/end minutes. The hour is the *start* of the hour. `B2 FD xx` ends.

**Heart rate, 24-hour series**: `F7 FA` plus a six-byte "since" timestamp (all zeros = everything). Each 18-byte
record carries a date, an hour, and twelve values at 10-minute spacing, `0xFF` meaning no sample. Records come every
two hours (hours 20, 22, 00, 02 …) and the hour byte is the **end** of the window: at 19:35 the record stamped 20 h
already covered eight slots (18:10 … 19:20: seven values and one `FF` for a missed 19:00 sample) followed by `FF`
for the four slots not yet reached. Ends with `F7 FD xx`.

**SpO2**: `34 FA`; 20-byte records with date, hour and minute (the window end) and twelve 10-minute values. Ends with
`34 FA FD xx`.

**Sleep**: `31 01` announces a session date on the command channel, then a `32` packet on the data channel lists
stages as (hour, minute, stage code 1–4, duration in minutes); `31 02` ends. Times after noon belong to the previous
evening. Stage code meanings are still a best guess (2 dominates and looks like light sleep).

**The year-byte trap.** Gadgetbridge treats the byte after the `F7` opcode as a "data" marker with value `0x07`
and the byte after `34 FA` likewise. Those bytes are the high byte of the year: `0x07E9` is 2025, `0x07EA` is 2026.
The check only works this century by accident, and the record date offsets in our decoder are one byte earlier than
Gadgetbridge's interpretation. Our unit tests replay captured packets so this cannot regress. The "since" stamp for
fetches is `yyyy MM dd HH mm` big-endian, and the app remembers per data type where it last got to [vendor SDK].

## 7. Live measurements and unsolicited pushes

**Live heart rate.** The command `E5 11` on its own produced nothing [captured]. The vendor app's HR screen first
selects a mode with `D6 02` (continuous, "dynamic") or `D6 01` (single "static" test), then waits before sending
`E5 11` — 1.5 s when its "RK platform" flag (Realtek) is set, 0.5 s otherwise [vendor SDK; the vendor app never
sent `D6`/`E5` in our captures, so this sequence is known from the app's behaviour, not the wire]. Our reproduction
with `D6 02`, 1.5 s, `E5 11` made the watch stream `E5 11 00 <bpm>` once a second after a warm-up of 9.8 s in one
run; a second run produced nothing within 12 s, so a client should allow well over 12 s before declaring failure
[captured]. `E5 00` stops the stream within a few seconds. The static variant never reported anything to the phone
in 75 s and was abandoned.

**SpO2 spot test** [captured]. `34 11` is acknowledged and, in five of six phone-initiated runs, a `34 00 FF FF`
arrives together with the ack (0.35–0.4 s after the write); it looks like a failure but is spurious. The watch then
re-announces `34 11`, wakes its screen with a measurement page, and about 57 seconds later delivers
`34 00 00 <percent>` (97, 96, 96 % in our three completed runs); Buzz felt a short buzz on the wrist at that moment
(on-wrist observation). In the sixth run the ack alone was followed by the result. Our first client gave up on the
spurious packet; the fix is to ignore a failure inside the first three seconds and keep waiting up to 90 s. A test
started on the watch itself produces the same packets: the vendor-app capture of 2026-09-04 18:43 shows `34 11`
followed 23 s later by `34 00 00 62` (98 %) with no phone command (`captures/vendor_watch_initiated_tests_20260904.txt`).

**Pushes the app must accept at any time** [captured unless noted]: `F7 03 <date> <hour> <bin> <bpm>`, the
automatic HR sample, where the byte after the hour (index 7, the eighth byte) is the 10-minute bin index within the
hour (0–5), not minutes — the vendor library reads it as minutes; more than a dozen pushes, each arriving about eight
minutes after the slot it reports, say otherwise; `F7 04 <date> <hour> <minute> <max> <min> <avg>`, a daily HR
summary sent repeatedly; `A2 <percent> 01` while charging; `B1` realtime hourly step records; `E5 11 00 <bpm>` once
a second when a measurement is started on the watch itself (observed for 25 minutes with no phone command, `00`
while another sensor test runs); `34 11` / `34 00 00 xx` for watch-started SpO2 tests. `D1 0A` find-my-phone is
expected from the SDK and Gadgetbridge but has not been observed unsolicited — the `D1 0A 00` seen during init is the
watch echoing the phone's own command.

## 8. Workouts

[captured from the vendor app during a real workout, then reproduced from our bridge and app]

The phone drives the watch, and the watch drives the phone's display of heart rate:

- `FD 11 <type> <interval>` starts a workout (type 1 is the outdoor mode we used; the interval byte is the HR report
  period in seconds). The watch echoes it. `FD 22 <type> <interval> hh mm ss …` pauses (13 bytes with the same
  payload layout as `FD 44`, echoed; captured once), `FD 00 <type> <interval>` stops (echoed). `FD 33` (resume) is
  documented by the SDK but has not been captured [vendor SDK]. Observed on the wrist, not captured: the watch
  switches to its workout page on start and shows a summary page on stop.
- The watch sends `FD 01 <bpm> 00…` (14 bytes) at irregular 1–11 s intervals — 14 packets in a 57 s workout with
  interval byte 1 — roughly once a second only while it has a fresh reading. Only the heart rate is understood; the
  zeros are probably steps/calories for sports without GPS.
- The phone sends a 13-byte `FD 44 <type> <interval> hh mm ss …` once a second, and the watch echoes it. The captured
  vendor workout was indoors, so only the elapsed-time bytes were non-zero; the remaining fields
  `<calories16> <km> <km hundredths> <pace min> <pace sec>` are known from the SDK and pinned by our codec tests, not
  yet by a capture with real movement [vendor SDK]. This is how distance and pace appear on the watch face. Rounding
  must carry into the whole-kilometre byte at x.995 km, a detail found while porting: the Python reference wrapped
  2.995 km to 2.00; both codecs now carry and both test suites pin it.
- The watch computes none of this. During a workout the vendor app takes distance and pace from the phone's GPS and
  pushes them; the watch only contributes heart rate.

## 9. Settings the watch does not keep for you

The vendor app re-sends its whole configuration on every connection, and so must we [captured]: time, profile,
continuous HR, SpO2 auto-sampling and window, reminders (it also queries the language list with `AF AA`; no language
set was seen). Presumably a factory-reset watch has none of them (untested — the reset command was never sent), so an
app that only sends settings when the user changes them would silently lose automatic sampling after a reset. Two
concrete lessons:

- Automatic SpO2 (`34 03 01 <16-bit minutes>`) wakes the screen and buzzes briefly at every sample. The vendor menu
  offers 10/30/60/120/180/240/360 minutes; the field accepts other values (5 was acknowledged) but our test of whether
  the watch honours them was overwritten two minutes later when Ryze Fit reconnected and re-sent 10. Any app that
  connects re-applies its own settings — Ryze Fit and our app fight over the watch if both are running.
- The 10-minute HR series cadence is fixed by the continuous mode (`F7 01`); it cannot be changed. The separate
  "timed HR test" (`D6 10 <hours>`) is an extra spot measurement every 1/2/6/12 hours [vendor SDK] and is not
  acknowledged on the wire (both `D6 10 0A` and `D6 10 00` timed out waiting for an echo,
  `captures/bridge_d610_restore.txt`) — the protocol reference's earlier "echoed as ack" note was wrong.

## 10. The classic radio is controllable over BLE

`38 02 01/00` on the data channel switches the watch's classic Bluetooth on or off (echoed), and `38 01 02 00
<4 bytes>` returns the watch's classic name and address followed by three status bytes whose meaning is not pinned
down (observed `01 00 00`, `01 01 00`, `01 01 01`; the first is the radio state) [captured]. The SDK generates the
four trailing request bytes randomly, but the vendor app sent the same `D2 5D AE 5D` every time and zeros are
accepted [vendor SDK, captured]. The vendor app uses this to pair the audio side after the BLE side. We used the
off switch while chasing the laptop link drops — the LE link still dropped, so classic paging was not the cause
(`captures/bluez_linkdrop_notes.md`) — and switched it back on afterwards. Bonding is optional for BLE: a second phone
connected with no pairing prompt at all; the one prompt we saw on the first phone was incidental.

## 11. Distance: the actual grievance

The watch never reports distance. The `B2` records carry step counts only [captured]. Every distance figure in the
vendor app is computed on the phone [vendor SDK]:

- Daily: kilometres = steps × height(cm) × factor ÷ 100 000, i.e. a stride of 0.418 × height, or with feature bit
  FL2.12 set (it is on this watch) gender-specific factors: walking 0.410 (male) / 0.415 (female) × height, running
  0.546 / 0.505 × height. For a 175 cm person that is 72 cm per walking step and 96 cm per running step, and the
  walking/running split comes from the watch's per-hour counts.
- Workouts: the phone's GPS track, summed between consecutive fixes after a smoothing pass, with no visible accuracy
  or speed gating, then pushed to the watch face (section 8).

Our app owns both calculations: the vendor factors are only defaults, a stride can be calibrated from a GPS walk
(GPS distance ÷ the steps the watch counted in the workout's window; a workout averaging under 2 m/s sets the walking
stride, a faster one the running stride), and the GPS tracker rejects fixes worse than
20 m, ignores movement smaller than the accuracy radius when nearly stationary, checks implied speed for spikes,
sums haversine distance between accepted fixes, derives pace from a rolling window, and keeps the raw track so a
distance can be recomputed with a better filter later. The outdoor walk that validates this is the remaining test.

## 12. Capturing traffic: three methods, one surprise

1. **The vendor app's own log** [vendor SDK, captured]. Its Bluetooth library logs every packet it sends and
   receives, in hex, with a release build that leaves that logging enabled. With USB debugging, `adb logcat` shows
   lines like `APK--->BLE4 = A1` and `BLE--->APK4 = A152…` (4 = command channel, 5 = data channel), plus decoded
   state: GPS fixes during workouts, the feature bitmap words, heart-rate values. This made a hardware sniffer and
   the Android HCI snoop log unnecessary. The reconnect burst captured this way (`captures/reconnect_*.md`) is the
   canonical init sequence.
2. **Our own bridge** (`android/`, `tools/bridge.py`): a headless Android app driven over `adb` that holds the GATT
   link and logs `TX 33F1 <hex>` / `RX 33F2 <hex>`. Android delivers notifications to every app that enabled them, so
   while the vendor app was also connected the bridge saw its entire traffic — useful, and a warning that a
   companion app must ignore packets it did not ask for. `tools/parse_logcat.py` decodes both log formats.
3. **HCI snoop / btmon on the laptop**: kept as a fallback (`tools/pull_btsnoop.sh`, `tools/decode_btsnoop.py`,
   the Gadgetbridge Wireshark dissector under `tools/wireshark/`), mostly used to diagnose the laptop radio.

## 13. Platform lessons

### Linux / BlueZ (laptop) — parked
- BlueZ 5.72 chooses the **classic** bearer for this dual-mode, public-address watch, because its LE advertisement
  flags do not say "BR/EDR not supported". You get an audio link, an unwanted bond, and no GATT. `ControllerMode = le`
  in `main.conf` forces LE. Newer BlueZ has a per-device `PreferredBearer` property.
- Idle, the watch advertises only every ~15 s (`captures/btmon_adv_interval_20260904.txt`), and BlueZ suppresses
  RSSI-only updates within 8 dB, so a bleak scan typically never reports it. Connect via the cached device object or
  scan with an RSSI filter.
- The watch answers ATT requests slowly from this laptop (0.3–0.5 s each), so first-time GATT discovery takes
  30–40 s; `bluetoothctl trust` makes BlueZ persist the cache.
- Every LE link from the laptop still died after 5–20 s with a supervision timeout. A 30-agent research pass ruled
  out the app, keepalives, init order and the LE-only mode; the best-supported causes are the Intel AX201's switch to
  the 2M PHY on a marginal RF link (advertisements arrive at −67 to −83 dBm from 50 cm) and the kernel's 420 ms
  initial supervision timeout. Cheap tests if ever needed: force 1M PHY (`btmgmt phy`), or a USB dongle. Details in
  `captures/bluez_linkdrop_notes.md`. Decision: the phone is the radio.
- Tooling traps that cost real time: `pkill -f` matching the shell running it; `grep` being `ugrep` so a pattern
  starting with `-` needs `-e`; foreground sleeps being blocked, so long jobs run in the background.

### Android (phone) — the platform
- Two different phones (Pixel 9a, Moto g05) held the link for as long as we cared to test, with 2M PHY, a 5 s
  supervision timeout, and intervals of 7.5–45 ms while traffic flows, dropping to 315 ms about ten seconds after
  the last packet (Android reports 1.25 ms units: 6/24/36 and 252). Use `TRANSPORT_LE`; the watch accepts any LE
  central.
- One GATT link is shared between apps. Force-stop the vendor app before testing, and never trust an unsolicited
  packet unless it is one of the known pushes.
- Foreground services need the `connectedDevice` (watch link) and `location` (workout) types on Android 14+; starting
  them through an activity launched with `adb shell am start` (the bridge's invisible activity starts the service
  from `onCreate` and finishes) sidesteps the background-start restrictions, which is what made the headless bridge
  practical.
- Health Connect permissions are platform permissions on Android 14+, so `adb shell pm grant` works, no dialog
  tapping. The Moto g05 sets `log.tag=I` system-wide and drops every app's `Log.d`, so the packet log had to be
  raised to INFO before it showed up.
- `uiautomator dump` plus `adb shell input tap` is enough to drive a Compose UI for verification
  (`tools/app_tap.sh`); an exact-text match must be tried before a substring match, or "40" taps the wrong field.

## 14. Health Connect mapping (what "send it to Google Health" means now)

Google Fit's API is gone; Health Connect is built into Android 14+. We write hourly `StepsRecord`s, `HeartRateRecord`s
grouped per hour, one `OxygenSaturationRecord` per sample, `DistanceRecord`s per day (stride model) and per workout
(GPS), a `SleepSessionRecord` per night with stages mapped 1 deep / 2 light / 3 REM / 4 awake (best guess), and an
`ExerciseSessionRecord` per workout, all with client record ids so re-exports update rather than duplicate. Verified
end to end: Health Connect lists the categories and shows entries attributed to "Buzz's Ryze Wave". Export runs
after each sync and after each workout; the export cursor is a phone-clock timestamp with a five-minute lookback.

## 15. How the app was built and checked

- Contracts first (`core/`), then six packages implemented in parallel against them, then an integrator that wired
  and built, then an adversarial review pass with five lenses (protocol vs. reference, BLE concurrency, Health
  Connect, GPS distance, UI flows — recorded in the session's workflow script, not in the repo), then a fixer; 36
  findings, 33 fixed. Two more fix-and-verify rounds: build 2 for UI bugs found by hand (profile-edit revert, SpO2
  chip overflow, export-button layout) plus the midnight rollover, and build 3 for the workout → Health Connect
  export, a packet log and chart polish.
- The Python codec (`ryzewave/protocol.py`) is the executable specification; the Kotlin port has 202 unit tests, 48
  of them protocol tests that replay the captured packets byte for byte, so the two references cannot drift
  unnoticed (the one divergence found so far, the `FD 44` kilometre carry, was caught while porting and is now
  pinned by both suites).
- On-phone verification is scripted: build-install-launch-screenshot (`tools/app_smoke.sh`), text-driven taps,
  a scripted workout (`tools/app_workout_test.sh`), and reading what was actually saved rather than what the
  screen claims — the verifier pulled the Room database (`captures/verify_build3/ryzewave.db`), and the DataStore
  settings file was read by hand over `adb shell run-as`.

## 16. Open questions

- Sleep stage code meanings (compare against the vendor app's sleep screen for one night).
- The remaining bytes of the `FD 01` workout push.
- Whether SpO2 auto-sampling honours intervals outside the vendor menu (5, 20 min).
- The two unknown classic SDP UUIDs (`0x5536`, `0x2222`).
- The static HR test (`D6 01`) — perhaps it only reports after a longer timer than we waited.
- The laptop radio, only if a laptop client is ever wanted.
