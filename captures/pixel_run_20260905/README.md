# Run/walk with the Pixel 9a, 2026-09-05 17:20–17:52 (build 8 + draft tracker, installed 15:15)

What Buzz did: started a workout from the app (Outdoor Running), about 30 minutes of hard running/walking with heart
rate 130–160, paused/unpaused a couple of times (one on the watch at ~12 s), stopped it from the app at 17:52. The
watch's time/distance display froze after ~10–12 s; pace stayed "--:--" and km 0 the whole time. The watch itself
showed the session as unpaused while moving.

Pulled with `tools/pull_app_data.sh`-style commands at 17:56 (8 MB log buffer set at 17:13, so the whole run is in
`logcat.txt`, git-ignored; `ryzewave.db` git-ignored).

## Timeline (from the log)
- 17:20:54 app → `FD 11 01 01` start; watch echoes. Pushes `FD 44` once per second begin.
- 17:21:12 **watch → `FD 22 …` pause** (13 B: 00:00:10, 1 kcal, 0.01 km, pace 21:58). **App ignores it** (no handler
  for watch-initiated control packets).
- 17:21:26 app → pause, 17:21:28 app → resume, 17:21:32 app → pause (phone buttons). Pushes stop at 17:21:32.
- 17:21:44 **watch → `FD 33 …` resume. App ignores it** and stays paused for 30 minutes; GPS updates are off while
  paused, so no fixes are recorded after 17:21:31.
- 17:31:34–17:32:48 four more watch-side pause/resume pairs (Buzz trying to unfreeze the display); ignored.
- 17:20:59–17:51:58 the watch streams 1809 realtime packets `FD 01 <hr> … steps24 …`: HR 86–166 (≥ 130 from 17:23:24
  to the end), **steps field rising to 3933** — the watch counts steps per session and reports them here.
- 17:52:01 app → `FD 00 01 01` stop; watch echoes. The workout row still has `endTime = NULL`, duration 36 s,
  distance 12 m, avgHr 90: the stop path did not persist the final row (see the app log excerpt below).

## What survived / was lost
- Stored: 1805 WORKOUT heart-rate samples for the whole run; 30 GPS fixes (17:20:56–17:21:31, 9 accepted, 12 m).
- Lost: GPS track and distance for 30 minutes (app paused → location updates off).

## Defects
1. Watch-initiated pause/resume/stop (`FD 22/33/00` sent by the watch) are not applied to the app's workout state.
2. Stop from the paused state did not finalise the workout row.
3. GPS updates stop while paused, so a missed resume loses the track.
4. The watch's per-session step count (realtime packet bytes 7–9) is not used.
Requested: spoken "workout paused" / "workout resumed" on the phone for every pause/resume, whichever side triggered it.
