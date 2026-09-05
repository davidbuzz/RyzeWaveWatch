# Track plot + Health Connect exercise route, verified on the Moto g05 (2026-09-05 09:01–09:05)

PLAN item 3 ("Draw the track"). The watch was out of range, so the Moto's app database was replaced with the Pixel's
real outdoor walk (`captures/pixel_outdoor_walk_20260905/ryzewave.db`: workout #1, 146 GPS fixes, 86 accepted,
155 m) — `adb push` to `/data/local/tmp`, `run-as au.buzz.ryzewave cp` into `databases/ryzewave.db` with the
`-wal`/`-shm` files removed, md5 checked. Build 8 (unit tests 261, 0 failures) installed with `tools/app_smoke.sh
--no-build 25`, which now also grants `android.permission.health.WRITE_EXERCISE_ROUTE` (granted=true in dumpsys).
Bluetooth on the Moto is off ("Bluetooth is off; waiting" in the log) — expected, nothing was changed on the phone.

## Files
- `detail_top.png` / `detail_bottom.png` — first build of the plot: Workout > "Outdoor Running · Sat 5 Sep 08:35". The
  scale bar overlapped the green start marker (the track fills the padded area), fixed in the next build.
- `detail_track.png` / `detail_track_scrolled.png` — the shipped version: accepted fixes as the polyline, rejected as
  grey dots, green start / red end, 10 m scale bar and north arrow in their own strip under the track.
- `settings_after_export.png` — Settings after "Export now".
- `logcat_export.txt` — the export log. The lines that matter:
  `I/HealthConnectExporter: exercise route attached to workout-1: 86 locations` and
  `I/HealthConnectExporter: exported 230 records (ExportCounts(steps=20, heartRate=36, spo2=167, distance=4,
  sleep=2, workouts=1, routePoints=86)) from 0, cursor -> 1788563005886, failed 0`.
- `hc_exercise_list.png` — Health Connect > Data and access > Exercise: "8:35 AM - 8:37 AM • Buzz's Ryze Wave, Walking •
  Outdoor Walking" with the "Exercise map route available" icon.
- `hc_exercise_entry.png` — the entry's details page with the route thumbnail (the shape of the loop).

Route points = the 86 accepted fixes (all lie inside the session and have distinct times); the 60 rejected fixes
stay out. The auto-export at launch reported "nothing new" because the Pixel database carries its own `hc-export`
cursor; "Export now" (`exportAll`) wrote everything.
