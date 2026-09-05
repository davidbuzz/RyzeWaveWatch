# Outdoor walk on the Pixel 9a, 2026-09-05 08:35 (build 6)

What Buzz did: started a workout from the app's Workout tab on the Pixel 9a (build 6, installed 08:30), walked
outside for about 2.5 minutes, stopped it. From the user's perspective it "appeared to perform as it should".
Pulled with `tools/pull_app_data.sh PIXEL_SERIAL pixel_outdoor_walk_20260905` at ~08:50 (5-minute USB window),
analysed with `tools/analyse_workout.py ryzewave.db`.

Files: `ryzewave.db` (Room DB, WAL checkpointed), `logcat.txt` (git-ignored; tail of the walk only — Gadgetbridge is
also installed on the Pixel and floods the log), `workout_tab.png`, `batterystats.txt`, `summary.txt`.

## Workout #1
- 08:35:14 → 08:37:56, 158 s, **155 m**, pace 16:58/km, avg HR 99 (max 105, 151 WORKOUT samples at ~1 Hz from
  `FD 01 <hr>` pushes), 14 kcal, sport type 1 (= Outdoor Running on the watch; the picker is build 7).
- 146 GPS fixes at 1 Hz (first 2 s after start, last at +161 s), 144 with Doppler speed, median speed 1.03 m/s.
  Accuracy median 6 m, p90 9 m; two fixes > 60 m (the first at 52 m, one at 132 m).
- 86 accepted, 60 rejected: 2 for accuracy > 60 m, 58 by the slow-speed jitter rule (reported speed 0.8–0.99 m/s,
  below the 1.0 m/s Doppler threshold, so the tracker ran in hop mode and absorbed sub-radius hops). Distance still
  advanced every 10 s window (no minute with < 20 m), no stall, no 0.0 km — the vendor symptom did not occur.
- Cross-checks: raw haversine over all fixes 192 m, over accepted fixes 176 m, stored cumulative 155 m, Doppler
  integral ≈ 163 m. Start→end straight line 44 m, bounding box 36 × 40 m (a short loop).
- Watch received per-second `FD 44` pushes; the last one: 00:02:37, 14 kcal, 0.16 km, 17:13/km (echoed by the watch).

## Steps: open question
The watch's realtime hourly pushes (`B1`) for the 08:00 hour read 66 → 67 (08:38:23-25) and 77 → 78 (08:41:17-19),
i.e. the watch counted only ~70–80 steps in the hour that contained a 158-s, 155-m walk (≈ 200 steps expected).
Either the watch arm was not swinging (phone held in the watch hand while watching the screen), or the watch's
pedometer lags/pauses during a sport session. To settle: compare with the watch face's own step total for the day,
and repeat with the phone in a pocket. Daily total in the DB at 08:41: 160 steps (58 at 06:00 + 78 at 08:00 + …).

## Coordinates
The unit-test fixture made from this walk (`ryzeapp/app/src/test/resources/pixel_outdoor_walk_20260905_track.csv`)
is shifted by +0.0180° latitude and −0.0250° longitude (about 3.2 km displacement); the track length changes by
1.4 cm over 192 m. Per the repo rule (CLAUDE.md "GPS data in the repo") every log gets its own shift of at least 1 km.
The pulled database with the real coordinates is git-ignored.
