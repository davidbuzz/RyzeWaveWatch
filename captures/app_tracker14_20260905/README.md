# Tracker refinements (build 14) on the Moto g05, 2026-09-05 11:45

What was done: the review of the build-9 draft of `DefaultGpsDistanceTracker` found rule 1c (re-anchor without credit)
firing mid-run and dropping the Doppler integral whenever a ≥ 3× better fix landed inside a > 20 m anchor's radius.
The rule is now limited to an *uncredited starting anchor* (first fix, wait-expiry anchor, re-anchor after `markGap()`)
and credits the capped Doppler integral when the receiver reports ≥ 1 m/s. Fresh `--rerun-tasks` unit run: 339 tests,
0 failures (35 result files, all written 11:44:17). Debug APK built 11:43:57, installed with
`tools/app_smoke.sh --no-build 25` (`captures/app_smoke_20260905_114450`, `lastUpdateTime=2026-09-05 11:45:00`),
then Workout tab → scrolled to Past workouts → the Pixel walk (workout #1, 08:35).

Files:
- `old_vs_new.txt` — build 8 (git HEAD) vs the build-9 draft vs build 14 on the finding's scenarios, from a temporary
  comparison test (deleted afterwards). 25 m run with every 10th fix at 8 m: 3597 → 3240 → **3597 m**; 30/9 m
  alternating: 3597 → 1800 → **3597 m**; 1.2 m/s walk with 25↔8 m every 5 s: 1438.8 → 1290 → **1432.8 m**. Hop mode
  (no Doppler) with varying accuracy differs from build 8 by ≤ 0.4 %. Stop/start cost of sub-threshold speeds:
  +5.8 m per stop at 5 m accuracy, +24.1 m at 25 m. Real walk replay 133.7 → **136.4 m** (0 re-anchors).
- `old_vs_new_round2.txt` — the third review's replay (2026-09-05, later the same day): build 8 (git 1c28d2e) vs the
  working tree after the second and third reworks on 194 scenarios (round-1 table, multipath bursts, speed-less riders
  at 5–30 m, wait-expiry spikes, pause cadences, NaN speed, under-reporting receivers, the O1/O2 variants that were not
  adopted). Bursts of 4–25 off-track fixes and the 21–30 m riders equal build 8; pause every 10 s in 25 m 0.0 →
  3213.0 m; real walk replay 130.7 m (unchanged). Details and the remaining limitations in docs/APP.md "The third review".
- `old_vs_new_round3.txt` — the fourth review's replay (2026-09-05): build 8 (git 1c28d2e) and the WIP HEAD (99669dd) vs
  the working tree after round 3 on 412 scenarios (the earlier tables, Doppler dropouts, ramped multipath excursions,
  hop-mode wait exits and pause cadences, stops inside the wait, duplicated timestamps, under-reporting receivers).
  Dropouts 3597.0 m at 6/15/25 m (were −5 to −30 %), ramps equal to build 8, hop-mode exits within 1 % of build 8,
  STOPS with the receiver reporting 0 exact; real walk replay 132.1 m. Details in docs/APP.md "The fourth review".
- `workout_tab.png` — Workout tab scrolled to "Past workouts" (the walk listed as 02:38 · 155 m · 16:58 /km · avg 99 bpm).
- `detail.png` — the walk's detail at 11:46: stored 155 m, 86 of 146 GPS fixes, track plot. Unchanged by design
  (distance is stored, not recomputed); the same 146 fixes replayed through the build-14 tracker give 136.4 m
  (`RealTrackTrackerTest`).
- `logcat.txt` — 0 `FATAL EXCEPTION` lines, app pid 15801 alive, MainActivity resumed. No watch (Bluetooth off on the
  Moto), no new workout recorded.
