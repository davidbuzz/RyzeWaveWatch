# "Buzz's Ryze Wave" — Android app spec

Gradle project in `ryzeapp/` (Kotlin 1.9.22, AGP 8.6.0, compileSdk 35, minSdk 26, Compose BOM 2024.02.02,
Room 2.6.1 via KSP, Health Connect client 1.1.0-alpha11, play-services-location 21.1.0). Package `au.buzz.ryzewave`.
Build: `cd ryzeapp && ./gradlew :app:assembleDebug` (SDK in `../tools/android-sdk`, see `local.properties`).
Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The protocol is fully described in `docs/PROTOCOL.md`; `ryzewave/protocol.py` is the reference codec and
`android/src/au/buzz/ryzebridge/BleService.java` is proven GATT code for this watch (connect, MTU, CCCD, write
queue, notification dispatch). Port, don't reinvent.

## Packages and owners

| Package | Files | Responsibility |
|---|---|---|
| `core` | `Models.kt`, `WatchApi.kt`, `HealthRepository.kt`, `DistanceModel.kt` | contracts (already written, do not change signatures without updating all users) |
| `protocol` | `Protocol.kt`, `Packets.kt` | pure Kotlin port of `ryzewave/protocol.py`: UUIDs, opcodes, encoders `encXxx(): ByteArray`, decoders for B2/F7/34/31+32/E5/FD 01/F7 03/F7 04/A1/A2/38 01, `Features` bitmap. Unit tests in `app/src/test` replaying the hex packets from `docs/PROTOCOL.md` and `tests/test_protocol.py`. No Android imports. |
| `ble` | `WatchGatt.kt`, `WatchService.kt`, `WatchApiImpl.kt` | `WatchGatt`: BluetoothGatt wrapper with a single-outstanding-op queue (Mutex + CompletableDeferred), `connectGatt(TRANSPORT_LE)`, `requestMtu(247)`, CCCD on 33F2/34F2, `write(cmd)`, `request(cmd, opcode)`, `collect(cmd, opcode, isEnd)` (like the Python client), reconnect with backoff, `onConnectionUpdated` logging. `WatchService`: foreground service (type connectedDevice) that owns the link and runs `applySettings` + `syncAll` on connect and every 30 min. `WatchApiImpl` implements `core.WatchApi` and persists through `HealthRepository`. |
| `data` | `Db.kt` (Room database + entities + DAOs), `RoomHealthRepository.kt`, `SettingsStore.kt` (DataStore) | implements `HealthRepository` and `SettingsStore`. Entities: steps_hour(hourStart PK), hr_sample(time, source PK), spo2_sample(time, source PK), sleep_stage(start PK), workout(id auto), track_point(workoutId, time PK), sync_cursor(kind PK). `DailySummary` computed with a query + `StrideModel`. |
| `health` | `HealthConnectExporter.kt` | writes Steps/HeartRate/OxygenSaturation/Distance/Sleep/ExerciseSession records to Health Connect since the export cursor, idempotent via `clientRecordId` (e.g. `hr-<time>`), handles SDK availability (`HealthConnectClient.getSdkStatus`), permission request contract for the UI, `exportAll()` and `exportSince(cursor)`. Mirrors `~/MyPulseApp/app/src/main/java/com/example/pulseapp/MainActivity.kt` for the permission flow. |
| `workout` | `DefaultStrideModel.kt`, `DefaultGpsDistanceTracker.kt`, `WorkoutService.kt`, `WorkoutController.kt` | stride model (vendor factors as defaults: walk 0.410/0.415 × height, run 0.546/0.505; calibrated override), GPS tracker per `docs/PLAN.md §3b`, foreground location service using FusedLocationProvider at 1 Hz, drives `WatchApi.startWorkout/updateWorkout/stopWorkout`, stores `Workout` + `TrackPoint`s, exposes `StateFlow<WorkoutState>` (elapsed, distance, pace, hr, gps quality), GPX export to the app's files dir. |
| `ui` | `RyzeApp.kt` (NavHost + bottom bar), `DashboardScreen.kt`, `HistoryScreen.kt`, `WorkoutScreen.kt`, `SettingsScreen.kt`, `Charts.kt`, `ViewModels.kt` | Material3 Compose. Dashboard: connection card (state, battery, firmware, connect/sync buttons), today's steps + goal ring, distance (stride model), last HR / SpO2 with time, sleep last night. History: day picker, HR line chart (10-min series + live/workout samples), SpO2 chart, steps bar chart per hour; 7/30-day step totals. Workout: start/pause/stop, live HR, distance, pace, elapsed, GPS accuracy; list of past workouts with summary. Settings: watch MAC (scan list or manual), profile, sampling (continuous HR, SpO2 auto + interval incl. 5/20 min), stride calibration (auto from last GPS walk + manual), Health Connect toggle + permission button + "export now", find watch. `Charts.kt`: hand-drawn Compose Canvas charts (no chart library; same idea as the `PulseView` Path-on-Canvas in `~/MyPulseApp/.../MainActivity.kt`). |

