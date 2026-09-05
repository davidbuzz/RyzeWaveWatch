# Tracker refinements (build 9) smoke on the Moto g05, 2026-09-05 11:25

What was done: installed the debug build with the refined `DefaultGpsDistanceTracker` (per-fix Doppler integral,
first-anchor quality, poor-anchor replacement) via `tools/app_smoke.sh --no-build 25`, opened Workout → Past workouts →
the Pixel walk (workout #1, 08:35) and took `detail.png`. No watch, no new workout: the stored distance (155 m) is
unchanged by design (distance is stored, not recomputed); the replay of the same 146 fixes through the new tracker gives
133.7 m (`RealTrackTrackerTest`). `logcat.txt`: 0 FATAL EXCEPTION lines, app pid alive, MainActivity resumed.
