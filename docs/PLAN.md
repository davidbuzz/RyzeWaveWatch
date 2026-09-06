# Plan: replacing Ryze Fit

Status 2026-09-04 evening: the protocol is understood well enough to build. Everything the phone needs to do
to get heart rate, SpO2, steps and workouts from the watch has been exercised through our own code (see
`README.md` roadmap and `PROTOCOL.md` §7a).

## 1. What the app must do

| Feature | Watch side (verified) | App side |
|---|---|---|
| Connect | `connectGatt(TRANSPORT_LE)`, MTU 247, CCCD on 33F2/34F2, no password on this model | reconnect by MAC, keep a foreground service |
| Time / profile / sampling | `A3` set time, `A9` height/weight/age/gender/goal, `F7 01` continuous HR, `34 03 01 <min16>` SpO2 auto-interval, `34 04` period | **on every connect** — after a factory reset the watch has none of these; the vendor app re-sends them each time it connects and so must we |
| Steps | hourly `B2` records, `B1` live pushes | store, show today + history |
| Heart rate | 10-min `F7` history; `F7 03/04` pushes; live via `D6 02`+`E5 11`; workout `FD 01` | charts + live view |
| SpO2 | 10-min `34 FA` history; auto-sampling `34 03`; spot test `34 11` (~60 s) | history + on-demand test |
| Sleep | `31 01` → `32` stages | nightly summary |
| Workout | `FD 11/22/33/00` control, `FD 01` HR stream, `FD 44` metrics push every second | GPS tracking, distance/pace, push to watch |
| Distance | **not from the watch** | our own model, see §3 |
| Nice-to-have | notifications `C5`, find watch `AB`, weather `CA/CB`, canned replies `46`, contacts `37` | later |

## 2. Platform

Recommendation: **native Android, Kotlin**, minSdk 26, using the same GATT code that already works in
`android/src/au/buzz/ryzebridge/BleService.java` (port it to Kotlin, keep the one-outstanding-op queue and the
foreground service). Reasons: the phone's Bluetooth stack is the only one that holds this watch's link reliably,
Buzz already has a working Kotlin/Gradle toolchain (`~/MyPulseApp`), and GPS tracking during workouts needs a
foreground location service anyway. The Python codec (`ryzewave/protocol.py`) stays as the executable spec and
test oracle; the Kotlin port should mirror its function names so the two can be diffed.

Alternative considered: contributing a fix to Gadgetbridge. Their GloryFit driver is close, but it lacks the
`D6`/`E5` live HR, SpO2 spot test, the `34 FA`/`F7` year-byte fix and the workout metric push; Buzz also wants
his own distance handling and UI. Worth upstreaming the protocol findings later regardless.

## 3. Distance, done properly

**Status: built and validated outdoors** (`workout/DefaultGpsDistanceTracker.kt`, `workout/DefaultStrideModel.kt`,
`ui/WorkoutMetrics.kt`). Calibrated walking/running strides are user-overridable in Settings, the GPS tracker
rejects poor and jittery fixes, and when GPS is unavailable pace and distance fall back to steps x stride and are
labelled as estimates. What follows is the reasoning that led there.

The vendor app never gets distance from the watch. Two separate calculations, both on the phone:

**a) Daily distance from steps** (`PedometerUtils.calculateDistance`): `km = steps × height_cm × k / 100000`
with `k` = 0.418, or 0.410/0.415 (walk, male/female) and 0.546/0.505 (run) when feature bit FL2&0x1000 is set
(it is, on the Ryze Wave). For 175 cm that is 71-73 cm per walking step, 95 cm per running step, and the watch's
`B2` records split walking vs running steps, so the app should already use the two factors. If the daily figure is
what's wrong, the fix is a **calibrated stride**: measure stride from a GPS walk (steps in the window ÷ GPS
distance), keep separate walking/running strides, and let the user override. Sanity: typical walking stride is
0.40-0.45 × height; running 0.55-0.65 × height depending on pace.

