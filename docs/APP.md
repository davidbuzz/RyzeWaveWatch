# "Buzz's Ryze Wave" — Android app spec

Gradle project in `ryzeapp/` (Kotlin 1.9.22, AGP 8.6.0, compileSdk 35, minSdk 26, Compose BOM 2024.02.02,
Room 2.6.1 via KSP, Health Connect client 1.1.0-alpha11, play-services-location 21.1.0). Package `au.buzz.ryzewave`.
Build and install: see **[BUILD.md](../BUILD.md)** (prerequisites, `tools/fastbuild.sh`, permission grants,
release signing, troubleshooting). The short form is `cd ryzeapp && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### A note on build numbers
Build numbers were reused: the series ran 1..13 on 2026-09-05, then restarted at 9 on 2026-09-06. A build is
therefore identified by **number *and* date** — "build 11, 2026-09-05" is the find-my-phone ringer, "build 11,
2026-09-06" is the GPS breadcrumb. Sections below are in chronological order regardless of number.

### Release build and signing (2026-09-05)
Summary in [BUILD.md](../BUILD.md); the detail is here.
`cd ryzeapp && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease` writes `app/build/outputs/apk/release/app-release.apk`, signed with the
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
| `data` | `Db.kt` (Room database + entities + DAOs), `RoomHealthRepository.kt`, `SettingsStore.kt` (DataStore) | implements `HealthRepository` and `SettingsStore`. Entities: steps_hour(hourStart PK), hr_sample(time, source PK), spo2_sample(time, source PK), sleep_stage(start PK), workout(id auto), track_point(workoutId, time PK), sync_cursor(kind PK), hc_export(clientRecordId PK — the Health Connect export ledger, schema v3). Schema v4 (MIGRATION_3_4): workout gained `steps`/`phoneSteps`/`exerciseTypeOverride` (all nullable) and track_point gained `paused` (NOT NULL DEFAULT 0). `DailySummary` computed with a query + `StrideModel`. |
| `health` | `HealthConnectExporter.kt`, `HealthConnectExportPlanner.kt`, `HealthConnectMapping.kt` | writes Steps/HeartRate/OxygenSaturation/Distance/Sleep/ExerciseSession records (the session with an `ExerciseRoute` of the workout's accepted GPS fixes) to Health Connect since the export cursor, idempotent via `clientRecordId` (e.g. `hr-<time>`) and an export ledger (`hc_export`: content fingerprint per client id, so only records whose content changed since their last export are written), handles SDK availability (`HealthConnectClient.getSdkStatus`), permission request contract for the UI, `exportNew()`, `exportAll(force)` and `exportSince(cursor)`. The SDK entry points sit behind `HealthConnectBackend` so the export loop is unit-tested against a fake client. Mirrors `~/MyPulseApp/app/src/main/java/com/example/pulseapp/MainActivity.kt` for the permission flow. |
| `workout` | `DefaultStrideModel.kt`, `DefaultGpsDistanceTracker.kt`, `WorkoutService.kt`, `WorkoutController.kt` | stride model (vendor factors as defaults: walk 0.410/0.415 × height, run 0.546/0.505; calibrated override), GPS tracker per `docs/PLAN.md §3b`, foreground location service using FusedLocationProvider at 1 Hz, drives `WatchApi.startWorkout/updateWorkout/stopWorkout`, stores `Workout` + `TrackPoint`s, exposes `StateFlow<WorkoutState>` (elapsed, distance, pace, hr, gps quality), GPX export to the app's files dir. Two-way control: a pause/resume/stop on the *watch* (unsolicited `FD 22`/`FD 33`/`FD 00`, told apart from the app's own echoes by `WatchApiImpl`) drives the same `WorkoutController.pause/resume/stop` path via `WatchEvent.WorkoutControl`, without echoing the command back (no loop). `StateAnnouncer` + `TextToSpeech` (alarm-usage audio) speak "workout started/paused/resumed/stopped" from the controller's state change, so app and watch triggers are announced once each. GPS is NOT stopped on pause — fixes are still stored (`TrackPoint.paused`, not counted, no distance) so the track stays continuous; only STOP stops it. Per-workout steps: `Workout.steps` (max of the watch's realtime `FD 01` session-step field) and `Workout.phoneSteps` (`PhoneStepCounter` over `TYPE_STEP_COUNTER`, paused excluded; needs `ACTIVITY_RECOGNITION`). |
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


## Tracker refinement ABANDONED and REVERTED (2026-09-05)

**The GPS tracker on `main` is the build 8 version.** The "Tracker refinements" work described below (per-fix
Doppler integration, first-anchor wait, re-anchor, anti-deadlock, and the various "build 14" numbers) was developed
in a fix-and-verify loop and REVERTED after three rounds: each round fixed its findings but introduced new
edge-case regressions (over-crediting traffic-light stops, a stale Doppler bridge after a dropout, one Doppler
reading poisoning a speed-less receiver, a multipath re-anchor dropping distance). The approach became too complex
to be safe. `DefaultGpsDistanceTracker.kt` and its tests are back at the build 8 commit (1c28d2e).

The ONE real-world issue that motivated this (a poor first GPS fix, ~52 m accuracy and ~45 m off the path, adding
~20-25 m at the very start of the morning walk) is NOT yet fixed. The safe, isolated follow-up is a **first-anchor
wait alone** (do not anchor distance on a fix worse than ~20 m until a better one arrives or ~15 s pass), built and
verified as its own small change WITHOUT the Doppler-integration/bridging machinery. Everything below this line is
the abandoned design, kept for the record; do not treat its "build 14" numbers as current.

---

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
Implemented in "Tracker refinements (build 14)" below: the replay gives **132.1 m** after the fourth review's rework (130.7 m after the second and third — one of the walk's two fixes reporting 0 is now bridged with the speed before it, +1.4 m; 136.4 m with the build-14 rules as installed, 133.7 m with the first draft's unrestricted rule 1c; the section was first numbered build 9, which is "Health Connect hygiene").

### Tracker refinements (build 14, 2026-09-05, installed on the Moto; reworked after the second, third and fourth reviews — source tree only, not yet built or installed) — 387 unit tests, 0 failures (fresh `--rerun-tasks` run)
`DefaultGpsDistanceTracker` (KDoc rules 1b, 1c, 3, 4, 4b and 4c) now does what the finding above asked for, and what
the second, third and fourth reviews (adversarial old-vs-new replays against the baseline tracker — git 1c28d2e, the
tracker last changed in b40739e; see "The second review", "The third review" and "The fourth review" below) found
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
   If none arrives within `firstFixWaitS` = 15 s of the first held fix, the best fix held *before* the current one
   becomes the anchor and the current fix is judged from it like any other fix (jitter, spike or accepted) — what
   anchoring on that fix at once would have done. The movement *during* the wait is real — only where it started is
   doubted — so the Doppler integral runs from the first held fix (a better candidate mid-wait does not restart it)
   and is credited, capped by the hop from the first held fix + the larger accuracy and by rule 4b, on **every** way
   out of the wait: a ≤ 20 m fix arriving, the fix accepted from the candidate at expiry (the credit then lands at
   the first accepted fix), and a `markGap()` while the wait is still open (credited at the last held fix — a pause
   every 10 s in 25 m accuracy used to record nothing because the wait never completed). Build 14 as installed
   credited it on the last exit only and lost up to (15 s + one interval) × speed at every start and every resume
   after a pause — 45 m at 3 m/s, 18 m at 1.2 m/s (the "held seconds are not lost" claim in the earlier version of
   this section was true for one exit of four). A speed-less receiver has no integral, so at expiry the plausibility
   bound of rule 3 is seeded with the wait's *raw-chain speed* — the length of the path through every held fix over
   the wait's duration, when no hop of that path exceeded 1.5 × 12 m/s — and a 6 m/s cyclist 90 m from the candidate
   after 15 s (more than 2.5 × 15 + two radii) is accepted as the 90 m hop: rejecting it deadlocked the tracker (every
   later fix was farther still: 0.0 m after 20 minutes), and the second rework's answer — anchor on the current fix
   with nothing credited — lost the wait's 90–105 m at every start (−1.2 to −1.6 % at 21–30 m). A fix that is still a
   spike from the candidate at expiry is rejected like any other spike unless the chain is consistent and the fix is
   ≤ 12 m/s from the previous raw fix (the rider outran the bound: it anchors with the chain credit of rule 3's
   escape); anchoring on any spike at expiry put the anchor 100 m off the path and cost the wait plus an escape
   (−1.5 to −2.1 %). On the synthetic 25/35 m runs this holds the first 15 fixes and loses nothing; on the real walk
   the 52 m first fix is skipped and the 17 m fix at +9 s starts the walk, so the 23.8 m hop back to the loop is gone.
   **Without Doppler there is no integral**, and the fourth review's replay found every hop-mode exit losing the wait
   (−1.1 % per start or resume, −4.7 % with a pause every 5 minutes, a pause cadence of ≤ 15 s in 25 m recording
   nothing): the hop from the first held fix to the exit fix / the candidate / the last held fix is now credited when
   it is beyond the jitter radius of the pair (capped by the raw chain through the held fixes and by 12 m/s × the
   wait, nothing across an inconsistent chain), and when it is inside the noise the *first held fix* anchors instead —
   unless the exit fix is 3 × better, the real walk's 52-versus-17 m case — so the wait's movement stays inside the
   first hop measured from it, exactly as build 8 measured it. The controller calls `markGap()` at a pause and at the
   stop, so a wait still open then is credited too (a workout stopped 14 s into a 25 m start recorded 0.0 m).
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
4. **Plausibility (rule 3) knows the receiver's last Doppler speed, and spikes cannot go on for ever.** The spike
   bound is max(reported speed, 2.5 m/s, *the Doppler speed of the last accepted fix*) × dt + both radii — the last
   term only when the receiver reported it. The second rework fed a hop-derived speed back into the bound, which is
   noisy and self-reinforcing (each admitted hop widened the next bound): +0.9–1.6 % over build 8 on every no-Doppler
   run with position noise (5 m sigma 3: 4711.7 vs 4680.1 m; 8 m sigma 5: 5014.0 vs 4956.9; 6/9/3 m bands sigma 3:
   4121.9 vs 4079.3; 6/9/3 bands: 3800.3 vs 3794.3) and multipath bursts admitted as hops (+4.0 / +9.4 % at 8 m
   against build 8's +1.5 / +1.75 %); in hop mode the bound is build 8's plain one again and those rows equal build 8.
   The escape hatch (`escapedSpikeCount`) is what keeps a speed-less receiver at running/cycling pace from
   deadlocking: at 5 m accuracy a 6 m/s hop is 12 m over 2 s against an allowance of 2.5 × 2 + 10 = 15 m, position
   noise rejects one hop in seven, and after a rejection dt grows while the allowance grows 2.5 m per second and the
   rider 6 — every later fix was a "spike" (build 8: 851 of 7194 m at 6 m/s, 54 of 8393 m at 7 m/s, 14 m at 8 m/s,
   0 at 10 m/s). When fixes have been rejected as spikes for more than 30 s since the first of them (10 s in the
   second rework), or three consecutive spikes were each ≤ 12 m/s from the *previous raw fix* while the *raw chain*
   since the anchor is consistent (no hop between consecutive fixes past the accuracy gate exceeded 1.5 × 12 m/s —
   the fixes agree with each other, only the anchor is stale), the current fix becomes the anchor crediting the larger
   of the receiver's own capped integral and the raw chain's length since the anchor (capped at 12 m/s × its
   duration): the path the fixes themselves drew, the only measurement there is for a speed-less rider. So the 5 m
   rider is measured mostly by that path — 7543.1 m (+4.85 %) at 6 m/s, 8837.3 m (+5.3 %) at 7 m/s, 6275.2 m (+4.7 %)
   at 5 m/s — hop mode's own over-count of 1 s hops with 2 m independent noise (build 8's 4 m/s run at 5 m, which
   never deadlocked, is +4.95 % with the same noise). The consistency gate is what the second rework lacked: the
   third fix of any burst of ≥ 4 multipath fixes 40 m off the path agrees with the second (3 m/s), so the walk
   re-anchored on the excursion with nothing credited and the return did the same, −12.7 to −13.3 % with one burst a
   minute; **bursts entered faster than 18 m/s** (a 40 m/s hop into a 40 m excursion) break the chain, are ridden out
   as spikes, and the first fix back on the path is accepted from the old anchor with the full credit, exactly as
   build 8 did (and the 30 s let a burst of up to ~25 s pass the same way — with 10 s, bursts of 12–25 fixes were −35
   to −39 %). The fourth review found the gate's hole: an excursion that *ramps* away at 13–18 m/s and drifts on at
   ≤ 12 m/s is a consistent chain of agreeing spikes too, and the escape re-anchored on it crediting its chain against
   the receiver's honest 3 m/s (+10 % on the run, +51 % on the walk, with one such excursion a minute). A receiver
   that reports speeds never deadlocks — its bound carries the last speed — so **the agreeing-spikes escape is for
   speed-less receivers only**; with Doppler only the 30 s time limit applies, crediting the receiver's last credible
   speed over the interval capped by the hop (nothing for a folded-nothing excursion; for a 12 m/s cyclist at the
   cap, whose noisy hops are all spikes, it is what measures him: −1.6 %, build 8 −9.7 %, crediting only the empty
   integral −55 %).
5. **A reported speed of exactly 0 is a dropout, not a stop** (rule 4c, the fourth review). `WorkoutService` sends 0
   when `Location.hasSpeed()` is false, which the per-fix integral credited as 0 m (build 8 credited the last speed
   over the interval): a 3 m/s run with its speed missing for 1 s every 20 s was −4.9 %, for 10 s every 60 s −15.5 %,
   for 30 % of its fixes −30 %. While the last fix folded reported ≥ 1 m/s (raw or itself bridged), an exact 0 is
   folded at that speed and judged, bounded and reported with it; a 0 before any speed was reported (a dropout at the
   very start, or in the wait) is filled in by the first speed reported. The bridged part of the integral is
   believed only when the position is consistent with the whole claim — the hop since the integral's start plus an
   allowance of half the claim (at least 9 m, at most the larger accuracy radius) reaches it — and is dropped
   entirely otherwise, so a walker who *stops* while the receiver keeps saying 0 (72 m claimed per minute against a
   hop of a few metres) credits nothing of it and the walk is exact; a bridged fix accepted on its position while
   its bridge is contradicted credits its hop, like any speed-less fix. Sub-threshold speeds (0.3 m/s) are not
   bridged: they are the receiver's measurement (rule 4b). A fix with the timestamp of the previous one (dt = 0) is
   rejected: its scatter used to be credited as a hop (+13.8 % with every 10th timestamp duplicated).

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

A scratch harness replayed the build-8 tracker (git 1c28d2e, the tracker last changed in b40739e, copied into the
test source set) and the working tree side by side on truth-known runs; the four findings and the fixed numbers
(truth / build 8 / build 14 as installed / reworked; the regression tests are `firstAnchorWaitCreditsTheMovementOnEveryExit`,
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
   hop in seven rejected, then the allowance never caught the rider). No Doppler, 20 minutes; "second rework" is the
   state after this review, "now" after the third review below (the 5 m rows moved because the hop-derived speed
   left the rule 3 bound and the chain-credited escape measures the rider instead; the 21–30 m rows because the wait's
   chain speed seeds the bound at expiry):

   | run | build 8 | second rework | now |
   |---|---|---|---|
   | 6 m/s, 5 m | 120.6 m (−98.3 %); 851.3 m with 40 m for the first 20 s | 7417.2 m (+3.1 %); 7413.0 m | 7543.1 m (+4.85 %); 7533.1 m |
   | 6 m/s, 8 m | 7317.2 m (+1.7 %) | 7316.1 m | 7317.2 m (+1.7 %) |
   | 6 m/s, 21 / 25 / 30 m | 7199.9 / 7174.2 / 7196.2 m (+0.1 / −0.3 / +0.0 %) | 7106.8 / 7083.4 / 7196.1 m (−1.2 / −1.5 / +0.0 %) | 7197.5 / 7174.1 / 7196.1 m (= build 8 within 0.03 %) |
   | 6 m/s, 21 / 25 / 30 m, 40 m for the first 20 s | 7197.4 / 7176.1 / 7192.5 m | 7197.5 / 7176.2 / 7196.2 m | 7197.5 / 7176.2 / 7196.2 m |
   | 7 m/s, 5 m | 53.7 m (−99.4 %) | 8504.1 m (+1.3 %) | 8837.3 m (+5.3 %) |
   | 7 m/s, 8 m | 3552.6 m (−57.7 %) | 8536.0 m | 8547.4 m (+1.8 %) |
   | 7 m/s, 21 / 25 / 30 m | 8392.3 / 8398.7 / 8362.8 m (−0.0 / +0.1 / −0.4 %) | 8286.5 / 8292.3 / 8257.1 m (−1.3 / −1.2 / −1.6 %) | 8392.1 / 8398.0 / 8362.8 m (= build 8 within 0.01 %) |
   | 7 m/s, 21 / 25 / 30 m, 40 m for the first 20 s | 8392.2 / 8397.5 / 8364.8 m | 8388.4 / 8398.4 / 8362.2 m | 8388.4 / 8398.4 / 8362.2 m |
   | 5 / 8 / 10 m/s, 5 m | 715.0 / 14.3 / 0.0 m | — / 9629.1 / 11789.4 m | 6275.2 / 10095.5 / 12126.4 m (+4.7 / +5.3 / +1.1 %) |
   | 8 / 10 m/s, 25 m | 9614.4 / 12011.6 m | 9493.6 / 11860.9 m (−1.3 %) | 9614.3 / 12011.5 m (= build 8) |
   | 6 / 7 m/s, 5 m, Kalman-like noise (phi 0.9) | 1016.7 / 90.5 m | 7222.7 / 8424.8 m (+0.4 / +0.4 %) | 7223.1 / 8428.6 m (+0.4 / +0.4 %) |
   | 6 / 7 m/s with Doppler, 5 or 25 m | within 0.1 % | same | same |

   The second rework's −1.2 to −1.6 % at constant 21–30 m was the one wait's hop (90–105 m): at expiry the current
   fix was a spike from the candidate, so it anchored and a speed-less receiver had nothing to credit for the 15 s
   (build 8 anchored on the first fix and measured them; the 40 m-start rows, where the candidate's radius makes the
   hop plausible, were equal to build 8). Seeding the bound with the wait's chain speed accepts that hop and the rows
   equal build 8. The +4.7–5.3 % at 5 m is hop mode's own over-count of 1 s hops with 2 m independent noise (the
   escape credits the raw chain, i.e. the noisy 1 s hops): build 8's 4 m/s run at 5 m, which never deadlocked, is
   +4.95 % with the same noise, and with Kalman-like noise the runs are within 0.5 %. The second rework's +3.1 % at
   6 m/s / 5 m came from the hop-derived speed in the bound, which cost 0.9–1.6 % on every other no-Doppler run and
   let multipath bursts in as hops, so it went (see the third review); the test bounds are 1 % at 21–30 m, 2 % at
   8 m and 6 % at 5 m.
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
two rows that moved are marked in the table above).

#### The third review: five more regressions against build 8, found by an adversarial replay and fixed (2026-09-05)

The same kind of harness (build 8 = git 1c28d2e copied into the test source set as a differently named class for the
comparison and deleted afterwards; the full 194-row table is `captures/app_tracker14_20260905/old_vs_new_round2.txt`)
run against the second rework. Truth / build 8 / second rework / now; the regression tests are
`multipathBurstsAreRiddenOutAsSpikes`, `pauseEveryTenSecondsInPoorAccuracyStillMeasures`, `spikeAtWaitExpiryIsRejected`,
`firstAnchorWaitInHopModeMeasuresFromTheBestEarlierFix`, the extended `speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock`
and `nanSpeedDoesNotPoisonTheRun` in `SyntheticRunGpsTrackerTest`, plus the rule 1b / rule 3 / markGap / NaN cases in
`DefaultGpsDistanceTrackerTest`:

1. **Multipath bursts triggered the escape hatch.** A burst of N consecutive fixes 40 m off the path (one a minute,
   6 m accuracy, 3 m/s Doppler, 3597.0 m truth for the tracker): the second, third and fourth fixes of a burst agree
   with each other at 3 m/s, so "three consecutive consistent spikes" re-anchored the walk on the excursion with
   nothing credited, and the return did the same. Fixed by the raw-chain consistency gate (the 40 m/s hop into the
   burst breaks the chain) and the 30 s time limit:

   | burst N | build 8 | second rework | now |
   |---|---|---|---|
   | 1 / 2 / 3 | 3597.0 / 3597.0 / 3596.2 m | same | same |
   | 4 / 5 / 6 / 8 | 3597.0 / 3594.1 / 3603.1 / 3599.9 m | 3141.0 / 3117.0 / 3117.0 / 3117.0 m (−12.7 to −13.3 %) | 3597.0 / 3594.1 / 3603.1 / 3599.9 m (= build 8) |
   | 12 / 16 / 20 / 25 (build 8 accepts the excursion as plausible hops after ~20 s) | 3616.3 / 3732.3 / 3867.3 / 3993.5 m (+0.5 / +3.7 / +7.4 / +10.9 %) | −13 %; −35 to −39 % with the 10 s limit | = build 8 |
   | 1.2 m/s walk, 4 / 8 fixes | 1438.8 / 1446.8 m | 1256.4 / 1246.8 m | 1438.8 / 1446.8 m |
   | 8 m, no Doppler, 4 / 8 fixes | 3652.6 / 3660.3 m (+1.5 / +1.7 %) | 3214.8 / 3226.2 m (−10.6 / −10.3 %) | 3652.6 / 3660.3 m |
   | F4 rows (every 30th / 10th fix 25 / 40 m off) | 3597.0 m, 39 / 119 spikes | same | same, 0 escapes |

2. **A pause/resume cadence shorter than the 15 s wait, in 25 m accuracy, recorded nothing**: every resume started a
   wait that the next pause cleared before it completed. `markGap()` now credits an open wait's integral at the last
   held fix: pause every 10 / 14 / 15 s **0.0 → 3213.0 / 3315.0 / 3318.0 m** (build 8 3240.0 / 3342.0 / 3360.0 m:
   the second before each pause is lost, and the run ends inside a wait, hence −0.75 to −1.2 % against build 8);
   every 20 / 30 s 3420.0 / 3480.0 m = build 8 (the wait completes); 6 m accuracy 3240.0 m = build 8. The
   pause/resume at t=600 rows (3594.0 / 1437.6 m) and the real walk (130.7 m) are unchanged.
3. **A spike at wait expiry became the anchor.** 100 m off the path at t=15 (also 15..17, 14..16, 13..20), 25 m, no
   Doppler: the second rework's "never reject at expiry" anchored on the spike and the return cost the wait plus an
   escape: 3541.5 / 3535.9 / 3538.6 / 3523.0 m (−1.5 to −2.1 %) against build 8's 3595.0 / 3595.0 / 3595.0 / 3595.7 m;
   now (a spike at expiry is rejected unless the wait's chain is consistent and the fix ≤ 12 m/s from the previous
   raw fix) **3595.0 / 3595.8 / 3595.2 / 3595.7 m**; with Doppler 3597.0 m (13..20: 3593.8 m, build 8 3596.6 m).
4. **Hop mode lost the wait's movement at expiry.** The "current fix best at expiry → anchor on it" exit credited
   nothing for a speed-less receiver: 30 m for 15 s then 25 m (F1c) 3549.2 m (−1.33 %) at 3 m/s and 7083.4 m
   (−1.54 %) at 6 m/s against build 8's 3595.0 / 7177.4 m; the constant 21–30 m rider rows above likewise. Now the
   best fix held *before* the current one anchors and the current fix is judged from it: **3595.0 / 7174.1 m**; the
   pause/resume rows F1d / F1e / F1f at 6 m/s 7174.1 / 7163.4 / 7151.7 m (build 8 7174.2 / 7163.1 / 7209.5 m — F1f,
   a resume in 25 m with 15 m fixes 10 s later, is a known −0.8 %: the decent-fix exit has no integral to credit in
   hop mode; −0.75 % at 3 m/s). All Doppler F1 rows unchanged at 3597.0 / 1438.8 m (pause/resume 3594.0 / 1437.6 m).
5. **The hop-derived speed in the rule 3 bound** (see item 4 of the section above): E3 6/9/3 m bands 3800.3 → 3794.3 m,
   3 m/s no-Doppler 5 m sigma 3 4711.7 → 4680.1 m, 8 m sigma 5 5014.0 → 4956.9 m, 6/9/3 sigma 3 4121.9 → 4079.3 m —
   all equal to build 8 now. (B3 25 m sigma 10 stays 4150.4 m, +0.85 % over build 8's 4119.6 m: that row has been
   there since build 14 as installed and is the wait's hop-phase shift with 10 m noise, not the bound.)
6. **NaN reported speed** (not what WorkoutService sends) poisoned the integral for the rest of the workout; now
   counted as 0: a 25 m run with a NaN during the wait and one mid-run 3591.0 m (−0.17 % against 3597.0).

Measured and *not* adopted (the switches were tried in the harness and removed): (O1) applying rule 4b's scaling only
when the integral spans more than 5 s helps under-reporting receivers at 10–35 m (0.99 m/s with 1.2 every 3rd fix
−16.4 → −11.8 %, 1.2 / 0.9 alternating −13.9 → −12.6 %, 0.8 with 1.2 every 4th −28.3 → −25.2 %, N(1.0, 0.3) −1.2 →
+0.4 %, T1 at 25 m −0.8 → 0.0 %) but costs the same receivers at 5 m (+1.15 → +5.5 %, −5.7 → −4.5 %, +3.4 → +6.4 %,
+11.5 → +12.6 %, T1 +9.3 → +10.1 %) — a shift of error from poor-accuracy runs to good-accuracy ones, the common
case, so left out; STOPS rows and the real walk were unchanged by it. (O2) bridging a Doppler dropout with
min(hop, last accepted Doppler speed × dt) fixes T3 (−4.4 → −1.3 %; three 0 m/s fixes a minute −1.45 → −0.03 %) but
re-introduces build 8's last-speed-over-the-interval over-count wherever the reported speed is noisy: N(1.0, 0.3)
−1.2 → +17.5 %, T1 at 25 m −0.8 → +7.2 %, the STOPS "reports 0" rows lose their exactness (718.8 → 735.7 m) and the
real walk moves to 132.4 m — rejected.

#### The fourth review: four more regressions against build 8, found by an adversarial replay and fixed (2026-09-05)

The same harness again (build 8 = git 1c28d2e and the WIP HEAD 99669dd copied into the test source set under other
names, deleted afterwards; the 412-row table is `captures/app_tracker14_20260905/old_vs_new_round3.txt`, every run
flushed with `markGap()` at the end as the controller's `stop()` now does). Truth / build 8 / third rework / now:

1. **Doppler dropouts** (rule 4c above). `hasSpeed() == false` → speed 0, which the per-fix integral counted as
   standing still, and dropouts come exactly where the accuracy is poor. 3 m/s run, truth 3597.0 m (build 8 within
   0.3 % except where noted):

   | speed 0 for | 6 m | 15 m | 25 m |
   |---|---|---|---|
   | 1 s every 20 s | 3420.0 (−4.9 %) → **3597.0** | same | same |
   | 2 s every 30 s | −6.6 % → 3597.0 | 3597.0 | 3597.0 |
   | 3 s every 30 s | −9.9 % → 3596.2 | 3597.0 | 3597.0 |
   | 5 s every 60 s | 3594.4 | −8.3 % → 3597.0 | −8.3 % → 3597.0 |
   | 10 s every 60 s | 3597.1 (build 8 +1.4 %) | 3597.0 | −15.5 % → 3597.0 |
   | 10 s every 120 s | 3598.5 | 3597.0 | 3597.0 |
   | 20 s every 120 s | 3598.4 (build 8 +1.2 %) | 3594.2 | −5.6 % → 3597.8 |
   | 10 % / 30 % of the fixes | 3597.0 / 3596.1 (build 8 +5.3 % for 30 %) | 3597.0 / 3597.0 | −10 / −30 % → 3597.0 / 3597.0 |

   The 1.2 m/s walk versions are 1438.2–1438.8 m (truth 1438.8; build 8 up to +3.7 %); a NaN speed every 50th fix
   3597.0 m (build 8 3630.4); a receiver that loses its speed for good at t=300 3591.0 / 3594.0 m at 25 / 6 m
   (build 8 3592.2 / 3691.1). A stop the receiver reports as exactly 0 is *not* bridged: the STOPS walk (718.8 m
   truth) is **718.8 m** at 25 / 50 m with 60 s stops (build 8 734.6) and with 20 / 30 / 40 s stops (build 8 +2.5 to
   +3.1 %), 810.3 m at 5 m (build 8 817.5, hop-mode jitter). The first cut of the bridge believed a claim the hop
   reached within one accuracy radius, which let a 20 s stop in 25 m credit its whole 25 m claim (+31 %); the
   allowance is half the claim, capped at the accuracy radius, with a 9 m floor for the scatter of short hops. Not
   bridged, by design: a *sub-threshold* dropout (0.3 m/s every 20th fix) stays −4.4 % (every 10th −8.7 %) — it is a
   reported speed, and bridging all sub-threshold speeds (the O2 variant of the third review) re-introduced build 8's
   noisy-speed over-count.
2. **Ramped multipath excursions** (item 4 above): 3 m/s with Doppler, four fixes displaced 15 / 23 / 31 / 39 m east
   once a minute at 5 / 6 / 10 m: 3941.8 / 3959.0 / 4012.3 m (+9.6 to +11.6 %) → **3597.0 / 3597.0 / 3726.2 m**
   = build 8; every 20 s +18 / +22 / +33 % → 3608.1 / 3670.3 / 4055.3 = build 8; the 1.2 m/s walk 2172.1 (+51 %) →
   1438.8; 16 / 26 / 36 / 46 and 14 / 22 / 30 / 38 / 46 / 54 m ramps likewise equal build 8 on every row (5 m:
   3597.0 / 3594.9 / 3613.1 / 3781.5, 6 m: 3597.0 / 3596.2 / 3622.1 / 3875.5, 10 m: 3631.8 / 3776.6 / 3806.5 /
   4324.3; walks 1438.8 / 1456.9, at 10 m build 8's own 1465.2 / 1981.3). At 10 m the first 15 m step is inside the
   3 × 1 + 20 m bound and is *accepted* as a hop by both trackers (+3.6 % per excursion a minute, +20 % on the walk):
   build 8's behaviour, kept. The abrupt 40 m × 4 bursts and the 12 / 24 / 36 ramp were equal to build 8 already; the
   no-Doppler ramps at 5 m stay 3262.6 / 3565.8 / 2887.4 / 3850.8 m (build 8 −29 to −78 %: the chain escape is what
   measures a speed-less rider through them).
3. **Hop-mode wait exits** (item 2 above), no Doppler, 3 / 6 m/s (truth 3597.0 / 7194.0): 25 m then a 19 m fix at
   t=14 3555.6 (−1.1 %) → **3595.4 / 7220.7** (build 8 3595.3 / 7222.5); a better 25 m candidate at t=14 in 30 m
   3569.8 / 7202.8 = build 8; a 21 m fix at t=14 only 3595.3 / 7176.3 (3595.0 / 7174.2); accuracy descending 40..26 m
   then 25 m 3555.5 (−1.15 %) → 3597.1 / 7176.3 = build 8; 25 m with a pause every 300 s and 15 m fixes 14 s after
   each resume 3408.0 / 6858.3 (−4.7 %) → 3576.5 / 7194.4 (build 8 3574.7 / 7194.8); the "30 m for 15 s then 25 m"
   and "25 m constant" rows stay 3595.0 / 7174.1. A pause cadence in 25 m without Doppler: every 14 / 15 s at 3 m/s
   0.0 → **2446.4 / 3268.7 m** (build 8 2543.4 / 3069.6), every 10 / 14 / 15 s at 6 m/s 0.0 → 6524.3 / 6712.8 /
   6735.5 m (build 8 4934.7 / 5127.8 / 6351.7); every 10 s at 3 m/s stays 0.0 = build 8 (27 m is inside the 37.5 m
   radius). The 100 m spike-at-expiry rows and the real walk are unchanged.
4. **The open wait at stop()**: `WorkoutController.stop()` read the distance without flushing a rule 1b wait, and
   `pause()` likewise (the credit came only at the resume's `markGap()`, after the state had been shown). Both call
   `markGap()` first now: a 25 m Doppler run stopped after 5 / 10 / 14 fixes 0.0 → **12.0 / 27.0 / 39.0 m** = build 8
   (3 m/s × the intervals), the 6 m/s no-Doppler version after 10 / 14 fixes 0.0 → 49.3 / 78.3 m (build 8 44.7 /
   44.7), a pause every 15 s in 25 m with Doppler 3318.0 → 3360.0 m = build 8 (`pauseAndStopFlushTheOpenFirstAnchorWait`).
5. **Duplicated timestamps** (both trackers): a fix with dt = 0 was accepted with its scatter as a hop, +13.8 % with
   every 10th timestamp duplicated (4093.4 m); rejected now, 3597.0 m at 6 and 25 m.

Decisions taken with the fourth review: (D2) the no-Doppler riders at 5 m stay within **6 %** (+4.7 to +5.3 %), not 3 %
— it is hop mode's over-count of 1 s hops with 2 m independent noise (build 8's 4 m/s run at 5 m, which never
deadlocked, is +4.95 % with the same noise; with Kalman-like phi = 0.9 noise +0.4 %), and the 8–30 m rows must not
move; (D5) the under-reporting receivers are **accepted as a consequence of the per-fix integral**: a receiver whose
integral is short by construction (0.99 m/s with 1.2 every third fix integrates to 1.06 m/s, −12 % even if fully
believed; 0.8 with 1.2 every fourth to 0.9 m/s, −25 %) can only be corrected by replacing the integral with the hop
over long windows — hop mode, which is what build 8 effectively did there and what over-counts every receiver with
symmetric speed noise (N(1.0, 0.3) +22 %, 0.9 / 1.1 +10–20 %, N(1.1, 0.2) +9.5 %, all within 1.2 % now). Every other
row of the 412 is within 1 % of build 8 or closer to the truth; the exceptions are listed below.

**Known limitations after the fourth review** (all measured; the loose-bound tests in
`perFixIntegralTradeOffsReported`, `rampedMultipathExcursionsAreRiddenOutWithDoppler` and the reported rows keep them
visible):
- a *sub-threshold* Doppler dropout (0.3 m/s at every 20th fix of a 3 m/s run) costs the missing second: **−4.4 %**
  (every 10th −8.7 %; build 8 −0.1 / +0.2 %). An exact 0 is bridged (item 1 above); 0.3 m/s is a reported speed;
- an under-reporting receiver at 10–35 m accuracy is believed: a 1.2 m/s walker reported as 0.99 m/s with 1.2 every
  3rd fix **−16.4 %**, 1.2 / 0.9 alternating **−13.9 %**, 0.8 with 1.2 every 4th **−28.3 %**, 0.99 with 1.0 every
  10th −11.3 / −18.4 % (build 8, in hop mode across the sub-threshold fixes, within 0.3 %, +1.7 / +4.5 % for the
  last); at 5 m +1.2 / −5.7 / +3.4 / +17.9 % (build 8 +16 / +7.6 / +27 / +19 %). Symmetric speed noise is better than
  build 8 everywhere (N(1.0, 0.3) at 25 m −1.2 % vs +22 %; N(1.2, 0.15) +0.2 % vs +2.2 %; N(1.1, 0.2) −0.4 % vs +9.5 %;
  0.9 / 1.1 at 25 m −0.8 % vs +10 %, at 5 m +9.3 % vs +19.8 %) — accepted, see D5 above;
- a *short* stop the receiver reports as exactly 0 — up to about 7 s at 1.2 m/s or 3 s at 3 m/s — is inside the
  bridge's 9 m noise floor and is credited as movement, at most 9 m per such stop (build 8 credited up to the hop +
  one accuracy radius for the same fixes);
- a single 40 / 60 m spike exactly at the wait's expiry in 25 m, no Doppler: **+1.16 / +2.0 %** against build 8
  (3636.9 / 3667.6 vs 3595.0 m; with Doppler the 60 m one +1.2 %) — the wait's 15 s dt makes the hop plausible and the
  return is another hop. A phase artefact rather than a bias: the same spike at t=12 or t=13 costs build 8 +1.4 /
  +2.1 % while this tracker is exact, and a 100 m spike is rejected by both at any phase;
- a pause cadence of ≤ 12 s at 3 m/s in 25 m without Doppler records nothing (the hop is inside the 37.5 m radius) =
  build 8; every 14 s 2446.4 m is 96 % of build 8's 2543.4 m;
- the walk's hop-mode rows where the wait's hop is inside the noise (a better candidate at t=14 in 30 m at 1.2 m/s:
  −2.7 %; 30 m for 15 s then 25 m: −2.2 %) equal build 8: the first held fix anchors, as build 8 anchored at once;
- bursts of 16 / 20 / 25 multipath fixes: +3.7 / +7.4 / +10.9 % = build 8 (after ~20 s the excursion is a plausible
  hop); ramped excursions at 10 m accuracy +1 to +20 % = build 8 (the 15 m first step is a plausible hop);
- the no-Doppler riders at 5 m: +4.7 to +5.3 % (D2 above);
- a 12 m/s cyclist at the speed cap, no Doppler: **−43 %** (8139 m; build 8 8.8 m — both fail; 25 m: −38 % vs
  −99 %). A per-sport `maxSpeedMps` for cycling is the fix, not tried. With Doppler he is −1.6 % now (build 8 −9.7 %).

Pause/resume (`WorkoutControllerTest`): the re-anchor after `markGap()` at 5 m accuracy is immediate; a resume in
poor accuracy goes through the rule 1b wait and credits the movement during it (synthetic 25 m run with a pause at
+600 s: 3594.0 m, equal to build 8), a pause before the wait completes credits it too (item 2 above), and `pause()` /
`stop()` flush the wait (`pauseAndStopFlushTheOpenFirstAnchorWait`: 27.0 m at the pause, 39.0 m at the stop).
Tests added: `DefaultGpsDistanceTrackerTest` poorFirstFixDoesNotAnchorTheWalkOffThePath (50 m fix 45 m beside the
path, then 5 m fixes: 12.0 m, not 57 m), firstFixWaitExpiresOnTheFirstHeldFixWhenTheBetterOneIsInsideItsNoise /
firstFixWaitExpiresOnTheBestFixHeldBeforeTheCurrentOne,
aMuchBetterFixInsideAPoorStartingAnchorsRadiusReplacesItWithoutCredit (28 m wait-expiry anchor → 5 m fix 20 m away: 0 m),
…CreditsTheDopplerIntegral (same at 1.2 m/s: 1.2 m, not 0 or 20), aPoorAnchorThatHasCreditedAHopIsKeptMidWalk (the
first draft's scenario: 0 re-anchors, 107.7 m for 100 m walked past a 30 m fix scattered 20 m sideways),
creditedPoorAnchorIsNotReplacedWhileRunningThroughAccuracyBands (25 m with every 10th fix 8 m at 3 m/s: 90.0 m over
30 s, 0 re-anchors), goodAnchorIsNotReplacedByABetterFix, dopplerCreditIsIntegratedPerFix (0, 0, 0.4, 1.2 m/s →
1.6 m), dopplerIntegralSkipsAccuracyRejectedFixesAndNeedsAnUnbrokenChain; `SyntheticRunGpsTrackerTest`
accuracySteppingBetweenBandsWithDopplerIsWithin1Percent, walkWithAccuracyBandsEveryFiveSecondsIsWithin1Percent,
accuracySteppingBetweenBandsWithoutDopplerReported, firstAnchorWaitCreditsTheMovementOnEveryExit,
firstAnchorWaitInHopModeMeasuresFromTheBestEarlierFix, speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock,
creepingReceiverCostsAboutTheHopNotTheAccuracyRadius, spikesReportingABogusSpeedAreNotIntegrated,
multipathBurstsAreRiddenOutAsSpikes, pauseEveryTenSecondsInPoorAccuracyStillMeasures, spikeAtWaitExpiryIsRejected,
nanSpeedDoesNotPoisonTheRun, perFixIntegralTradeOffsReported (and `DefaultGpsDistanceTrackerTest`
waitExpiryAcceptsASpeedlessRiderFromTheWaitsChainSpeed, aSpikeAtWaitExpiryIsRejectedAndTheCandidateStaysTheAnchor,
aRiderBeyondTheSpeedCapAtWaitExpiryAnchorsWithTheChainCredit, aSpeedlessRunnerRejectedByNoiseIsRecoveredFromTheRawChain,
aHopDerivedSpeedDoesNotWidenTheBound, theReceiversLastDopplerSpeedWidensTheBound,
threeConsistentSpikesReAnchorCreditingTheRawChain, aBurstOfOffTrackFixesEnteredAtSpeedIsRiddenOutAsSpikes,
thirtySecondsOfSpikesReAnchorWithoutCredit, markGapCreditsTheIntegralOfAnOpenWait, nanSpeedCountsAsZero); the fourth
review's `DefaultGpsDistanceTrackerTest` hopModeWaitCreditsTheHopToTheCandidateAtExpiry / …ToADecentFixBeyondTheNoise,
hopModeWaitAnchorsOnTheFirstHeldFixWhenTheDecentFixIsInsideTheNoise, hopModeWaitSkipsTheHopToAMuchBetterDecentFix,
markGapCreditsTheHopOfAnOpenWaitWithoutDoppler, aDopplerDropoutIsBridgedWithTheLastSpeed,
aLongDropoutIsAcceptedOnItsPositionWithTheBridge, aStopTheReceiverReportsAsZeroIsNotBridged,
aBridgedFixAcceptedOnItsPositionCreditsTheHopWhenTheBridgeIsContradicted, aDropoutAtTheStartIsFilledInByTheFirstSpeed,
aSubThresholdSpeedIsNotBridged, agreeingSpikesDoNotReAnchorAReceiverWithDoppler,
theTimeEscapeWithDopplerCreditsTheLastSpeedOverTheInterval, aDuplicatedTimestampIsRejected, and
`SyntheticRunGpsTrackerTest` dopplerDropoutsAreBridgedWithTheLastSpeed, rampedMultipathExcursionsAreRiddenOutWithDoppler,
hopModePauseCadenceAndEarlyStopKeepTheWaitsMovement, duplicatedTimestampsAreRejected,
cyclistAtTheSpeedCapWithDopplerIsMeasuredByTheTimeEscape (and the extended firstAnchorWaitInHopModeMeasuresFromTheBestEarlierFix),
`WorkoutControllerTest` pauseAndStopFlushTheOpenFirstAnchorWait;
`RealTrackTrackerTest`;
`rejectsPoorAccuracy` now shows a 60 m first fix held for 15 s. Screenshot of the Pixel walk's detail on the Moto
after the build-14 install (stored 155 m, no crash): `captures/app_tracker14_20260905/detail.png` (first draft:
`captures/app_tracker9_20260905/detail.png`).

## Workout control: two-way pause/resume/stop, spoken cues, GPS through pause, per-workout steps, type override (2026-09-05)

Fixes for the 17:20 run (`captures/pixel_run_20260905`): a watch-side pause the app ignored left it stuck paused
for 30 min, GPS was effectively lost while paused, a stop from paused never finalised the row, and the watch's
per-session step count went unused.

- **One state machine, two sources.** The watch echoes every control it receives and *also* sends `FD 22`/`FD 33`/
  `FD 00` when its own buttons are pressed. `WatchApiImpl` tells an echo (a waiter consumed it, or it matches a
  ~2 s expected-echo window after the app's own send) from a watch button press, and surfaces the latter as
  `WatchEvent.WorkoutControl(action)`. `WorkoutController` collects it and runs the same `pause/resume/stop` as the
  app buttons — with `fromWatch = true` so it does **not** send the control back (no loop). App-initiated pause
  still sends `FD 22`. Tests: echo-vs-watch-origin discrimination (`WatchApiImplTest`), and a watch pause that
  pauses without re-sending (`WorkoutControllerTest`).
- **Spoken cues.** `StateAnnouncer` (pure, unit-tested) maps phase transitions to "workout started/paused/
  resumed/stopped"; `WorkoutService` speaks them from one collector of `controller.state` via `TextToSpeech`
  (alarm-usage audio, audible from a pocket) and logs each utterance at INFO. Driven by the controller's state, so
  app and watch triggers are each announced exactly once, no duplicates on repeated same-state emissions.
- **GPS through pause.** `WorkoutService` no longer stops location on pause (only on STOP). Fixes taken while
  paused are stored (`TrackPoint.paused = true`, `accepted = false`, no distance added) so the track stays
  continuous and a missed resume never loses the route.
- **Finalise from paused.** `WorkoutController.stop()` writes the finished row (end, active-time duration,
  distance, HR, calories, steps) whether RUNNING or PAUSED.
- **Per-workout steps.** `Workout.steps` = max of the watch's realtime session-step field; `Workout.phoneSteps` =
  `PhoneStepCounter` over `TYPE_STEP_COUNTER` (paused excluded; needs `ACTIVITY_RECOGNITION`). Room v3→v4
  (`MIGRATION_3_4`) adds `steps`, `phoneSteps`, `exerciseTypeOverride`, and `track_point.paused`.
- **Calibration uses per-workout steps.** `SettingsViewModel.calibrateFromLastWorkout()` uses
  `DefaultStrideModel.calibrationSteps` (watch steps ≥ MIN, else phone steps, else an honest "recorded before
  steps were counted per workout" message); the message names the source used. The hourly pro-rating path is gone.
- **Exercise type override.** `Workout.exerciseTypeOverride` (a Health Connect `EXERCISE_TYPE_*`) wins over the
  speed/sport heuristic in `HealthConnectMapping.exerciseType`; the detail screen has a Running/Walking/Hiking/
  Biking/Other selector (`WorkoutDetailViewModel.setExerciseType`) that stores it and re-exports the session.
- **Debug hook** (debug build only, `au.buzz.ryzewave.debug.WORKOUT`, DUMP-guarded): `--es op start|pause|resume|
  stop|steps [--ei n <count>]` injects `WatchEvent.WorkoutControl` / `WatchEvent.WorkoutRealtime` to exercise the
  state machine without the watch (`start` uses the normal foreground path and may send `FD 11` if a watch is
  connected — run it with the watch disconnected).


## Build 9 (2026-09-06): honest sleep reconstruction + night-workout guard

**Generic asleep.** Watch sleep codes are 1 deep, 2 light, 3 REM, 4 awake; code **5 = asleep, stage unknown**
(`SleepStage.GENERIC_ASLEEP`). It counts toward the asleep total, never as awake, is never split into deep/light/REM,
renders in its own grey "Asleep" lane on the History card ("unstaged h:mm" in the totals line) and exports to Health
Connect as `STAGE_TYPE_SLEEPING`. `SleepReconstruction` fills a night window with generic-asleep only where the watch
has no stage (both ends and internal gaps), keeping every real stage byte for byte. `HealthRepository.replaceSleepForNight`
writes it; the debug-only broadcast `au.buzz.ryzewave.debug.SLEEP` (extras `start`/`end` epoch ms, DUMP-guarded)
triggers it and re-exports the night. Why: the night of 2026-09-05 the watch was stuck in an accidental exercise mode
and only staged 00:26–06:11; heart rate showed real sleep 22:15–07:00 (captures/pixel_sleep_20260906/README.md).

**Night workout guard.** `NightWorkoutGuard` (pure): a WATCH-originated workout start is "likely accidental" when it
starts 22:00–05:59 local, the recent resting HR is below 75 bpm and there is no GPS movement (unknown HR = not
accidental). `NightWorkoutGuardController` then posts a high-priority "Workout started while you may be asleep — tap to
stop" notification with a Stop action and auto-stops the watch workout after a grace period if unacknowledged.
App-initiated workouts are never auto-stopped; daytime starts are only logged. Debug trigger: the existing
`au.buzz.ryzewave.debug.WORKOUT` broadcast with op `watchstart`. The general per-sport "stuck in exercise mode"
detector (docs/PLAN.md) is the planned superset.

361 unit tests. Verified on the Moto: the buggy night went from 3 h 33 m to 8 h 10 m asleep (bed 22:15, rise 07:00,
35 min real awake, 4 h 37 m unstaged), Health Connect export OK; the guard flagged an injected 02:00 start at 58 bpm and
auto-stopped it after the grace, and allowed a daytime start.


## Build 10 (2026-09-06): stuck-in-exercise-mode detector (per-sport), spoken "workout started"

Supersedes the build-9 night guard. `workout/StuckWorkoutMonitor` watches every workout — the app's own and one the
watch started by itself (`WorkoutControl(START)`) — and reduces what it sees to four indicators over a rolling 4-minute
window (`ActivitySignals`): **steps** (watch session counter from the `FD 01` pushes, or the phone step counter,
rising), **gps** (tracker distance +10 m or Doppler ≥ 0.7 m/s), **hr** (≥ resting baseline + 15; baseline =
10th percentile of the last 24 h of periodic samples, `RestingHrBaseline`, fallback 65), **motion** (phone accelerometer
|a| variance ≥ 0.15 over 5 s blocks, `AndroidMotionSampler`/`MotionVariance`). Each indicator is ACTIVE / INACTIVE /
UNKNOWN (unavailable). `SportSignature` maps all 70 sport ids to the indicators a live session should show (MOVEMENT:
all four; INDOOR_STEPS: steps+hr+motion; RIDE: gps+hr+motion; STATIONARY_CARDIO: hr+motion; FLOOR_WORK (yoga): motion
only; STRENGTH: steps+hr+motion; UNMONITORED (swimming, fishing, archery…): never judged). `StuckModeDetector`: live if
ANY expected indicator is active; likely-stuck if the window is covered, nothing expected is active and at least one
expected indicator was actually measured; urgency HIGH (2-min grace) at night with HR at sleeping level and a still
phone (the former night guard), else NORMAL (10-min grace). Escalation: speak "Workout running but no activity
detected. Tap to stop." + high-priority notification with Stop; after the grace, auto-stop through the normal path
(watch-started always; app-started only with the Settings switch "auto-stop app-started workouts", default off) and
speak "workout stopped, no activity"; activity resuming withdraws the warning. Watch-originated starts are spoken
("workout started"); app starts too, via the service. Settings: detector master switch (default on).

Salvaged from a terminated workflow and finished directly (Buzz: "we want the code and the edits from that job that
make sense, but not as a subtask"). One real bug found by the new tests: the fired grace timer cancelled its own
coroutine mid-stop (the stop path cancels `autoStop`), so an app-started workout never reached STOPPED; the timer now
detaches itself first. Tests: `SportSignatureTest`, `StuckModeDetectorTest` (yoga / rowing machine / phone-in-bag /
flat run / night / rest / unavailable signal), `ActivitySignalsTest`, `StuckWorkoutMonitorTest` (warn → auto-stop,
rising steps never warn, withdraw on activity, app-started not auto-stopped by default, stopNow). Debug:
`au.buzz.ryzewave.debug.WORKOUT` op `watchstart` with `--el window/grace`, `--ez night`, `--ei hr`, `--ez moving`, then
op `steps --ei n` to feed flat or rising session steps.


## Auto-connect switch (2026-09-06)

`SettingsStore.autoConnect` (default true) is the persisted form of the Home screen's Connect/Disconnect: Disconnect
sets it false and the watch service's connect loop treats `!autoConnect` like the in-memory pause, so the choice
survives a reinstall or reboot. Motivation: the watch holds ONE BLE link; right after a build was installed on the
Moto test phone its app reconnected and stole the watch from Buzz's Pixel mid-test (status 147 on the Pixel until the
Moto was stopped). On the test phone, tap Disconnect once and it stays off the watch until Connect is tapped.


## Build 11 (2026-09-06): opt-in always-on GPS breadcrumb ("plan B", independent of Google Fit)

Buzz's requirement: the app itself keeps a GPS trail while he moves so a workout whose own tracking fails can be
reassembled, without relying on Fit being pre-configured; it must discard stationary time.

- **Gate** (`workout/BreadcrumbGate`, pure): activity-recognition transitions decide the radio. WALKING/ON_FOOT →
  location on at 60 s; RUNNING/ON_BICYCLE → 20 s; STILL → keep sampling for a 2-minute grace, then off;
  IN_VEHICLE → off; a significant-motion event with no activity report → a 3-minute burst at 60 s; no report for
  10 minutes → off. Unit-tested (`BreadcrumbGateTest`).
- **Service** (`workout/BreadcrumbService`): location-type foreground service (own MIN-importance notification),
  started/stopped by the Settings switch through a collector in `GraphFactory`; requests `ActivityTransition`
  updates (mutable PendingIntent to a receiver registered for the service's lifetime), arms the
  `TYPE_SIGNIFICANT_MOTION` trigger as a fallback, ticks the gate every minute, and turns fused location on/off
  (balanced-power accuracy) at the gate's interval. Fixes are stored as `breadcrumb` rows (Room v5) with the detected
  activity; nothing is stored while one of our workouts is active (it records its own track); rows older than
  14 days are pruned at start and daily.
- **Permissions**: `ACCESS_BACKGROUND_LOCATION` declared; the Settings section links to the app's permission page
  ("Allow all the time" lets the trail survive a reboot). ACTIVITY_RECOGNITION is already requested.
- **Rebuild**: the workout detail's Track card gains "Rebuild from breadcrumb": crumbs inside the workout window run
  through `BreadcrumbReconstruction` (the same `DefaultGpsDistanceTracker` rules as a live workout), the points are
  added to the workout, the distance replaced and the session re-exported to Health Connect. Unit-tested
  (`BreadcrumbReconstructionTest`: a 1 km breadcrumb walk at one fix per 30 s reconstructs within 3 %).
- Settings: "GPS breadcrumb (plan B)" switch, off by default; trail kept on the phone only.


## Location gate + BreadcrumbService crash fix (build 12, 2026-09-06)

- **A workout cannot start without GPS** (memory: location-off is critical). The Workout screen checks
  `LocationManager.isLocationEnabled` and the fine-location permission before starting; when location services are off
  it hides the Start button and shows a red "Location services are OFF" card with a "Turn on location" button that
  opens `Settings.ACTION_LOCATION_SOURCE_SETTINGS`, and re-checks on ON_RESUME so Start returns once location is on.
  (Cause of the 2026-09-06 run failure: the phone's location was off, so zero GPS, 0 m distance, pace "--:--".)
- **BreadcrumbService crash fixed**: `stop()` used a start-intent which created the service and crashed trying to go
  foreground (type location) while location was off; now `stop()` uses `stopService` (never creates it) and
  `startInForeground()` catches the refusal and `stopSelf()`s. The app no longer crash-loops on launch.
- Still to do: a step-based (stride) pace/distance fallback so pace is never a bare "--:--"; stop mirroring the watch's
  rapid junk pause/resume (FD 22/33 flood) that split the 2026-09-06 run and spammed spoken cues.


## Build 13 (2026-09-06): step-based pace fallback + watch pause/resume debounce

- **Never a bare "--:--".** `ui/WorkoutMetrics` (pure) picks the source: GPS when it is live (available, not stale,
  distance > 0); otherwise, when the watch reports session steps, distance = steps × stride (walk/run stride from
  Settings, chosen by sport or live cadence) and pace = elapsed / that, labelled "Distance (from steps)" / "6:10 /km
  (est)". The estimate is display-only — the stored `workout.distanceMeters` stays the GPS value. Dashes only when
  neither GPS nor steps exist. (Together with the location gate this covers both "location off" and "GPS lost mid-run".)
- **Watch pause/resume debounce.** A watch-originated pause/resume arms an 8-s settle timer in `WorkoutController`;
  a reversal inside the window cancels it, so the watch's junk 1–5 s FD 22/33 flood (2026-09-06 run) no longer
  thrashes the workout or spams spoken cues. Only a state held past the window is applied and announced, once. App
  buttons and STOP stay immediate; the 13-byte FD 33 play-button fix is untouched.
- 394 unit tests (WorkoutMetricsTest 9, WorkoutControllerTest 21). Verified by an independent agent. Not yet
  exercised on-device (the Moto was off USB during the job); installed on the Pixel.

## Indoor machines are no longer expected to travel (2026-09-06)

`SportSignature` gained `STATIONARY_MACHINE` (steps, HR, motion) and Rower (0x29) and Elliptical (0x1F) moved into
it from `RIDE`, which expected GPS. On an erg the body does not move, so the stuck-workout detector could reach
"nothing expected is active" on a real session and warn, then auto-stop it where that is enabled. What does move is
the wrist, once per stroke, and the watch's realtime push already delivers that count to
`ActivitySignals.onWatchSteps`, so the counter is now accepted as evidence of life for those two sports. On-water
rowing is unaffected: its HR and motion are active regardless. Spinning stays `STATIONARY_CARDIO`, because hands on
the bars never raise the counter.

## Average speed no longer overrules the chosen sport (2026-09-06)

`SportTypes.effectiveId` used to relabel any type-1 (Outdoor Running) workout with an average speed under 2 m/s as
Outdoor Walking, for Health Connect and for the GPX name. That was a back-fill for rows recorded before the sport
picker existed, when type 1 was the only type and walks were filed under it. Applied to a chosen sport it is the
same mistake the stride calibration made: a run of sprints and recovery walks averages below any fixed threshold,
and the 2026-09-06 session (1.42 m/s over 772 m) would have reached Health Connect as a walk. The heuristic now
applies only to rows started before `SportTypes.PICKER_EPOCH_MS` (2026-09-05); anything since is exported as the
sport the wearer picked, with the manual override on the detail screen still winning outright.

## Sports: researched table, court gate, all 70 mapped to Health Connect (2026-09-06)

`docs/sports_and_step_counting.md` now carries a per-sport, sourced description of what the wrist sees, whether the
counter means anything, whether GPS is worth having, and the Health Connect type. Code changes it drove: a
`COURT` stride gate for racket and net sports; snorkeling moved from strokes to noise; baseball, softball and bowling
to repetitions; all 70 sports mapped to Health Connect types (11 remain "other"); and `tools/all_sports_test.sh`,
which drives every sport through the real picker on the phone and checks the saved row.

## GPS versus the declared sport (2026-09-06)

`workout/SportMotionCheck` compares a finished workout's track with what its sport implies (`STAYS_PUT`,
`COVERS_GROUND`, `EITHER`, derived from the sport signature and the stride gate so the tables cannot disagree).
Verdicts: `CONFIRMED_STATIONARY` (every usable fix inside 25 m of the centroid), `CONFIRMED_MOVING` (150 m recorded
or a 60 m spread), `MISLABELLED_MOVED`, `MISLABELLED_STILL` (only after 4 min, so a warm-up on the spot is not
accused), `INCONCLUSIVE`, `NO_GPS` (fewer than 20 fixes better than 30 m). Paused fixes are ignored. The sentence is
shown under the stats on the workout detail, red for a contradiction, primary for a confirmation.

## The watch is asked which sport it opened (2026-09-06)

`FD AA` (verified on the watch via RyzeBridge: `FD AA 00 01` when idle) is the closest thing to reading the
watch's screen. `WatchApi.queryWorkout()` sends it; `startWorkout()` now asks right after the start echo and logs
"watch confirms sport N open" or "watch reports state=S type=T", best effort and never fatal. `tools/all_sports_test.sh`
reads that line back from logcat into a `watch_says` column and fails a sport when the watch disagrees with what
was chosen, so the end-to-end test proves the watch entered the sport, not only that the app saved the id.
