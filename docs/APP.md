# "Buzz's Ryze Wave" — Android app spec

Gradle project in `ryzeapp/` (Kotlin 1.9.22, AGP 8.6.0, compileSdk 35, minSdk 26, Compose BOM 2024.02.02,
Room 2.6.1 via KSP, Health Connect client 1.1.0-alpha11, play-services-location 21.1.0). Package `au.buzz.ryzewave`.
Build: `cd ryzeapp && ./gradlew :app:assembleDebug` (SDK in `../tools/android-sdk`, see `local.properties`).
Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### Release build and signing (2026-09-05)
`cd ryzeapp && ./gradlew :app:assembleRelease` writes `app/build/outputs/apk/release/app-release.apk`, signed with the
`release` signing config in `app/build.gradle.kts`. That config is only created when `ryzeapp/keystore.properties`
exists; it reads `storeFile` (relative to `ryzeapp/`), `storePassword`, `keyAlias` and `keyPassword` from it. The
keystore is `ryzeapp/keystore/release.jks` (PKCS12, one RSA-2048 key, alias `ryzewave`, `CN=Buzz's Ryze Wave`,
valid 10,000 days, generated with JDK 17's `keytool`; the passwords are random and live only in `keystore.properties`).
**Both files are git-ignored (`ryzeapp/keystore/`, `ryzeapp/keystore.properties`) and exist only on this laptop: back
them up — losing the keystore means a new app identity (Android will not update an installed release build with an
APK signed by a different key; it has to be uninstalled first).** Without the properties file the release APK is
built unsigned (Gradle warns, `apksigner verify` fails), which is what a fresh clone or CI gets. Check a build with
`tools/android-sdk/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
`isMinifyEnabled` stays false for now (no R8 rules written yet). The debug and release builds share the package name
`au.buzz.ryzewave` but not a signing key, so one cannot be installed over the other: the phone keeps the debug build.

The protocol is fully described in `docs/PROTOCOL.md`; `ryzewave/protocol.py` is the reference codec and
`android/src/au/buzz/ryzebridge/BleService.java` is proven GATT code for this watch (connect, MTU, CCCD, write
queue, notification dispatch). Port, don't reinvent.

## Packages and owners

| Package | Files | Responsibility |
|---|---|---|
| `core` | `Models.kt`, `WatchApi.kt`, `HealthRepository.kt`, `DistanceModel.kt` | contracts (already written, do not change signatures without updating all users) |
| `protocol` | `Protocol.kt`, `Packets.kt` | pure Kotlin port of `ryzewave/protocol.py`: UUIDs, opcodes, encoders `encXxx(): ByteArray`, decoders for B2/F7/34/31+32/E5/FD 01/F7 03/F7 04/A1/A2/38 01, `Features` bitmap. Unit tests in `app/src/test` replaying the hex packets from `docs/PROTOCOL.md` and `tests/test_protocol.py`. No Android imports. |
| `ble` | `WatchGatt.kt`, `WatchService.kt`, `WatchApiImpl.kt` | `WatchGatt`: BluetoothGatt wrapper with a single-outstanding-op queue (Mutex + CompletableDeferred), `connectGatt(TRANSPORT_LE)`, `requestMtu(247)`, CCCD on 33F2/34F2, `write(cmd)`, `request(cmd, opcode)`, `collect(cmd, opcode, isEnd)` (like the Python client), reconnect with backoff, `onConnectionUpdated` logging. `WatchService`: foreground service (type connectedDevice) that owns the link and runs `applySettings` + `syncAll` on connect and every 30 min. `WatchApiImpl` implements `core.WatchApi` and persists through `HealthRepository`. |
| `data` | `Db.kt` (Room database + entities + DAOs), `RoomHealthRepository.kt`, `SettingsStore.kt` (DataStore) | implements `HealthRepository` and `SettingsStore`. Entities: steps_hour(hourStart PK), hr_sample(time, source PK), spo2_sample(time, source PK), sleep_stage(start PK), workout(id auto), track_point(workoutId, time PK), sync_cursor(kind PK), hc_export(clientRecordId PK — the Health Connect export ledger, schema v3). `DailySummary` computed with a query + `StrideModel`. |
| `health` | `HealthConnectExporter.kt`, `HealthConnectExportPlanner.kt`, `HealthConnectMapping.kt` | writes Steps/HeartRate/OxygenSaturation/Distance/Sleep/ExerciseSession records (the session with an `ExerciseRoute` of the workout's accepted GPS fixes) to Health Connect since the export cursor, idempotent via `clientRecordId` (e.g. `hr-<time>`) and an export ledger (`hc_export`: content fingerprint per client id, so only records whose content changed since their last export are written), handles SDK availability (`HealthConnectClient.getSdkStatus`), permission request contract for the UI, `exportNew()`, `exportAll(force)` and `exportSince(cursor)`. The SDK entry points sit behind `HealthConnectBackend` so the export loop is unit-tested against a fake client. Mirrors `~/MyPulseApp/app/src/main/java/com/example/pulseapp/MainActivity.kt` for the permission flow. |
| `workout` | `DefaultStrideModel.kt`, `DefaultGpsDistanceTracker.kt`, `WorkoutService.kt`, `WorkoutController.kt` | stride model (vendor factors as defaults: walk 0.410/0.415 × height, run 0.546/0.505; calibrated override), GPS tracker per `docs/PLAN.md §3b`, foreground location service using FusedLocationProvider at 1 Hz, drives `WatchApi.startWorkout/updateWorkout/stopWorkout`, stores `Workout` + `TrackPoint`s, exposes `StateFlow<WorkoutState>` (elapsed, distance, pace, hr, gps quality), GPX export to the app's files dir. |
| `ui` | `RyzeApp.kt` (NavHost + bottom bar), `DashboardScreen.kt`, `HistoryScreen.kt`, `WorkoutScreen.kt`, `SettingsScreen.kt`, `Charts.kt`, `ViewModels.kt` | Material3 Compose. Dashboard: connection card (state, battery, firmware, connect/sync buttons), today's steps + goal ring, distance (stride model), last HR / SpO2 with time, sleep last night. History: day picker, HR line chart (10-min series + live/workout samples), SpO2 chart, steps bar chart per hour; 7/30-day step totals. Fifth card "Sleep": the night that ended on the selected morning (`repo.sleepForNight`, previous noon .. noon) as a hypnogram — four lanes awake/REM/light/deep top to bottom, a filled block per stage, hourly ticks from bed to rise, tap a block to read it — with the totals line ("7 h 12 m asleep · deep 1:05 · light 4:30 · REM 1:37 · awake 0:20"), bed/rise times and the stage list. Layout maths in `ui/SleepChartData.kt` (pure Kotlin, unit-tested against the night synced on 2026-09-05). The dashboard's sleep card is a one-liner ("Last night: 6 h 19 m" + strip) and is hidden until a night exists. Workout: start/pause/stop, live HR, distance, pace, elapsed, GPS accuracy; list of past workouts with summary. Settings: watch MAC (scan list or manual), profile, sampling (continuous HR, SpO2 auto + interval incl. 5/20 min), stride calibration (auto from last GPS walk + manual), Health Connect toggle + permission button + "export now", find watch. `Charts.kt`: hand-drawn Compose Canvas charts (no chart library; same idea as the `PulseView` Path-on-Canvas in `~/MyPulseApp/.../MainActivity.kt`). |
| `findphone` | `FindPhoneRinger.kt`, `AndroidFindPhoneAlerter.kt` | find-my-phone: `FindPhoneRinger` (Android-free state machine fed with `WatchEvent.FindPhone(start)` from `WatchApi.events`: start rings, `D1 0A 00` / the notification's Stop / 30 s stops, duplicate starts ignored; unit-tested on virtual time) and `AndroidFindPhoneAlerter` (alarm ringtone looped on the alarm stream at full volume — volume restored afterwards —, repeating vibration with alarm attributes, high-priority `find_phone` notification whose Stop action / tap / swipe go to `WatchService.ACTION_FIND_PHONE_STOP`). Wired in `GraphFactory` as `App.graph.findPhone`. |
| root | `App.kt`, `GraphFactory.kt`, `MainActivity.kt` | `GraphFactory.create(app)` wires Room repo, settings, `WatchApiImpl`, exporter. `MainActivity`: runtime permissions (BLUETOOTH_CONNECT/SCAN, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS), Health Connect permission launcher, sets content to `RyzeApp()`. |

### Permissions
Runtime (manifest + `MainActivity`): `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION` (+ `ACCESS_COARSE_LOCATION`),
`POST_NOTIFICATIONS`; foreground-service types `connectedDevice` and `location`; notification-listener access is granted
in system settings. Health Connect (write-only, requested through `PermissionController`, `pm grant`-able on Android 14+):
`android.permission.health.WRITE_STEPS`, `WRITE_HEART_RATE`, `WRITE_OXYGEN_SATURATION`, `WRITE_DISTANCE`, `WRITE_SLEEP`,
`WRITE_EXERCISE` — these six gate the export (`HealthConnectExporter.REQUIRED_PERMISSIONS`) — plus
`WRITE_EXERCISE_ROUTE`, requested with them but optional: without it the exercise sessions are exported without their
GPS route. `tools/app_smoke.sh` grants all of them. The Settings gate ("Permissions granted", "Export now") is computed
against `REQUIRED_PERMISSIONS` only — `ui/HealthPermissionGate.evaluate` (pure, unit-tested) — while the request goes
out for `WRITE_PERMISSIONS`; a declined route permission shows a hint and a "Grant route permission" button instead
of a red status (build 13).
### The charts (at least these five)
1. **Heart rate, one day** — line chart of the 10-minute `F7` series with gaps for missing bins, plus dots for live/workout/auto samples; min/max/avg labels; tap to read a value.
2. **SpO2, one day** — line/dot chart of the 10-minute series and spot tests, y-axis 85-100 %.
3. **Steps per hour, one day** — bar chart (walk vs run stacked), with the daily goal line on the cumulative total.
4. **Daily steps and distance, last 7 / 30 days** — bars for steps with the distance (stride model) as a secondary line; today highlighted.
5. **Workout detail** — HR over elapsed time with pace (from the GPS tracker) as a second series; distance markers every km.
6. **Workout track** (`ui/TrackPlot.kt`, maths in `ui/TrackGeometry.kt`) — the GPS track north-up on a Canvas, scaled to fit with padding: accepted fixes as a polyline, rejected fixes as small dots, green start / red end markers, a numbered ring where each whole km was crossed (from `cumulativeM`, falling back to summed hops for old rows), a 10/50/100/500 m/1 km scale bar that fits 45 % of the width, and a north arrow. No map tiles.
7. **Sleep hypnogram, one night** (`SleepHypnogram` in `Charts.kt`, maths in `ui/SleepChartData.kt`) — four lanes (awake, REM, light, deep, top to bottom), a filled block per watch stage (1 deep / 2 light / 3 REM / 4 awake, unknown codes drawn in the light lane), x axis from bed (first stage start) to rise (last stage end) with ticks at whole hours (every 2 h for nights over 6 h), thin connectors where the lane changes, tap a block for "23:27–23:46 · Light 19 min". Colours match the dashboard strip: deep = primary, light = faded primary, REM = tertiary, awake = error.
All charts: axis ticks with times/dates, dark and light theme aware, `Modifier.fillMaxWidth().height(200.dp)`, empty-state text when there is no data.

**History screen layout (Buzz's request, 2026-09-04):** one shared date navigator at the top (`<  4 Sept 2026  >`),
then FOUR chart cards (a fifth, sleep, added 2026-09-05) stacked in a scrollable column — heart rate, SpO2, steps per hour, daily steps + distance
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
StepsRecord per hour (count, start/end), HeartRateRecord with samples grouped per epoch hour (client id `hr-<hourStart>`), OxygenSaturationRecord per sample, DistanceRecord per day (stride model) and per workout (GPS), SleepSessionRecord per night with stages mapped (1 → deep, 2 → light, 3 → REM, 4 → awake — best current guess, keep a single mapping function), ExerciseSessionRecord per workout (type walking/running by sport type) with an `ExerciseRoute` built from the workout's *accepted* track points (time, lat, lon, altitude, horizontal accuracy; fixes outside the session's start..end and duplicate instants dropped; no route with fewer than 2 points — `HealthConnectMapping.routeLocations` / `exerciseRoute`). The planner reads the points with `HealthRepository.trackPointsOnce` only when `WRITE_EXERCISE_ROUTE` is granted, and the exporter logs `exercise route attached to workout-<id>: <n> locations` at INFO. Every record carries `Metadata(clientRecordId = …, clientRecordVersion = <export clock>)` so a re-export updates instead of duplicating (Health Connect only applies an upsert with a *higher* version).

**Cursor and ledger.** The export cursor (`sync_cursor` kind `hc-export`) is the phone clock at the start of planning; the
planner takes every row whose `updatedAt` is at or after `cursor − 5 min` (the lookback covers clock jitter between a
sync and the export) as a *candidate*, rebuilds the affected hour / day / night completely, then compares each candidate
record's content fingerprint (`HealthConnectMapping.fingerprint`: the data, not the version and not the `now` cap of
the hour / day in progress) with the ledger of what was last written under that client id (`hc_export`). Only records
whose content differs are written; the exporter stores their fingerprints chunk by chunk as Health Connect accepts them.
So a finished workout goes out once, an export with nothing changed writes 0 records, and a changed workout row re-sends
exactly its session and its GPS distance record. The daily DistanceRecord is steps × stride: the ledger also keeps a
marker of the effective strides (`stride`), and when it differs from the current one (calibration, manual edit, reset,
or a height change with derived strides) every day with steps is rebuilt and the days that moved are re-sent;
`GraphFactory` triggers an export when the stride setting changes. `exportAll(force = true)` clears the ledger first
(after the app's data was deleted in Health Connect); "Export now" in Settings is `exportAll()` without force.

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


### Find-my-phone ringer implemented (build 11, 2026-09-05) — 300 unit tests, verified on the Moto without the watch
`WatchEvent.FindPhone` now carries `start` and `WatchApiImpl` emits it for both `D1 0A 01` and `D1 0A 00`
(`WatchApiImplTest.pushesAtAnyTime…` checks both). `findphone/FindPhoneRinger` (state machine, `FindPhoneAlerter`
interface) + `findphone/AndroidFindPhoneAlerter` (ringtone / vibration / notification) as in the package table;
`GraphFactory` collects `watch.events` into `findPhone.onEvent`, and `WatchService.onStartCommand` handles
`ACTION_FIND_PHONE_STOP` from the notification (before its own foreground check, so a tap always silences the
ring). The ring runs in the process the `WatchService` foreground service keeps alive, so it works with the
screen off; the 30 s cap is `FindPhoneRinger.TIMEOUT_MS`. Settings > Watch > "Find watch" (`AB 00 00 00 01 02 07 01`
via `WatchApi.findWatch`) was already there. `src/debug` (debug build only) adds `debug/DebugEventReceiver`, an
exported receiver that injects the event into the same `onEvent` path:
`adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.FIND_PHONE --ez start true`
(`--ez start false` = the watch's `D1 0A 00`). Evidence in `captures/app_findphone_20260905/` (screenshots of the
ringing notification, after Stop, after the 30 s timeout; `logcat.txt`; `steps.txt`). Still unverified: the real
`D1 0A 01` from the wrist end to end (the packet → event mapping is unit-tested on the captured bytes).

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

### Sport types implemented (build 7, 2026-09-05 08:38) — 250 unit tests, 0 failures

- `protocol/SportTypes.kt`: the 70-entry id → name map (copy of `ryzewave/protocol.py` `SPORT_TYPES`), `POPULAR` (the ten
  picker chips), `GPS_SPORTS` {0x01, 0x02, 0x08, 0x09, 0x23, 0x24}, `name(id)` with a "Sport <id>" fallback, and
  `effectiveId(type, avgSpeed)`: type 1 alone is split by average speed (>= 2 m/s Outdoor Running, else Outdoor Walking)
  because every workout recorded before the picker existed used type 1, walks included.
- Decoder: `Packet.SportRt` is now *any* 14-byte `FD` packet (`Protocol.isSportRt`, `SPORT_RT_LEN = 14`; the misleading
  `SPORT_RT_DATA = 0x01` is gone) and carries sportType, hr, calories, pace s/km, steps, count and distance. `FD 11/00
  <type> <ivl>` stay `SportControlEcho`; the 13-byte `FD 22 …` pause echo is now accepted as a pause echo too. Tests
  replay the wrist capture `fd235c00…` (Outdoor Walking, HR 92). `WatchApiImpl` still turns `SportRt.hr > 0` into
  `HrSample(WORKOUT)`. The `D6 10` comment no longer claims an echo (the watch neither echoes nor acks it).
- Workout screen: when idle, a `FlowRow` of `FilterChip`s for the popular ten plus a "More…" `AssistChip` that opens a
  dialog listing all 70 by name. The choice is persisted in DataStore (`workout_sport_type`, default 1,
  `SettingsStore.workoutSportType` / `setWorkoutSportType`) and `WorkoutViewModel.start()` passes it to
  `WorkoutBridge.start` → `WorkoutService.start`; callers that pass nothing still get type 1. The header line reads
  "<sport> · Ready/Running/…", the "Sport" stat shows the name, the distance stat is labelled "Distance (GPS)" (was "Distance (from GPS)", which wrapped), and
  the GPS tracker runs for every sport. Workouts list rows, the detail title/card and the GPX `<type>` show the name.
- Health Connect: exercise type by sport id — 0x01/0x24/0x73 running, 0x1B/0x15 running_treadmill, 0x09/0x23 walking,
  0x02 biking, 0x12 biking_stationary, 0x08 hiking, 0x04 swimming_pool, 0x13 yoga, 0x1C strength_training, 0x61 HIIT,
  0x1F elliptical, 0x29 rowing_machine, everything else other_workout; the speed rule survives only as the type-1
  tie-breaker. Session title = sport name (for type 1, the one the tie-breaker picked); notes end with
  "<name> (sport type N)".
- Verified on the Moto (`tools/app_smoke.sh --no-build 25`, then `tools/app_tap.sh`): the Workout tab shows the chip row
  with Outdoor Running selected (`captures/app_sport_20260905/workout_tab_chips.png`); tapping "Outdoor Walking" flips
  the header to "Outdoor Walking · Ready" and the Sport stat (`workout_tab_outdoor_walking.png`); after a force-stop and
  relaunch the selection is still Outdoor Walking (`workout_tab_after_restart.png`). No crash in `logcat -b crash`.
  Start was not pressed (Buzz was wearing the watch), so a non-type-1 workout end-to-end is still untested from the app.

### Track plot + Health Connect exercise route (build 8, 2026-09-05 09:05) — 261 unit tests, 0 failures
PLAN item 3. `ui/TrackGeometry.kt` (pure: projection scaled to fit with padding, km marks from `cumulativeM` with the
summed-hops fallback, scale bar) + `ui/TrackPlot.kt` (Canvas) + a "Track" card on `WorkoutDetailScreen`
(`WorkoutDetail.points`). `HealthConnectMapping.routeLocations / exerciseRoute / exerciseSessionRecords(tracks)` attach
an `ExerciseRoute` of the accepted fixes (>= 2 points, inside start..end — Health Connect wants route times strictly
before the session end); `HealthRepository.trackPointsOnce` (default = `trackPoints().first()`, Room one-shot query)
feeds the planner, which reads tracks only when `WRITE_EXERCISE_ROUTE` is granted (`Plan.routes`,
`ExportCounts.routePoints`); the exporter logs `exercise route attached to workout-<id>: <n> locations`. The route
permission is requested with the others but does not gate the export (`REQUIRED_PERMISSIONS` vs `WRITE_PERMISSIONS`).
Tests: `TrackGeometryTest` (square loop, km marks, empty/single, scale bar, outliers, the real walk),
`HealthConnectMappingTest` (route filtering, < 2 points, the real walk = 86 locations), `HealthConnectExportPlannerTest`
(route loaded from the repository, skipped without the permission); the real track lives in
`app/src/test/resources/pixel_outdoor_walk_20260905_track.csv` (`workout/RealTrack.kt`). Verified on the Moto with the
Pixel's database (watch absent): `captures/app_track_20260905/` — plot screenshots, logcat
`exercise route attached to workout-1: 86 locations` / `exported 230 records (... workouts=1, routePoints=86) ... failed 0`,
Health Connect's Exercise list shows "Exercise map route available" and the entry a route thumbnail.

### Health Connect hygiene (build 9, 2026-09-05 09:30) — 283 unit tests, 0 failures
PLAN item 9. Room schema v3 (`Db.MIGRATION_2_3`) adds the export ledger `hc_export(clientRecordId PK, fingerprint,
exportedAt)`; `HealthRepository.exportedFingerprints / markExported / clearExported` (defaults keep the test fakes
compiling). `HealthConnectMapping.fingerprint(record, now)` is a 64-bit FNV-1a of the record's data (not the version,
not the `now` cap of the hour / day in progress; the route and the notes are part of a session's content), and
`strideFingerprint` hashes the effective walk / run stride. The planner compares every candidate with the ledger and
plans only what differs (`Plan.fingerprints`, `Plan.markers`, `Plan.unchanged`); when the stored `stride` marker
differs from the current stride every day with steps is rebuilt. The exporter stores fingerprints chunk by chunk as
Health Connect accepts them, the markers after a successful run (also on "nothing to export"), and logs
`exported N records (...) from F, U unchanged skipped, cursor -> C, failed M` or `nothing to export: U candidate
records unchanged since their last export`. `HealthConnectBackend` wraps `getSdkStatus` / `getOrCreate` so
`HealthConnectExporterTest` runs the real export loop against `FakeHealthConnectClient` (upsert by client id, only a
higher `clientRecordVersion` counts) with read-back: first export = exactly the planned ids; second export = 0 writes;
changed workout = same ten ids, only `workout-1` / `wdist-1` at the new version; `exportAll(force = true)` rewrites all
at a higher version without duplicates; stride change re-sends the daily distances only; rejected / transient failures.
`GraphFactory` also exports on a stride change (`settings.stride.drop(1)`). Known, deliberate extra write: an hour whose
count did not change after its last mid-hour export is sent once more when it closes (its end time moves from the
export time to the hour end). Verified on the Moto with the build-8 database (watch absent): `captures/hc_ledger_20260905/`
— first export after the migration 230 records, second export `nothing to export: 230 candidate records unchanged`,
walk stride 0.8 → `export after stride change: distance=3, inserted=3`, "Use defaults" → 3 again; `hc_export` holds 231
rows, `user_version = 3`. Unit tests need `testOptions.unitTests.isReturnDefaultValues = true` (the exporter calls
`android.util.Log`).

### Ship-shape polish (build 12, 2026-09-05) — 307 unit tests, 0 failures; watch absent
PLAN item 10, the parts that need no watch.
- **Settings edit state.** `ui/SettingsScreen.kt` `EditGuard` / `rememberEditGuard` (KDoc there): "local edits win until
  saved". The Watch (MAC) and Stride sections used `remember(flowValue) { mutableStateOf(…) }`, which re-created the
  field state whenever the settings flow re-emitted (another setting saved, the service re-applying settings, DataStore
  rewriting its file) and so reverted what was being typed — the profile bug of build 1. All three text sections now
  share the guard: fields are seeded once; while nothing is edited the stored value re-seeds them on change
  (`LaunchedEffect(stored)`); `touch()` on every keystroke, `saved()` on Save (the flow's echo re-seeds with the same
  or normalised values), `discard()` when an outside action replaces the value (scan result tapped, "Use defaults",
  "Calibrate from last GPS workout") so the fields show it at once even if the flow does not re-emit. Verified by
  reading (the watch is out of range); the Save buttons still compare the parsed fields with the stored value.
- **"avg" / "goal" label overdraw.** `ui/ChartData.kt` `Box` + `LabelLayout.pick` (pure Kotlin, `LabelLayoutTest`):
  a label that annotates a horizontal reference line is offered ten candidate boxes (right edge, left edge, centre,
  quarter points; above then below the line) and takes the first one that covers no data — dots within their radius,
  polyline segments (Liang–Barsky clip test), bars — with a ×100 penalty for overlapping the min / max point labels;
  otherwise the least-covered one. It is drawn on a translucent card-coloured pill so a line underneath cannot cut
  through the glyphs. Used by the HR chart's "avg NN" (`drawLineLabel` in `Charts.kt`) and the steps chart's "goal".
  Min / max point labels are also inset 4 dp from the plot's left edge so SpO2's "99" no longer touches the "100"
  axis tick. Before / after on the Moto: `captures/app_polish_20260905/hr_card_before_4sep.png` ("avg 81" on top of
  the 22:00–23:50 dots) vs `hr_card_after_4sep.png`.
- **Launcher icon.** `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive; minSdk 26 so no legacy PNGs) with
  `drawable/ic_launcher_bg.xml` (deep blue radial gradient), `drawable/ic_launcher_fg.xml` (white rounded watch case
  with strap stubs and crown, coral heart-rate wave, all inside the 66 dp safe circle) and `drawable/ic_launcher_mono.xml`
  (same shapes in one colour for Android 13+ themed icons). `drawable/ic_watch.xml` stays the notification icon.
  Screenshots: `captures/app_polish_20260905/app_drawer.png` (drawer tile) and `app_info_icon.png` (App info, larger).