**b) Workout distance from GPS** (`sports/…`, `AMapUtils.calculateLineDistance` on a `PathSmoothTool`-filtered
track): distance is summed between consecutive fixes after a smoothing pass, with no visible accuracy or speed
gating in the decompiled code. Classic failure modes: counting GPS jitter while standing still (inflates), or
over-smoothing corners and dropping fixes (deflates). If the workout figure is what's wrong, our version should:
1. take fused-location fixes at 1 Hz with `accuracy`, `speed`, `bearing`;
2. drop fixes with accuracy worse than 60 m (was ~20 m, which gave 0 km on a poor-signal run), and ignore movement below 1.5 × the accuracy radius when speed ≈ 0; do not *start* on a poor fix: wait up to 15 s for one of ≤ 20 m before anchoring (the first real walk anchored on a 52 m fix 45 m off the route and counted the hop back), then anchor on the best fix held before the current one and judge the current fix from it as if that fix had anchored at once; credit the Doppler integral over the wait on every way out of it — a decent fix, the fix accepted at expiry, a pause or the stop before the wait completes — (the movement was real, only its start was doubted — dropping it cost 45 m per start and per resume at 3 m/s, and a pause every 10 s in 25 m accuracy recorded nothing); without Doppler credit the hop from the first held fix to the exit fix / candidate when it is beyond the jitter radius (capped by the raw chain) and otherwise anchor on the first held fix, as anchoring at once would have (losing it cost 1.1 % per start and 4.7 % with a pause every 5 minutes); for a speed-less receiver seed the plausibility bound at expiry with the wait's raw-chain speed so a 6 m/s rider's 90 m hop is accepted rather than deadlocking, and reject a spike at expiry like any other; let a ≥ 3× better fix inside a poor *starting* anchor's radius (nothing credited from it yet) replace it, crediting only the Doppler integral — a poor anchor mid-run is the last accepted fix of a run in progress and is kept, or replacing it drops real movement on every accuracy step;
3. accumulate distance between accepted fixes: with a Doppler speed ≥ 1 m/s the integral of each fix's reported speed × its own dt since the last accepted fix (capped by the haversine hop + accuracy radius, only while fixes are ≤ 5 s apart; spikes contribute nothing; the sub-threshold part is believed only as far as the hop confirms it, so a receiver saying 0.7 m/s while standing costs the hop, not an accuracy radius per stop), otherwise the haversine hop; reject implausible jumps (> 12 m/s, or beyond max(speed, 2.5 m/s, the last accepted fix's Doppler speed) × dt + both radii — a hop-derived speed is not fed back into the bound: it is self-reinforcing and let multipath bursts in as hops), treat a reported speed of exactly 0 after a Doppler speed as a dropout (`hasSpeed()` false), folded at the last speed and believed only when the position is consistent with the claim — so a walker who stops with the receiver saying 0 credits nothing of it; and if spikes go on for 30 s, or (for a speed-less receiver only — one with Doppler never deadlocks, and a multipath excursion ramping away at 13–18 m/s agrees with itself too) three agree with each other while the raw chain of fixes since the anchor has no hop over 18 m/s (bursts entered faster than 18 m/s break the chain and are ridden out), re-anchor on the current fix crediting the receiver's integral, the raw chain's length (speed-less; capped at 12 m/s × its duration) or the last speed over the interval (Doppler) rather than lose the rest of the workout — this is what measures a speed-less rider at 5 m accuracy (+4.7–5.3 %, hop mode's own noise over-count). Implemented in `DefaultGpsDistanceTracker` (docs/APP.md "Tracker refinements (build 14)" and its "second review" / "third review" / "fourth review" subsections: real walk 155 → 132 m, synthetic 20-minute runs within 0.1 % with Doppler, 1.5 % without, also with the accuracy stepping between bands, at 6–10 m/s without Doppler, with multipath bursts and ramped excursions, with Doppler dropouts, with a creeping receiver and with pauses every 10 s; known limitations listed there with numbers);
4. derive pace from a rolling window (e.g. last 200 m / last 30 s), not from consecutive fixes;
5. push distance/pace/calories to the watch once a second with `FD 44` (we already do this from the bridge);
6. keep the raw track so a distance can be recomputed with a different filter later.

Which of (a) or (b) is the one that's wrong decides the first milestone. Both can be validated against the phone's
own GPS or a known route without any watch involvement.

## 4. Milestones (status 2026-09-06)
- [x] 1 and 2 done in the first build: Kotlin GATT layer + codec (187 unit tests replaying the captures), dashboard, history charts; connects and syncs on launch on the Moto g05.
- [x] 3 workout: implemented and exercised outdoors on the phone (GPS tracker, controller, foreground service, GPX, two-way pause/resume/stop, live HR, 70 sport types).
- [x] Health Connect export: verified on the phone, permissions flow included; sessions, routes, steps, HR, SpO2, distance and sleep all land in Health Connect.
- [~] 4 comfort features: notifications to the watch and find-my-phone are done; weather and watch alarms are not started.
Review of the first build produced 36 findings (2 high: Health Connect export cursor semantics, BLE "Ready" published on a lost link); the fix pass applied 33 (build 00:36 on the phone, connected + synced). Health Connect export verified (172 records). Build 2 (00:41) fixed and independently verified the profile-edit revert, SpO2 chip overflow, export-button layout and midnight rollover (200 unit tests). Workout verified on the phone at 00:52 (start, live HR, stop; GPS poor indoors). A polish pass (workout → Health Connect exercise session, debug packet log, chart nits) is running with an independent verifier. Remaining real-world test: an outdoor walk for distance/pace and stride calibration.

## 4a. Original milestone list

1. **Kotlin GATT layer** ported from `BleService.java` with the packet codec; instrumented tests replay the hex
   captures in `captures/` against the decoder.
2. **Daily dashboard**: connect, sync, show steps/HR/SpO2/sleep; auto-sampling settings.
3. **Workout**: GPS track, distance model, live HR, push to watch, summary; export GPX.
4. **Comfort**: notifications, find-watch, weather, alarms.
5. Optional: upstream protocol notes to Gadgetbridge.

## 5. Open protocol questions (low priority)
- Static HR spot test (`D6 01`) never reports to the phone; the app's timer may just be longer than 75 s.
- Sleep stage code meanings (1-4) — compare against Ryze Fit's sleep screen once.
- `FD 01` bytes after the HR (all zero so far) — probably steps/calories/distance for GPS-less sports.
- Classic SDP UUIDs `0x5536`/`0x2222` on the watch (vendor-specific, unused).


## Top 10 things to do next (2026-09-05, in priority order)

Items 3 and 5–10 do not depend on the outdoor test; only 1, the final confirmation of 2, and 4 need Buzz outside.

Status 2026-09-05 18:xx: the per-fix Doppler tracker refinement was REVERTED (three fix-and-verify rounds each added new regressions); the tracker on main is build 8. The real poor-first-fix issue remains, to be fixed later as a first-anchor-wait-only change. Earlier: Status 2026-09-05 09:00: first real outdoor walk analysed (captures/pixel_outdoor_walk_20260905, 155 m, steady, item 1 partly done — a known-length route is still wanted); sport picker + Health Connect exercise type by sport id shipped in build 7; item 2 done (four defects fixed, 20 m gate relaxed to 60 m, synthetic runs within 2 %); item 8 done and verified on the wrist (build 6; find-my-phone ringer still open); item 10: CI workflow added (`.github/workflows/ci.yml`), stale `D6 10` comment being fixed with the sport-type work; sport picker + Health Connect exercise type by sport id in progress (docs/APP.md "Sport types"). Item 3 done in build 8 (2026-09-05 09:05: track plot on the workout detail, ExerciseRoute on the Health Connect session, verified with the Pixel's real walk on the Moto — captures/app_track_20260905). Item 9 done in build 9 (2026-09-05 09:30: export ledger `hc_export` with content fingerprints + Room v3 migration, so an export with nothing changed writes 0 records and a changed workout re-sends only its session + distance; exporter unit-tested against a fake Health Connect client with read-back; a stride change re-exports the affected daily DistanceRecords — see docs/APP.md "Cursor and ledger"). Item 6 UI part done in build 10 (2026-09-05: History "Sleep" card with a hypnogram + totals line + bed/rise, dashboard one-liner, `ui/SleepChartData.kt` unit-tested on the 32 stages synced on 2026-09-05 — see captures/app_sleep_20260905); the stage-code check against the vendor app is still open. Find-phone ringer done in build 11 (2026-09-05: `findphone/FindPhoneRinger` + Android alerter, Stop action through WatchService, 30 s cap, debug-build adb injection hook; verified on the Moto without the watch — captures/app_findphone_20260905; 300 unit tests), so item 8 is complete apart from seeing a real `D1 0A 01` from the wrist. Item 10 without the watch done in build 12 (2026-09-05: `EditGuard` for the Stride / Watch settings fields, `LabelLayout` placement for the chart "avg" / "goal" labels, adaptive launcher icon with a monochrome layer, signed release build from a git-ignored keystore — docs/APP.md "Ship-shape polish"; 307 unit tests). Still open in 10: pushing to GitHub and the Gadgetbridge upstreaming. Build 13 (2026-09-05) fixed the nine findings of the build-12 review — Settings permission gate now matches the exporter's required set, the MAC field no longer drops Gboard keystrokes, find-phone ring generations, DUMP-guarded debug receiver, session tracks read once, missing stride marker treated as a change, honest export toast, no destructive Room fallback, effective sport names in the workout list, height changes trigger the distance re-export — docs/APP.md "Review fixes (build 13)"; 324 unit tests. Build 14 (2026-09-05): the tracker refinements from the first real walk — per-fix Doppler integral, first-anchor quality, poor starting anchor replaced — after a review of the first draft found its re-anchor rule firing mid-run and dropping 10–50 % of a Doppler run whose accuracy stepped between bands; narrowed to an uncredited starting anchor, with regression tests for the band patterns (all within 1 %), the real walk replays at 136.4 m — docs/APP.md "Tracker refinements (build 14)"; 339 unit tests.

1. **The outdoor acceptance test.** Walk and then run a known route of at least 1 km with the watch on and the phone in
   a pocket. Compare the app's distance and pace with the known length, look at the raw track, and check that the
   day's steps keep rising through the workout. This is the exact scenario the vendor app failed (0.0 km after a
   20-minute run) and nothing replaces doing it.
2. **Act on the distance audit.** Apply whatever the audit and its synthetic 20-minute-run tests confirm (prime
   suspects: the hard 20 m accuracy cut, and any dependence on the phone reporting a Doppler speed). Keep the
   synthetic-run tests as permanent regression tests.
3. **Draw the track.** A Canvas track plot on the workout detail (accepted fixes as a line, rejected as dots, km
   marks) and an `ExerciseRoute` on the Health Connect session so Health Connect shows the run on a map. No map SDK.
4. **Stride calibration end to end.** After the outdoor test, calibrate walking and running strides from GPS, then
   confirm the daily distance uses them and that Health Connect's daily DistanceRecord follows.
5. **Background reliability over a full day.** Reconnect after phone reboot, app kill, Bluetooth off/on, watch out of
   range and back; the 30-minute periodic sync; battery use of the foreground service; ask for battery-optimisation
   exemption if the link drops overnight.
6. **Sleep.** Confirm the stage codes against the vendor app for one night (it is still installed on the Pixel), fix the
   1→deep / 2→light / 3→REM / 4→awake mapping if wrong, and give sleep its own history card and Health Connect stages.
7. **Record watch-initiated measurements.** The watch streams HR (`E5 11`) and reports SpO2 results when a test is
   started on the watch; store those in history instead of dropping them, and show them as spot samples.
8. **Phone notifications to the watch** (`C5`: app-type byte + UTF-16 chunks) with per-app filtering, plus the
   find-my-phone push (`D1 0A`) and the vibrate-watch button. The most-used daily feature after the numbers.
9. **Health Connect hygiene.** Tighten the export cursor so finished workouts are not re-sent, add a read-back check
   in the exporter tests, and re-export the day's distance when the stride changes.
10. **Ship-shape.** Fix the stale `D6 10` comment in the Kotlin codec, the `remember(flowValue)` pattern left in the
    Stride and Watch settings sections, the "avg" label overdraw; a proper launcher icon and a signed release build;
    push the repo to a private GitHub and add a workflow that runs the Python and Kotlin unit tests; and upstream the
    protocol corrections (year byte, `D6 02` live HR, feature bitmap) to Gadgetbridge.

## Design notes 2026-09-05 (from the first real run/walk)

- **Exercise type of a run/walk.** Health Connect has no "run/walk" type; it forces one. The app's speed tie-breaker
  downgrades a type-1 (Outdoor Running) workout to WALKING below 2.0 m/s (5:00/km... i.e. slower than 8:20/km), so the
  17:20 run/walk at 9:24 min/km avg was filed as Walking. Plan: let the user pick/confirm the exercise type on the
  workout (and don't discard the user's chosen sport to a heuristic); a run/walk should be recordable as Running. The
  session still counts as cardio regardless of label because the HR series (130-160) earns heart points.
- **Always-on GPS breadcrumb (Buzz's idea) — BUILT as build 11 (2026-09-06), see docs/APP.md.** Optionally log a GPS fix ~every 60 s all day so a workout can be
  reassembled even if workout tracking fails, discarding fixes while the phone is stationary. Battery-sane design:
  gate on the Activity Recognition API / step sensor so GPS is only requested while the phone reports walking/running/
  cycling (that IS the "discard when stationary"), not a blind 60 s timer that wakes the GPS radio while still. Needs
  ACCESS_BACKGROUND_LOCATION (Play scrutiny), a persistent record, and off-by-default (privacy + battery). NOTE: the
  direct fix for today's failure is simpler — keep recording GPS during a workout even while paused (queued), so a
  missed resume never loses the track. The all-day breadcrumb is a separate, larger opt-in feature.

## Stuck-in-exercise-mode detector — per-sport signature design (2026-09-06)

Four observed signals during an active workout, each reduced to an "active?" boolean over a rolling ~3-5 min window:
- **steps**: watch session step counter (FD 01 bytes 7-9) rising, or phone TYPE_STEP_COUNTER cadence.
- **gps**: GPS speed / displacement above noise.
- **hr**: heart rate elevated above the user's resting baseline (not an absolute number).
- **motion**: phone/wrist accelerometer variance (significant motion) — needed to tell real low-HR low-step activity
  (yoga, stretching) from lying still.
Plus **time of day** and **resting-HR level** as a confidence/urgency modifier, not a trigger.

Per-sport EXPECTED indicators (the workout is "genuinely live" if ANY expected indicator is active):
| sport | steps | gps | hr | motion |
|---|---|---|---|---|
| Outdoor Running / Trail / Walking / Hiking | ✓ | ✓ | ✓ | ✓ |
| Treadmill / Indoor Running | ✓ | – | ✓ | ✓ |
| Cycling outdoor / Rowing on water | – | ✓ | ✓ | ✓ |
| Spinning / stationary bike / Rowing machine | – | – | ✓ | ✓ (cadence) |
| Yoga / Stretching / low-HR floor work | – | – | – | ✓ |
| Strength / HIIT | ~ | – | ✓ | ✓ |

Decision: **likely-stuck = none of the sport's expected indicators active for the sustained window.** Because expectations
are per-sport, the hard cases the user named resolve: a rowing machine (high HR + stroke cadence, no GPS) stays "live" on
HR; outdoor running/rowing stays live on GPS; yoga stays live on accelerometer motion even with low HR and no steps; only
a workout where literally nothing moves and HR is flat is flagged.

Confidence/urgency modifier: night (22:00-06:00) + HR at true sleeping levels + zero motion = near-certain accidental →
short grace, act fast. Daytime with just-no-activity = longer grace, gentler.

Escalation: (1) speak + high-priority notification "Workout running but no activity — tap to stop"; (2) auto-stop after the
grace period if unacknowledged. Never auto-stop an app-initiated workout without at least the notification. Also speak
"workout started" on a WATCH-originated start so an accidental start announces itself immediately.

Built as build 10 (2026-09-06) — see docs/APP.md; the night guard is now its HIGH-urgency case.