| root | `App.kt`, `GraphFactory.kt`, `MainActivity.kt` | `GraphFactory.create(app)` wires Room repo, settings, `WatchApiImpl`, exporter. `MainActivity`: runtime permissions (BLUETOOTH_CONNECT/SCAN, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS), Health Connect permission launcher, sets content to `RyzeApp()`. |
### The charts (at least these five)
1. **Heart rate, one day** — line chart of the 10-minute `F7` series with gaps for missing bins, plus dots for live/workout/auto samples; min/max/avg labels; tap to read a value.
2. **SpO2, one day** — line/dot chart of the 10-minute series and spot tests, y-axis 85-100 %.
3. **Steps per hour, one day** — bar chart (walk vs run stacked), with the daily goal line on the cumulative total.
4. **Daily steps and distance, last 7 / 30 days** — bars for steps with the distance (stride model) as a secondary line; today highlighted.
5. **Workout detail** — HR over elapsed time with pace (from the GPS tracker) as a second series; distance markers every km.
All charts: axis ticks with times/dates, dark and light theme aware, `Modifier.fillMaxWidth().height(200.dp)`, empty-state text when there is no data.

**History screen layout (Buzz's request, 2026-09-04):** one shared date navigator at the top (`<  4 Sept 2026  >`),
then FOUR chart cards stacked in a scrollable column — heart rate, SpO2, steps per hour, daily steps + distance
(7/30 days) — each styled like the vendor app's blood-oxygen card in `captures/ryzefit_spo2_reference.png`:
a coloured header card with the line-and-dots chart, dashed horizontal gridlines with the value labels on the left
(e.g. 88/91/94/97/100 for SpO2, 40..180 for HR), x-axis time labels (00:10 … 20:00), then a "Minimum / Maximum"
(or total / average) row under the chart, and an expandable "Today's data (n)" list of time / value rows.
The workout-detail chart is its own screen.

## Behaviour that must match the watch (all verified 2026-09-04)
- Connect: `connectGatt(ctx, false, cb, TRANSPORT_LE)` → discover → `requestMtu(247)` → enable notifications 33F2 and 34F2 → read 33F1 (feature bitmap, keep it) → `A3` time → `A9` profile → `F7 01/02` → `34 03` / `34 04` → sync.
- Sync: `B2 FA` → 18-byte `B2` records → `B2 FD xx`; `F7 FA <since6>` → 18-byte `F7` records (date at byte 1, 12 ten-minute values ending at HH:00, 0xFF = none) → `F7 FD xx`; `34 FA` → 20-byte records (date at byte 2, values end HH:mm) → `34 FA FD xx`; `31 01` → `31 01 date n` then `32` stage packets on 34F2 → `31 02`.
- Live HR: `D6 02`, 1.5 s, `E5 11` → `E5 11 00 <hr>` per second; stop `E5 00`.
- SpO2 test: `34 11` → ignore `34 00 FF FF` within 3 s → `34 00 00 <pct>` ≈ 60 s later; give up after 90 s.
- Workout: `FD 11 <type> 01` (echoed) → `FD 01 <hr> …` per second; push `FD 44 <type> 01 hh mm ss cal16 km frac2 pace_m pace_s` each second; pause `FD 22`, resume `FD 33`, stop `FD 00 <type> 01` (echoed).
- Pushes to handle at any time: `F7 03` auto HR sample (byte 7 = 10-min bin index), `F7 04` daily HR summary, `34 00 00 xx` SpO2 result, `B1` realtime steps, `A2 <pct> [01]` charging, `D1 0A` find phone.
- Ryze Fit (`com.yc.ryzefit`) shares the GATT link if running; nothing to do about it, but the app should not be confused by its traffic (only react to opcodes it asked for or the pushes above).

## Health Connect mapping
StepsRecord per hour (count, start/end), HeartRateRecord with samples grouped per day (or per hour), OxygenSaturationRecord per sample, DistanceRecord per day (stride model) and per workout (GPS), SleepSessionRecord per night with stages mapped (1 → deep, 2 → light, 3 → REM, 4 → awake — best current guess, keep a single mapping function), ExerciseSessionRecord per workout (type walking/running by sport type). Use `Metadata(clientRecordId = …, clientRecordVersion = 1)` so re-exports update instead of duplicate.

## Definition of done for this iteration
`./gradlew :app:assembleDebug` succeeds; unit tests in `app/src/test` pass; APK installs on the USB phone (Moto g05, Android 15, Health Connect 2026.08 present, location high-accuracy); the app connects to `78:02:B7:37:91:E5`, syncs, shows today's numbers and charts, can run a workout with live HR and GPS distance, and exports to Health Connect after permissions are granted.

## Known issues after build 1 (2026-09-05 00:30) — FIXED in build 2 (2026-09-05 00:45) except workouts
Build 2 fixes (verified on the phone, screenshots in `captures/fix_verify/`): Profile keeps local edits until Save
(`edited` flag in `ProfileSection`); SpO2 interval chips wrap in a `FlowRow`; Health Connect buttons are full-width
rows; `ui/DayClock.dayStarts()` (minute ticker, unit-tested in `DayClockTest`) drives the Dashboard day and the
History default day / 7-30 day range so "Today" rolls over at midnight. `gradle.properties` sets `org.gradle.java.home`.
- Settings > Profile: values typed into Height/Weight/Age revert to the stored profile while editing (state keyed on the
  profile flow re-emits), so "Save profile" stores stale values (weight typed 75 → saved 50). Keep local edit state
  until Save; only reset it when the user leaves the screen.
- Dashboard "Today" does not roll over at midnight until the app is reopened (integrator note); add a midnight tick.
- Settings > Sampling: the SpO2 interval chips (5/10/20/30/60 min) overflow the row; the 4th chip renders as a tall
  empty box and the rest are cut off. Use a FlowRow or a dropdown.
- Health Connect export VERIFIED 2026-09-05 00:29: 172 records (steps 17, HR 28, SpO2 123, distance 2, sleep 2) after
  enabling the toggle and tapping Export now (permissions granted via adb). Health Connect's Data and access screen lists Distance, Steps, Sleep, Heart rate, Oxygen saturation, and a heart-rate entry reads "12:00 AM - 12:20 AM • Buzz's Ryze Wave, 74-83 bpm" (captures/hc_data.png, hc_heart_rate.png). Cosmetic: the "Export now" button is a
  squashed circle with wrapped text — give it a fixed min width / put it on its own row.
- Workouts were not exercised on the phone yet.

## Nits seen in build 2 (2026-09-05 00:44)
- History HR card: the in-chart "avg 76" label disagrees with the stats row "Average 75 bpm" — the chart's mean probably
  includes live/auto samples while the stats row uses the 10-minute series (or one rounds differently). Use one mean.
- SpO2 chart: the "100" axis label and the "99" point label overlap at the top-left; nudge point labels away from axis ticks.

## After the workout test (2026-09-05 00:53)
- Past workouts lists "Sat 5 Sep 00:52 · 01:03 · 0 m · --:-- /km · avg 70 bpm" (indoor test, GPS ±39 m). OK.
- Health Connect's Data and access screen still shows no "Exercise" category after the workout, so the
  ExerciseSessionRecord export either did not run (export only fires after a watch sync) or skipped the session.
- The app does not log raw watch packets, so on-phone protocol diagnosis needs a debug-level TX/RX log in WatchGatt.

## Build 3 (2026-09-05 01:02) — polish pass
- Workouts now export to Health Connect when stopped (`WorkoutController.onFinished` → `GraphFactory.exportNew`);
  HC lists an Exercise category with "Walking • Ryze Wave walk" sessions from Buzz's Ryze Wave.
- Packet log: `adb logcat -s WatchGatt:*` shows `TX 33F1 <hex>` / `RX 33F2 <hex>` / `RX 34F2 <hex>`. Logged at INFO
  because the Moto g05 sets `log.tag=I` system-wide and drops DEBUG for every app (a `log -p d` probe never appears).
- HR card: chart "avg" and stats "Average" now share `ChartData.meanValue` (rounded, same sample set).
- Charts: floating min/max point labels are clamped inside the plot so they cannot overlap the y-axis labels.
- 202 unit tests. All four items independently verified on the phone (01:05).
- Leftovers (cosmetic / low): the post-workout export re-scans from the export cursor and re-sends the previous
  workout (Health Connect de-duplicates by clientRecordId, so no duplicates appear — tighten the cursor later);
  the in-chart "avg NN" text can be overdrawn by data-point circles near the right edge; `StrideSection` and
  `WatchSection` still use the `remember(flowValue)` pattern that bit the profile section.

## Notifications to the watch (spec for the next feature, 2026-09-05)
Verified format (bridge, 07:39): `C5 00 <type> <total_bytes> <16 bytes UTF-16BE>`, then `C5 <idx> <16 bytes>` per chunk, then
`C5 FD`; the watch acks each chunk with `C5 <idx>` and the end with `C5 FD <type> <total>`. Text = "<app or sender>: <text>".
Type byte per PROTOCOL.md §6 (0 call, 3 SMS, 4 generic app, 5 Facebook, 7 WhatsApp, 19 email, 24 Telegram …).
App side: a `NotificationListenerService` (user grants notification access in Settings), per-app on/off list stored in
DataStore, de-duplication by notification key, rate limiting (one packet burst at a time through the BLE queue, drop
if the link is down), truncation at 127 UTF-16 characters (the total-length field is one byte; a 120-char SMS-type message in 15 chunks was fully acknowledged, ack `C5 FD 03 F0`), no emojis (the watch font lacks them; strip or transliterate).
Settings > Notifications: master switch, "open notification access", per-app toggles for installed apps. Do not send
type 0 (call) from the listener — calls are handled by the classic-Bluetooth HFP link.

## Steps / distance audit (2026-09-05 morning) — verdict before fixes
Question: can this app reproduce the vendor's "0.0 / 0.01 km after a 20-minute run" or wrong steps after a workout?
Method: three code-path analysts, synthetic 20-minute-run tests through the real tracker/controller/encoder, an
adversarial skeptic. Sources cited by file and line in the workflow journal; the synthetic tests live in
`ryzeapp/app/src/test/java/au/buzz/ryzewave/workout/SyntheticRun*.kt` and `data/StepsHourUpsertSummaryTest.kt`.

Daily steps and distance in the app itself: **sound**. Hourly B2 records are upserted by hour, summed per local day,
multiplied by the stride; nothing in the workout path subtracts, resets or replaces steps.

Confirmed defects (being fixed):
1. **Health Connect only:** every record carried `clientRecordVersion = 1`, and Health Connect only applies an upsert
   with a higher version, so the in-progress hour's StepsRecord and today's DistanceRecord froze at whatever the first
   export of that hour/day carried — a "0.01 km all day" symptom visible in Health Connect, never on the dashboard.
2. The watch pushes a realtime `B1` record with total 0 at hh:00:01 for the hour just finished; it overwrote the real
   count until the next `B2 FA` sync repaired it (transient).
3. GPS tracker: the jitter filter is bypassed whenever the phone reports speed ≥ 1 m/s, so every 1 Hz hop including
   noise is summed: +48 % on a synthetic run with independent 2 m noise, +0.6 % with strongly correlated noise. Real
   magnitude depends on the phone; the design must not depend on it.
4. Cosmetic: the workout detail recomputed distance from all accepted points, differing from the controller's figure
   after a pause.
Residual risks named by the skeptic: the hard 20 m accuracy gate drops every fix on a bad-signal run (the exact
vendor symptom — to be relaxed), no run steps have ever been observed so the running stride is untested, and the daily
figure is steps × stride only (a GPS workout's distance is not added to it — by design, same as the vendor app).

### Audit fixes applied (build 4, 2026-09-05 07:53) — 221 unit tests, installed, connected, DB migrated (v2)
1. Health Connect records now carry a monotonic `clientRecordVersion` (the export clock), so re-exports update.
2. A realtime `B1` push never lowers a stored hour; `B2` history stays authoritative (captured 279→0 sequence replayed in a test).
3. Tracker: jitter radius = 1.5 × max(accuracy, anchor accuracy); with a reported speed ≥ 1 m/s and fixes ≤ 5 s
   apart the credited movement is min(speed × dt, hop + accuracy). Synthetic 20-minute run (3600 m truth):
   good GPS + Doppler +47.95 % → **−0.08 %**; no Doppler +5.19 % → **+1.53 %**; 30 s gap +47 % → **+0.04 %**;
   60/120/300 s gaps ≤ +0.08 %; 3 m noise +89 % → −0.08 %. Controller pushes 3.60 km @ 5:33 to the watch.
4. Track points store the tracker's running total (`cumulativeM`) so the detail page matches the summary after a pause.
Still open after this round: the **20 m accuracy gate** (poor-signal runs still produce 0 m) — fixed in build 5, below.

### GPS accuracy gate relaxed (build 5, 2026-09-05 07:59) — 225 unit tests, installed, connected, no crash
`DefaultGpsDistanceTracker.maxAccuracyM` 20 m → **60 m**; jitter radius still 1.5 × max(accuracy, anchor accuracy),
Doppler credit still min(speed × dt, hop + accuracy). Synthetic 20-minute run (3600 m truth), before → after:
25 m + Doppler 0.0 m (all 1200 rejected) → **3597.0 m (−0.08 %)**; 35 m + Doppler 0.0 m → **3597.0 m (−0.08 %)**;
25 m no Doppler 0.0 m → **3595.0 m (−0.14 %, 92 hops of ≥ 37.5 m)**, 35 m no Doppler → 3561.5 m (−1.07 %, 65 hops);
80 m → still 0.0 m (all rejected, by design). With coarser noise (sigma 5 / 10 m instead of 2 m) the Doppler runs
stay at −0.08 % (spike rule drops 238 / 550 fixes, the Doppler credit bridges them); no-Doppler 25 m gives
+1.2 % / +14.4 %, 35 m −0.1 % / +3.8 %. Through the controller the 25 m run pushes 3.60 km @ 5:33 to the watch.
Workout screen GPS label bands: good ≤ 10 m, fair ≤ 20 m, **usable ≤ 60 m** (measures, coarse without Doppler),
poor > 60 m (fixes dropped). Tests: `SyntheticRunGpsTrackerTest` variants B/B2/B3/B4, `SyntheticRunWorkoutControllerTest`
25 m and 80 m, `DefaultGpsDistanceTrackerTest.rejectsPoorAccuracy` (gate at exactly 60 m).

### Notifications to the watch implemented (build 6, 2026-09-05 08:12) — 243 unit tests, verified on the wrist
`Protocol.encNotification(type, text)` / `encNotificationEnd()` (chunks `C5 00 <type> <total> +16 B`, `C5 <idx> +16 B`,
last chunk shorter, then `C5 FD`; text through `NotificationText.sanitize` — emoji / surrogates / controls stripped,
whitespace collapsed — and cut at 127 chars); `NotificationType.forPackage()` maps packages to §6 codes (SMS 3,
WhatsApp 7, Telegram 24, Facebook 5, Messenger 9, Instagram 13, mail packages 19, else 4; never 0).
`WatchApi.sendNotification(type, text)` (additive, default false) is implemented in `WatchApiImpl`: each chunk waits
for its `C5 <idx>` ack (3 s), the end for `C5 FD`, one burst at a time, skipped when disconnected.
`notify/WatchNotificationListener` (NotificationListenerService, manifest-registered) → `NotificationForwarder`
(master switch + allow-list + debug forward-all from `SettingsStore`, `NotificationFilter`: drops ongoing / group
summaries / own package, de-dups key+text within 10 s, text "<title or app label>: <text>", bounded queue, drop
when the watch is down). Settings > Notifications: switch, "Open notification access", status, launcher-app toggles
(loaded off the main thread), "Send test notification". Grant access with
`adb shell cmd notification allow_listener au.buzz.ryzewave/au.buzz.ryzewave.notify.WatchNotificationListener`.
Device log 08:11:59-08:12:43: test "Buzz's Ryze Wave: test" → `c500042c…` 3 chunks acked, `c5fd042c`;
`adb shell 'cmd notification post -S bigtext -t "Test title" tag2 "Hello from adb"'` with forward-all on →
`c500043400540065…` 4 chunks acked, `c5fd0434`; the shell's group summary was dropped by the filter.



## Sport types (2026-09-05)

The watch's sport-mode ids are now known (docs/PROTOCOL.md §6c, `ryzewave/protocol.py` `SPORT_TYPES`): 70 modes, ids
with gaps, 1 = **Outdoor Running** (what every workout so far has used), 0x23 = Outdoor Walking, 9 = Walking,
2 = Cycling, 8 = Hiking, 0x24 = Trail Running, 0x15 = Treadmill, 0x1B = Indoor Running, 4 = Swimming.

Two consequences for the app, both open as of build 6:

1. **Realtime workout packet decoder.** `Packets.kt` only recognises `FD 01 <hr>` as `SportRt`; the watch sends
   `FD <sportType> <hr> …` (14 bytes), verified on the wrist with type 0x23 (`FD 23 5C …`). Any workout started with a
   type other than 1 would get no watch heart rate. Fix: classify by length (14 B) and carry `sportType`; decode the
   other fields (calories16, pace min/sec, steps24, count16, km + km/100) even though the Ryze Wave zeros them during a
   phone-driven workout.
2. **Sport picker and Health Connect mapping.** `WorkoutService` always starts type 1 and `HealthConnectMapping` guesses
   walking vs running from average speed. Plan: a short picker on the Workout screen (Outdoor Running, Outdoor Walking,
   Walking, Cycling, Hiking, Trail Running, Treadmill, Indoor Running, Swimming, Free Training) with the full list behind
   "more"; persist the choice; map the sport id to the Health Connect exercise type (running, walking, biking, hiking,
   running_treadmill, swimming_pool, other) instead of the speed heuristic; show the name in the workouts list and in
   the GPX `<type>`.

Verified on the wrist 2026-09-05 08:23 (bridge, `captures/bridge_20260905_082301.txt`): `FD 11 23 01` and `FD 11 02 01`
each started a workout on the watch and `FD 00 <type> 01` stopped it.

## Notifications: independent verification (build 6)

The verifier agent re-ran the unit tests (243, 0 failures), confirmed the installed APK is the built one (md5 match),
confirmed listener access is granted and the service is live, exercised "Send test notification" (3 chunks, every ack
seen, `C5 FD 04 2C` end ack), posted `Verifier: Ping from verifier` from adb with forward-all on (4 chunks, acked;
with forward-all off the shell package is dropped as expected), then ran "Sync now" and a Health Connect export with
no disconnect and no crash. Buzz saw both texts on the watch.