- **Signed release build.** See "Release build and signing" at the top. `assembleRelease` also runs `lintVitalRelease`,
  which failed on `InvalidFragmentVersionForActivityResult` (play-services-base drags in `androidx.fragment:fragment:1.0.0`);
  a dependency constraint lifts it to 1.6.2. `app-release.apk` verified with `apksigner verify --print-certs`
  (APK Signature Scheme v2 — AGP's default for minSdk 26 —, `CN=Buzz's Ryze Wave`, RSA 2048; output in `captures/app_polish_20260905/apksigner_release.txt`); the debug build stays installed on the Moto (same package name, different key).
- Small fixes on the way: Settings > Watch buttons in a `FlowRow` ("Find watch" was squashed onto two lines in a
  `Row`); the Watch Save button now saves the trimmed text it compares against; Workout stat label "Distance (GPS)".

### Review fixes (build 13, 2026-09-05) — 324 unit tests, 0 failures; watch absent
Nine review findings, all fixed; verified on the Moto without the watch (`captures/app_fixes_20260905/steps.md` lists
the evidence per item: the gate with the route permission revoked and re-granted, three lossless lower-case typing
trials in the MAC field, ring 1 / ring 2 from the DUMP-guarded receiver, "Outdoor Walking" in the workout list,
two stride exports from a height change, and a `user_version = 4` database that made the launch throw
`A migration from 4 to 3 was required but not found` instead of emptying the tables). Not done: a negative test of
the DUMP guard from a third-party uid (needs another app; `run-as` sends as the app's own uid, which Android
always admits).
- **Settings permission gate = exporter gate.** `HealthConnectPermissionHost(request, required)` requests the seven
  `WRITE_*` permissions but computes `granted` against `HealthConnectExporter.REQUIRED_PERMISSIONS` (six) through the
  pure `HealthPermissionGate.evaluate(have, required, optional)` (`ui/HealthPermissions.kt`, `HealthPermissionGateTest`);
  `optionalMissing` drives the hint "Permissions granted (GPS routes not allowed: workouts are exported without their
  track)" and turns the button into "Grant route permission". The exporter's instance properties now mean what they
  say: `requiredPermissions` = the six, `writePermissions` = the seven, `optionalPermissions` = the route;
  `HealthConnectExporter.routeGranted(result)` joins `granted(result)`.
- **MAC field.** `WatchSection` keeps the raw text (no `uppercase()` inside `onValueChange`, which made Gboard drop
  keystrokes under its composition); `KeyboardCapitalization.Characters` asks the keyboard for capitals and
  `MacText.normalise` (`ui/Format.kt`, `MacTextTest`) upper-cases and trims when the value is compared and saved.
- **Find-my-phone ringer.** Each ring has a `generation`; the timeout job stops only its own ring
  (`stop(reason, ringGeneration)`), so a stale timer cannot silence a later ring. The alerter calls run outside the
  state lock (serialised by a second lock), so a Stop tap on the main thread flips the state at once instead of
  waiting behind `MediaPlayer.prepare()`; a stop issued from inside `startAlarm` is honoured (`FindPhoneRingerTest`).
- **Debug receiver.** `DebugEventReceiver` (debug builds) is still exported for adb but guarded with
  `android:permission="android.permission.DUMP"`: the shell holds it, ordinary apps cannot get it.
- **Planner: sessions read their track once.** With every exercise session the exporter stores two companion
  ledger entries (`workout-<id>.base` = fingerprint of the session without its route, `workout-<id>.route` = number
  of route locations, or -1 when written without the route permission). A candidate workout whose route-less
  fingerprint and route state match them is unchanged without `trackPointsOnce` being called
  (`Plan.sessionsSkipped`, logged as "N sessions unchanged without reading their tracks"); a session exported before
  the companions existed is read once more and gets them through `Plan.markers`.
- **Planner: missing stride marker.** On an incremental run (cursor > 0) a missing `stride` marker now counts as a
  stride change (exports ran before the ledger existed — the v2 → v3 upgrade — or the marker write failed): every day
  with steps is rebuilt and the fingerprints decide what is sent. A full run never needed the marker.
- **Export toast.** `exportMessage(ExportResult)` (`ui/ViewModels.kt`): a no-op "Export now" reads "Nothing new to
  export: N records already in Health Connect" instead of "Exported 0 records"; the Settings "Last export" line says
  "nothing new (N records unchanged)".
- **Room.** `fallbackToDestructiveMigration()` is gone from `Db.get`: a schema version without a migration (a
  downgrade, a forgotten `MIGRATION_n_m`) throws at the first query instead of silently emptying every table.
- **Workout titles.** The workouts list, the detail title and the detail card name a stored workout by
  `HealthConnectMapping.effectiveSportType` (type 1 split by speed), the same name the Health Connect session and the
  GPX carry — the 155 m walk is "Outdoor Walking" everywhere now.
- **Profile height.** `GraphFactory` exports on a change of the *effective* stride: `combine(settings.profile,
  settings.stride)` through the stride model, so a height change with derived strides re-exports the daily distances
  at once (a height change with manual strides changes nothing and exports nothing).
- Docs: the Health Connect mapping paragraph now says HeartRateRecord samples are grouped per epoch hour.

## Notifications: independent verification (build 6)

The verifier agent re-ran the unit tests (243, 0 failures), confirmed the installed APK is the built one (md5 match),
confirmed listener access is granted and the service is live, exercised "Send test notification" (3 chunks, every ack
seen, `C5 FD 04 2C` end ack), posted `Verifier: Ping from verifier` from adb with forward-all on (4 chunks, acked;
with forward-all off the shell package is dropped as expected), then ran "Sync now" and a Health Connect export with
no disconnect and no crash. Buzz saw both texts on the watch.


## Tracker finding from the first real walk (2026-09-05, build 8 track plot)

The track plot of the Pixel walk shows the first fix (52 m accuracy, reported speed 0) sitting about 45 m away from
the loop the walker actually made, joined to it by one long accepted hop. The rules credited that hop with
`speed × dt` of the *next accepted* fix (1.17 m/s × 33 s ≈ 38 m) although the Doppler speeds reported during those
33 s were 0–0.46 m/s (≈ 10–15 m of real movement). Two refinements, both watch-independent and testable with the
real 146-fix fixture (`app/src/test/resources/pixel_outdoor_walk_20260905_track.csv`):

1. Integrate the Doppler credit per fix (sum of each fix's `speed × its dt` since the last accepted fix, still capped by
   hop + accuracy) instead of the last speed times the whole interval.
2. Do not anchor on a poor first fix: wait for accuracy ≤ 20 m (or replace the anchor without credit when a much
   better fix arrives inside the poor fix's radius).

Expected effect on the walk: 155 m → roughly 130–140 m; the truth is unknown until Buzz gives the route length.
Implemented in "Tracker refinements (build 14)" below: the replay gives **130.7 m** after the second review's rework (136.4 m with the build-14 rules as installed, 133.7 m with the first draft's unrestricted rule 1c; the section was first numbered build 9, which is "Health Connect hygiene").

### Tracker refinements (build 14, 2026-09-05, installed on the Moto; reworked after the second review — source tree only, not yet built or installed) — 356 unit tests, 0 failures (fresh `--rerun-tasks` run)
`DefaultGpsDistanceTracker` (KDoc rules 1b, 1c, 3, 4 and 4b) now does what the finding above asked for, and what
the second review (an adversarial old-vs-new replay against the build-8 tracker, see "The second review" below) found
missing:
1. **Doppler credit integrated per fix.** At an accepted fix with reported speed ≥ 1 m/s the credit is the sum of
   `speed × own dt` over every fix since the last accepted fix that passed the 60 m accuracy gate — including the
   ones the jitter rule rejected (their position was doubted, not their Doppler speed), but *not* the ones the spike
   rule rejected (a fix whose position is impossible is not believed about its speed either: the next credible fix's
   dt spans it), and with a fix that failed the gate simply skipped. Still capped by hop + max(accuracy, anchor
   accuracy), and still only while consecutive fixes are ≤ 5 s apart (a larger gap breaks the chain → hop, as
   before). The old rule credited the accepted fix's speed × the whole interval; speeds 0, 0, 0.4, 1.2 m/s over 4 s
   now credit 1.6 m instead of 4.8 m. **Sub-threshold speeds (0.4–0.99 m/s) are believed only as far as the position
   confirms them** (rule 4b): when the hop since the integral started is at least as long as the sub-threshold part of
   the integral the whole integral is credited, otherwise the integral is scaled by hop / sub-threshold part (never
   below the ≥ 1 m/s part, which is trusted). On the real walk the slow stretches advanced the position and are kept;
   a receiver saying 0.7 m/s while its owner stands still, whose 42 m claim per 60 s stop is met by a hop of a few
   metres, credits about the hop. Build 14 as installed relied on the plain cap for this, which allows one accuracy
   radius per stop (+33 % on a walk with ten stops at 25 m accuracy — see the review).
2. **First anchor quality.** While nothing has been accepted, a fix worse than `firstFixAccuracyM` = 20 m is held
   as a candidate (returned as rejected, counted in `deferredFirstFixCount`) and the tracker waits for a ≤ 20 m fix.
   If none arrives within `firstFixWaitS` = 15 s of the first held fix, the best fix seen so far becomes the anchor
   (the current fix if it is the best; otherwise the earlier candidate, and the current fix is judged against it).
   The movement *during* the wait is real — only where it started is doubted — so the Doppler integral runs from the
   first held fix (a better candidate mid-wait does not restart it) and is credited, capped by the hop from the first
   held fix + the larger accuracy and by rule 4b, on **every** way out of the wait: a ≤ 20 m fix arriving, the
   current fix being the best at expiry, or the candidate anchoring at expiry (the credit then lands at the first
   accepted fix). Build 14 as installed credited it on the last exit only and lost up to (15 s + one interval) ×
   speed at every start and every resume after a pause — 45 m at 3 m/s, 18 m at 1.2 m/s (the "held seconds are not
   lost" claim in the earlier version of this section was true for one exit of four). The wait can also never end in
   a rejection: when the current fix is a spike from the candidate (a 6 m/s cyclist with no Doppler speed is 90 m
   away after 15 s, more than 2.5 × 15 + two radii) the current fix becomes the anchor instead, crediting nothing
   beyond the capped integral, so the ride is measured from there rather than deadlocked (every later fix was farther
   still: 0.0 m after 20 minutes). On the synthetic 25/35 m runs this holds the first 15 fixes and loses nothing; on
   the real walk the 52 m first fix is skipped and the 17 m fix at +9 s starts the walk, so the 23.8 m hop back to
   the loop is gone.
3. **Poor starting anchor replaced** (`reAnchoredCount`, rule 1c): while the anchor is a *starting* anchor worse than
   20 m — the first fix, the best fix of an expired wait, or the re-anchor after `markGap()` — from which nothing
   has been credited yet, a fix ≥ 3× better that lands inside its accuracy radius replaces it. The anchor's
   position is not trusted but the receiver's speed still is: the Doppler integral since the anchor is credited
   (capped as in rules 4 and 4b, only while the chain is intact and time has passed), so a stationary receiver
   credits nothing and a walker keeps the metres it walked; with no receiver speed at that fix the reported speed is
   the credited distance / time (rule 6, which the build-14 branch skipped). *The first draft of this rule fired on any
   anchor worse than 20 m at any time* and the first review caught it: a phone's accuracy steps between bands every
   few seconds, and each step down to a ≥ 3× better fix inside the poor anchor's radius replaced the anchor and
   dropped the fix interval since it — real movement. Measured on the 20-minute 3 m/s Doppler run: 25 m with every
   10th fix at 8 m 3597 → 3240 m (−10.0 %, 119 re-anchors), 30 / 9 m alternating 3597 → 1800 m (−50.0 %, 599
   re-anchors), a 1.2 m/s walk with 25 ↔ 8 m bands every 5 s 1438.8 → 1290.0 m (−10.3 %). Once a hop has been
   credited the anchor is simply the last accepted fix of a run in progress, so it is kept: a poor-but-credited
   anchor is measured on from by the jitter radius of rule 2 or the Doppler credit of rule 4, exactly as in build
   8. Rule 1c is still limited to anchors worse than 20 m for the reason the first draft gave (a 9 → 3 m step mid-walk
   must not re-anchor either). On the real walk the first draft fired once (21 m anchor at +18 s → 6.4 m fix at
   +24 s, 2.5 m apart — that anchor *had* credited a hop); the narrowed rule fires 0 times there and the 21 m anchor
   is kept (+2.7 m).
4. **Plausibility (rule 3) knows the speed the tracker has measured.** The spike bound is now
   max(reported speed, 2.5 m/s, *the speed of the last accepted fix*) × dt + both radii. Without the last term a
   receiver with no Doppler speed deadlocked at running/cycling pace: at 5 m accuracy a 6 m/s hop is 12 m over 2 s
   against an allowance of 2.5 × 2 + 10 = 15 m, position noise rejects one hop in seven, and after a rejection dt
   grows while the allowance grows 2.5 m per second and the rider 6 — every later fix was a "spike" (build 8 and
   build 14 alike: 851 of 7194 m at 6 m/s, 54 of 8393 m at 7 m/s). And spikes cannot go on for ever (the escape
   hatch, `escapedSpikeCount`): when fixes have been rejected as spikes for more than 10 s since the first of them,
   or three consecutive spikes were each ≤ 12 m/s from the *previous raw fix* (the fixes agree with each other, only
   the anchor is stale), the current fix becomes the anchor with no hop credited — only the receiver's own capped
   integral, nothing for a speed-less receiver — so a lost segment costs that segment instead of the rest of the
   workout.

Real walk (`RealTrackTrackerTest`, replays the 146-fix fixture; the walk was paused at +114 s and resumed at +118 s —
the three missing fixes and the +117.8 s fix stored as accepted with an unchanged total — so the replay calls
`markGap()` there as the controller did; the old rules reproduce the stored 155.2 m exactly):
**155.2 m → 130.7 m** (85 accepted, 58 jitter, 2 accuracy, 1 deferred, 0 re-anchored, 0 spikes; 133.4 m without
the markGap; build 14 as installed gave 136.4 / 140.9 m, the first draft with the unrestricted rule 1c 133.7 /
138.2 m). Of the −24.5 m, −23.8 m is the dropped first hop, +2.3 m the per-fix integral replacing last-speed ×
interval, +2.7 m the kept 21 m anchor at +18 s, and −5.7 m rule 4b on the slow stretches whose position did not
fully confirm the sub-threshold speeds. The truth is still unknown; the test asserts 120–150 m as a regression
band, not a truth check. The stored figure on the Pixel/Moto does not change (distance is stored, not recomputed).

Synthetic 20-minute run (3600 m truth), before → after — every asserted bound unchanged (build 8 → build 14; the
rework changes none of these rows):

| variant | accepted before → after | distance before → after |
|---|---|---|
| A 6 m + Doppler (within 2 %) | 1200 → 1200 | 3597.0 m (−0.08 %) → 3597.0 m (−0.08 %) |
| B 25 m + Doppler (within 5 %) | 1200 → 1185 (15 deferred) | 3597.0 m (−0.08 %) → 3597.0 m (−0.08 %) |
| B2 35 m + Doppler (within 5 %) | 1200 → 1185 (15 deferred) | 3597.0 m (−0.08 %) → 3597.0 m (−0.08 %) |
| B3 25 m no Doppler (within 15 %) | 92 → 91 | 3595.0 m (−0.14 %) → 3595.0 m (−0.14 %) |
| B3' 35 m no Doppler (reported) | 65 → 64 | 3561.5 m (−1.07 %) → 3561.5 m (−1.07 %) |
| B4 80 m (rejected) | 0 → 0 | 0.0 m → 0.0 m |
| C 8 m no Doppler (within 5 %) | 259 → 259 | 3655.2 m (+1.53 %) → 3655.2 m (+1.53 %) |
| D 30 s gap (within 5 %) | 1170 → 1170 | 3601.6 m (+0.04 %) → 3601.6 m (+0.04 %) |
| D' 60 / 120 / 300 s gaps | unchanged | 3601.3 / 3602.8 / 3601.2 m → same |
| A jitter sigma 0 / 0.5 / 1 / 2 / 3 m, phi 0.9 / 0.98, seeds 1 / 7 / 99 | unchanged | 3597.0 m (−0.08 %) → same |
| B / B2 sigma 5 m (238 spikes) | 962 → 950 | 3597.0 m → 3597.0 m |
| B / B2 sigma 10 m (550 spikes) | 650 → 642 | 3597.0 m → 3597.0 m |
| B3 25 m no Doppler sigma 5 m (reported) | 90 → 89 | 3643.4 m (+1.21 %) → 3644.9 m (+1.25 %) |
| B3 25 m no Doppler sigma 10 m (reported) | 98 → 97 | 4119.6 m (+14.43 %) → 4150.4 m (+15.29 %) |
| B3' 35 m no Doppler sigma 5 / 10 m | 65 → 64 / 64 → 63 | 3597.1 / 3737.3 m → same |
| controller: 6 m run, 30 s gap, 25 m run, 80 m run | 1200 / 1170 / 1200→1185 / 0 accepted | 3597.0 / 3601.6 / 3597.0 (3.60 km @ 5:33 pushed) / 0.0 m → same |

**Accuracy stepping between bands** (`SyntheticRun.fixes(accuracyAt = …)`, the case the first draft of rule 1c got
wrong; build 8 → first draft → build 14, `captures/app_tracker14_20260905/old_vs_new.txt`):

| variant | build 8 | first draft (unrestricted 1c) | build 14 |
|---|---|---|---|
| E 25 m, every 10th fix 8 m, Doppler (within 1 %) | 3597.0 m (−0.08 %) | 3240.0 m (−10.00 %, 119 re-anchors) | 3597.0 m (−0.08 %, 0) |
| E 25 m, every 10th fix 8 m from t=5 s, Doppler (within 1 %) | 3597.0 m | 3225.0 m (−10.42 %, 119) | 3582.0 m (−0.50 %: the 5 held fixes before the first 8 m fix, rule 1b) — **3597.0 m after the rework** (the wait credits them) |
| E 30 / 9 m alternating, Doppler (within 1 %) | 3597.0 m | 1800.0 m (−50.00 %, 599) | 3597.0 m (−0.08 %) |
| E 9 / 30 m alternating, Doppler | 3597.0 m | 1797.0 m (−50.08 %, 599) | 3594.0 m (−0.17 %) |
| E2 1.2 m/s walk, 25 ↔ 8 m every 5 s, Doppler (within 1 % of 1438.8 m) | 1438.8 m | 1290.0 m (−10.34 %, 119) | 1432.8 m (−0.42 %) — **1438.8 m after the rework** |
| E2 1.2 m/s walk, 8 ↔ 25 m every 5 s, Doppler | 1438.8 m | 1296.0 m (−9.92 %, 119) | 1438.8 m (0.00 %) |
| E3 25 m, every 10th fix 8 m, **no Doppler** (reported, within 5 %) | 3581.5 m (−0.52 %) | 3581.5 m | 3581.5 m (−0.52 %) |
| E3 25 m, every 10th fix 8 m from t=5 s, no Doppler | 3595.5 m (−0.13 %) | 3583.8 m | 3583.8 m (−0.45 %) |
| E3 30 / 9 m alternating, no Doppler (reported) | 3631.3 m (+0.87 %) | 3631.3 m | 3631.3 m (+0.87 %) |
| E3 25 ↔ 8 m every 5 s, no Doppler | 3632.1 m (+0.89 %) | 3617.8 m | 3617.8 m (+0.49 %) |
| E3 8 ↔ 25 m every 5 s, no Doppler | 3611.7 m (+0.33 %) | 3611.7 m | 3611.7 m (+0.33 %) |
| E3 6 / 9 / 3 m bands every 4 s, no Doppler | 3794.3 m (+5.40 %) | 3794.3 m | 3794.3 m (+5.40 %, hop-mode over-count at small radii with 2 m independent noise — unchanged since build 4) — 3800.3 m (+5.56 %) after the rework: the last-accepted-speed bound admits 4 hops build 8 rejected as spikes |
| E3 1.2 m/s walk, 25 ↔ 8 m every 5 s, no Doppler | 1455.9 m (+1.19 %) | 1454.4 m | 1454.4 m (+1.08 %) |

So **hop mode (no Doppler) is unchanged only at constant accuracy**: when the accuracy varies, the first anchor
rule (1b) shifts the hop phase and the figures differ from build 8 by up to 0.4 % (14 m on 3600 m), all within
1.1 % of the truth. The first draft's rule 1c did not fire in hop mode on these runs (every 8 m fix arrived outside
the 25 m radius of the previous 37.5 m hop's anchor, or inside the jitter radius of a good one), so its damage was
confined to the Doppler runs — where it is now gone.

#### The second review: four regressions against build 8, found by an adversarial replay and fixed (2026-09-05)

A scratch harness replayed the build-8 tracker (git HEAD c7871c0, copied into the test source set) and the working
tree side by side on truth-known runs; the four findings and the fixed numbers (truth / build 8 / build 14 as
installed / reworked; the regression tests are `firstAnchorWaitCreditsTheMovementOnEveryExit`,
`speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock`, `creepingReceiverCostsAboutTheHopNotTheAccuracyRadius`,
`spikesReportingABogusSpeedAreNotIntegrated` and the rule 1b / rule 3 / rule 4b cases in
`DefaultGpsDistanceTrackerTest`):

1. **The wait dropped its integral on three exits of four** (a ≤ 20 m fix arriving, a better candidate mid-wait, the
   current fix best at expiry): up to (15 s + one interval) × speed per start and per resume — the perverse case being
   a *better* fix at expiry losing 45 m while an equal one lost nothing. Reworked: every exit credits the integral
   since the first held fix, and each of these 3 m/s runs (3597.0 m truth: 25 m then a 15 m fix at t=10; 30 m with
   a better 25 m candidate at t=5 or t=14; 30 m for 15 s then 25 m; 25 m constant; a pause/resume at t=600 with and
   without a 15 m fix 10 s later; a 50 m first fix) gives **3597.0 m** (pause/resume 3594.0 m) — equal to build 8 —
   and the 1.2 m/s versions 1438.8 m (pause/resume 1437.6 m).
2. **Deadlock for a speed-less receiver at running/cycling speed.** Starting in poor accuracy, the wait pushed the
   first judged hop to dt = 15 s where rule 3 allowed 2.5 × 15 + two radii; rejected once, every later fix was
   farther still: **0.0 m** over 20 minutes at 6–7 m/s with 21–30 m fixes (build 8, which anchored at once, was within
   0.4 %). And build 8 itself deadlocked at 5 m accuracy (851 of 7194 m at 6 m/s, 54 of 8393 m at 7 m/s: one noisy
   hop in seven rejected, then the allowance never caught the rider). Reworked (no Doppler, 20 minutes):

   | run | build 8 | reworked |
   |---|---|---|
   | 6 m/s, 5 m | 120.6 m (−98.3 %); 851.3 m with 40 m for the first 20 s | 7417.2 m (+3.1 %); 7413.0 m |
   | 6 m/s, 21 / 25 / 30 m | 7199.9 / 7174.2 / 7196.2 m (+0.1 / −0.3 / +0.0 %) | 7106.8 / 7083.4 / 7196.1 m (−1.2 / −1.5 / +0.0 %) |
   | 6 m/s, 21 / 25 / 30 m, 40 m for the first 20 s | 7197.4 / 7176.1 / 7192.5 m | 7197.5 / 7176.2 / 7196.2 m |
   | 7 m/s, 5 m | 53.7 m (−99.4 %) | 8504.1 m (+1.3 %) |
   | 7 m/s, 21 / 25 / 30 m | 8392.3 / 8398.7 / 8362.8 m (−0.0 / +0.1 / −0.4 %) | 8286.5 / 8292.3 / 8257.1 m (−1.3 / −1.2 / −1.6 %) |
   | 7 m/s, 21 / 25 / 30 m, 40 m for the first 20 s | 8392.2 / 8397.5 / 8364.8 m | 8388.4 / 8398.4 / 8362.2 m |
   | 6 / 7 m/s, 5 m, Kalman-like noise (phi 0.9) | 1016.7 / 90.5 m | 7222.7 / 8424.8 m (+0.4 / +0.4 %) |
   | 6 / 7 m/s with Doppler, 5 or 25 m | within 0.1 % | same |

   The −1.2 to −1.6 % at constant 21–30 m is the one wait's hop (90–105 m): at expiry the current fix is a spike
   from the candidate, so it anchors and a speed-less receiver has nothing to credit for the 15 s (build 8 anchored
   on the first fix and measured them; the 40 m-start rows, where the candidate's radius makes the hop plausible, are
   equal to build 8). The +3.1 % at 6 m/s and 5 m is hop mode's own over-count of 1–2 s hops with 2 m independent
   noise, not the rework: build 8's 4 m/s run at 5 m, which never deadlocked, is +4.95 % with the same noise, and
   with Kalman-like noise the reworked runs are within 0.4 %. The test bound is 3 % at 21–30 m and 3.5 % at 5 m.
3. **Creeping receiver.** Integrating sub-threshold speeds (0.7 m/s reported while standing) cost up to one accuracy
   radius per stop under the plain cap: a 1.2 m/s walk with ten 60 s stops (718.8 m truth) came out **+33.5 %** at
   25 m accuracy (959.6 m) and +52.6 % at 50 m against build 8's +2.2 % (734.6 m: it fell back to the hop across the
   long interval), +19.7 % (860.3 m) at 5 m against +13.7 % (817.5 m). Reworked with rule 4b:

   | accuracy | standing receiver reports 0 | 0.5 / 0.7 / 0.9 / 0.95 m/s | build 8 (any) |
   |---|---|---|---|
   | 5 m | 802.5 m (+11.6 %) | 819.8 / 819.6 / 819.2 / 819.1 m (+14.0 %) | 817.5 m (+13.7 %) |
   | 25 m | 718.8 m (0.0 %) | 735.7 / 735.4 / 735.2 / 735.2 m (+2.3 %) | 734.6 m (+2.2 %) |
   | 50 m | 718.8 m (0.0 %) | 735.7 / 735.4 / 735.2 / 735.2 m (+2.3 %) | 734.6 m (+2.2 %) |

   A creeping receiver now costs about the hop (1.7 m per stop over one reporting 0, the hop's scatter), the same as
   build 8 charged, and a receiver reporting 0 while standing is exact at 25 / 50 m (the walking second's Doppler,
   not the scattered hop). The 5 m baseline is hop-mode jitter (±2 m scatter sometimes leaves the 7.5 m radius, ≈ 8 m
   per stop), unchanged since build 4. The review's own suggestion — cap the credit at the ≥ 1 m/s part + the hop —
   double-counts the walking second's displacement and came out 1.5 % of the truth over build 8 at every accuracy;
   a cap of max(≥ 1 m/s part, hop) matched build 8 on the creeper but lost 7 % on an honest 0.8 m/s walker with an
   occasional 1.05 m/s fix (whose integral ≈ hop, so noise in the hop only ever cuts the credit) and pulled the real
   walk to 123 m; the proportional rule keeps the creeper at +0.1–0.3 % over build 8, that walker at −2.4 % (build 8
   +5.0 %) and the real walk at 130.7 m.
4. **Rule 4 integrated the speed of fixes rejected as spikes.** Every 30th fix 25 m off the path reporting 10 m/s
   added its 10 m: **+20 %** over the 3 m/s run (build 8 0 %). Reworked: a spike contributes nothing and the next
   credible fix's dt spans it: **3597.0 m (−0.08 %)** with all 39 spikes rejected, at 6 and 8 m accuracy, with the
   spikes reporting 3 m/s, with every 10th fix 40 m off (119 spikes), and on the 1.2 m/s walk (1438.8 m).

Every scenario of the earlier old-vs-new table is within 1 % of build 8 or closer to the truth after the rework (the
two rows that moved are marked in the table above). Known trade-offs of the per-fix integral, reported by
`perFixIntegralTradeOffsReported` with loose bounds: a Doppler dropout (one fix in 20 reporting 0.3 m/s on a 3 m/s
run) costs the missing second's speed, −4.5 % (build 8, which credited the last speed over the whole interval,
−0.1 % — the same rule that turned 33 s of 0–0.5 m/s fixes on the real walk into 38 m); a 1.0 m/s walker whose
reported speed straddles the threshold (0.9 / 1.1 alternating, 5 m) is measured mostly in hop mode, +9.3 % (build 8
+19.8 %).

Pause/resume (`WorkoutControllerTest`): unchanged — the re-anchor after `markGap()` at 5 m accuracy is immediate;
a resume in poor accuracy goes through the rule 1b wait and credits the movement during it (synthetic 25 m run with
a pause at +600 s: 3594.0 m, equal to build 8).
Tests added: `DefaultGpsDistanceTrackerTest` poorFirstFixDoesNotAnchorTheWalkOffThePath (50 m fix 45 m beside the
path, then 5 m fixes: 10.8 m, not 55 m), firstFixWaitExpiresOnTheBestFixSeen / …OnTheCurrentFixWhenItIsTheBest,
aMuchBetterFixInsideAPoorStartingAnchorsRadiusReplacesItWithoutCredit (28 m wait-expiry anchor → 5 m fix 20 m away: 0 m),
…CreditsTheDopplerIntegral (same at 1.2 m/s: 1.2 m, not 0 or 20), aPoorAnchorThatHasCreditedAHopIsKeptMidWalk (the
first draft's scenario: 0 re-anchors, 107.7 m for 100 m walked past a 30 m fix scattered 20 m sideways),
creditedPoorAnchorIsNotReplacedWhileRunningThroughAccuracyBands (25 m with every 10th fix 8 m at 3 m/s: 90.0 m over
30 s, 0 re-anchors), goodAnchorIsNotReplacedByABetterFix, dopplerCreditIsIntegratedPerFix (0, 0, 0.4, 1.2 m/s →
1.6 m), dopplerIntegralSkipsAccuracyRejectedFixesAndNeedsAnUnbrokenChain; `SyntheticRunGpsTrackerTest`
accuracySteppingBetweenBandsWithDopplerIsWithin1Percent, walkWithAccuracyBandsEveryFiveSecondsIsWithin1Percent,
accuracySteppingBetweenBandsWithoutDopplerReported, stopStartCostOfSubThresholdSpeedsReported; `RealTrackTrackerTest`;
`rejectsPoorAccuracy` now shows a 60 m first fix held for 15 s. Screenshot of the Pixel walk's detail on the Moto
after the build-14 install (stored 155 m, no crash): `captures/app_tracker14_20260905/detail.png` (first draft:
`captures/app_tracker9_20260905/detail.png`).
