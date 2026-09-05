# Health Connect export ledger — on-phone check (Moto g05, build 9, 2026-09-05 09:26-09:29)

Watch absent (Bluetooth off on the phone, "Bluetooth is off; waiting" in logcat), so everything here is the
export path only. The phone database is the one from build 8 (the Pixel's outdoor walk: 20 step hours, 350 HR,
167 SpO2, 62 sleep stages, 1 workout with an 86-point track), schema v2 before the install.

What was done on the phone:
1. `tools/app_smoke.sh --no-build 25` installed build 9 (Room migration 2 → 3 creates the empty `hc_export` ledger;
   no crash; `captures/app_smoke_20260905_092600/`).
2. Settings → "Export now", waited 20 s, "Export now" again (`logcat_exports.txt`, screenshots
   `after_first_export.png`, `after_second_export.png`):
   - first export (ledger empty after the migration): `exported 230 records (steps=20, heartRate=36, spo2=167,
     distance=4, sleep=2, workouts=1, routePoints=86) from 0, 0 unchanged skipped, failed 0`
   - second export: `nothing to export: 230 candidate records unchanged since their last export (from 0)` —
     no `insertRecords` call at all, the Settings card reads "Last export 09:28: nothing new".
3. Settings → Stride: typed 0.8 into "Walk stride", Save (`logcat_stride_change.txt`, `after_stride_change.png`):
   `GraphFactory: Health Connect export after stride change: ... distance=3, inserted=3` — exactly the three daily
   DistanceRecords (2026-09-03/04/05), nothing else.
4. "Use defaults" put the stride back (`logcat_stride_reset.txt`): again exactly 3 DistanceRecords; the card shows
   "In use: walk 0.746 m, run 0.994 m" as before.
5. `hc_export_table.txt`: the database pulled afterwards (`run-as … cat databases/ryzewave.db`) has
   `PRAGMA user_version = 3`, 231 ledger rows (230 records + the `stride` marker), the three `dist-*` rows and the
   marker stamped 09:29:15 (the reset), `workout-1` / `wdist-1` still stamped 09:27:38 (the first export).

Files: `logcat_full.txt` (everything between the two taps), `logcat_exports.txt`, `logcat_stride_change.txt`,
`logcat_stride_reset.txt`, `hc_export_table.txt`, three screenshots.
