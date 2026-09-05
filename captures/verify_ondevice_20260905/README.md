# Independent on-device verification, 2026-09-05 10:03–10:2x (Moto g05 MOTO_SERIAL, Android 15, watch absent, Bluetooth off)

Build: `./gradlew :app:assembleDebug :app:testDebugUnitTest` then a forced re-run (`--rerun --no-build-cache`):
307 unit tests, 0 failures (32 classes). Installed with `tools/app_smoke.sh --no-build 25`
(`captures/app_smoke_20260905_100426/`); `installed_md5.txt`: installed base.apk md5 == built app-debug.apk md5
(6e9ce0ace5bb07a64f240ddce28a6277). `adb logcat -b crash`: no entries for au.buzz.ryzewave during the run.

Screenshots (in order taken):
- `00_home.png` dashboard after launch.
- `01_workout_tab.png`, `02_workout_list.png`: Workout tab and the past-workout row "Outdoor Running · Sat 5 Sep 08:35 / 02:38 · 155 m".
- `03_workout_detail_top.png`, `04_workout_detail_scrolled.png`, `05_workout_detail_bottom.png`: the detail with the Track card
  (polyline of the 86 accepted fixes, grey dots for the 60 rejected, green start / red end, 10 m scale bar, north arrow, legend)
  drawn from the Pixel walk that is in the phone's database (workout 1, 146 track points, 155 m).
- `06_history_top.png`, `07_history_bottom.png`: History with the fifth "Sleep" card (hypnogram, "6 h 19 m asleep · deep 1:55 ·
  light 4:12 · REM 0:12 · awake 0:31", "Bed 23:27 · rise 06:17 · Fri 4 Sep", stats row, "Last night's stages (32)").
- `08_history_sleep_tooltip.png`: tap on a deep block -> "01:28–02:00 · Deep 32 min"; `09_history_sleep_stages.png`: expanded list.
- `10_settings_top.png` .. `19_settings_mac_restored.png`: Settings edit sequence, see below.
- `20_app_info_icon.png`, `21_app_drawer.png`: the adaptive launcher icon in App info and in the launcher drawer.
- `22_findphone_ringing.png`, `23_findphone_shade.png` (notification "Find my phone / Your Ryze Wave is lookin… / Stop"),
  `24_findphone_after_stop.png`, `25_findphone_after_timeout.png`; `findphone_steps.txt`, `findphone_logcat.txt` (+ `_full`).
- `37_export1.png`, `38_export2.png`; `export5_steps.txt`, `export5_logcat.txt` (+ `_full`): the two consecutive exports (see below).
  Earlier attempts `export_steps.txt` (copy step failed, nothing changed on the phone), `export2_*` (database pushed in
  rollback-journal mode: Room could not switch it to WAL, "database is locked", and the ledger/cursor writes of that
  process never reached the file — a test-harness artefact, see "Database replacement" below), `export3_*` / `export4_*`
  (replacement raced with the process the system restarts for the notification listener; `export3` ended in a
  SQLiteDiskIOException crash of the app and an emptied database — my doing, not the app's). `32_after_crash_state.png`,
  `33_home_after_restore.png` belong to those attempts.

Settings edit sequence (uiautomator dumps in `export2_steps.txt`-style logs were read live; the values below are what the
EditText nodes reported):
1. Appended text to the MAC field -> "78:02:B7:37:91:E5FF12ABFF" (unsaved).
2. Typed "0.9" into Walk stride (unsaved).
3. Changed Weight W -> W+1 and tapped "Save profile" (DataStore write, profile flow re-emits, Stride section recomposes):
   MAC field still "78:02:B7:37:91:E5FF12ABFF", Walk stride still "0.9" (`13_…`, `14_…`).
4. Stride "Save": field re-seeded to "0.900", "In use: walk 0.900 m", logcat `Health Connect export after stride change: … distance=3, inserted=3`.
   "Use defaults": field cleared, "In use: walk 0.746 m", another `distance=3` export (`15_…`, `16_…`).
5. Weight restored to W and saved. Invalid MAC Save -> snackbar "MAC must look like 78:02:B7:37:91:E5", field kept (`17_…`).
6. MAC "aa:bb:cc:dd:ee:01" typed + Save -> field "AA:BB:CC:DD:EE:01", `WatchService: watch address changed 78:02:B7:37:91:E5 -> AA:BB:CC:DD:EE:01`
   (`18_…`); restored to 78:02:B7:37:91:E5 and saved (`19_…`, `watch address changed AA:BB:CC:DD:EE:01 -> 78:02:B7:37:91:E5`).
7. Keystroke trials in the MAC field (adb `input text` / `input keyevent`, Gboard): lowercase letters are intermittently
   dropped ("abcdefabcdef" -> "ABCDEFBCEF", keyevents a b c d e f -> "ACDEF", "78:02:b7:37:91:e5" -> "78:02:B:37:91:E"),
   while digits in the same field and the Height field (no transform) never drop a character in the same trials.
   Cause: `onValueChange = { text = it.uppercase(...) }` rewrites the value under the IME's composition. Pre-existing
   (the uppercase transform is in HEAD too), not introduced by the EditGuard change.

Find-phone (debug broadcast `au.buzz.ryzewave.debug.FIND_PHONE`): see `findphone_logcat.txt` —
10:16:03 start -> notification posted, alarm volume 6 -> 7, alarm ringtone playing (dumpsys audio: MediaPlayer state:started usage=USAGE_ALARM), vibrating (dumpsys vibrator_manager: opPkg=au.buzz.ryzewave usage ALARM);
10:16:12 notification "Stop" tapped -> `stopped (user)`, volume restored to 6, no started player, notification id 1002 gone;
10:16:16 start, 10:16:20 duplicate start `already ringing, ignored`, 10:16:46.678 `stopped (timeout)` (30.06 s after start);
10:16:50 start, 10:16:53 start=false -> `stopped (watch)`. No FATAL in the log.

Two consecutive Health Connect exports (`export5_steps.txt`, `export5_logcat.txt`): to make the first export write
something, the ledger rows `workout-1` and `wdist-1` were deleted from a consistent copy of the phone's database
(everything else identical: 20 steps_hour, 350 hr, 167 spo2, 62 sleep_stage, 1 workout, 146 track_point) and the copy was
put back with no process alive. Then Settings > "Export now" twice:
- 10:29:43 `exercise route attached to workout-1: 86 locations` / `exported 2 records (... distance=1, workouts=1, routePoints=86)
  from 0, 228 unchanged skipped, cursor -> 1788568183585, failed 0` — exactly the session and its GPS distance record.
- 10:29:57 `nothing to export: 230 candidate records unchanged since their last export (from 0)`; UI "Last export 10:29: nothing new".
- Database afterwards (pulled with its WAL): integrity ok, all tables as before, `hc_export` 231 rows with `workout-1` /
  `wdist-1` stamped 10:29:43 and the `stride` marker refreshed 10:29:57, `hc-export` cursor 10:29:43. No journal-mode
  warnings, `logcat -b crash` empty for the run.
- Earlier the same morning (Settings sequence, step 4): a stride change and "Use defaults" each produced an automatic
  `Health Connect export after stride change: ... distance=3, inserted=3` (the three daily DistanceRecords only).

Database replacement (lesson for the next verifier): `am force-stop` does not keep the app dead — the system re-binds
`WatchNotificationListener` within a second and starts a new process, which opens the database. Replacing
`databases/ryzewave.db` under that process corrupts it (SQLITE_IOERR_SHORT_READ crash, then Room's
`fallbackToDestructiveMigration` left every table empty). What worked: `cmd notification disallow_listener
au.buzz.ryzewave/au.buzz.ryzewave.notify.WatchNotificationListener`, `am force-stop`, confirm `pidof` stays empty for 4 s,
`run-as` copy + rename with `-wal`/`-shm` removed, `allow_listener` again (which starts the process), then launch. Push a
file whose header is already in WAL mode (`PRAGMA journal_mode=WAL` on the copy first); a rollback-journal file makes
Room log "database is locked" on every connection. The listener setting was restored; nothing else on the phone was changed.
The phone's database ends the session with the same health data it started with plus the refreshed ledger rows.

Health Connect after the exports (`39_hc_exercise_list.png`, `hc_check2_steps.txt`): Data and access > Exercise > Today
lists the walk once — "8:35 AM - 8:37 AM • Buzz's Ryze Wave, Walking • Outdoor Walking, 0.16 km, 2:38, avg HR 99,
max HR 105, 14 kcal", "Exercise map route available" — although the session was upserted four times today (build 8
export, two exports of the `export2` attempt, `export5`), i.e. the clientRecordId upsert de-duplicates. The two older
"Ryze Wave walk" entries (1:04 AM, 7:22 AM) are the earlier indoor test workouts from before the Pixel database was
installed. Observation: the app's own list/detail title says "Outdoor Running" for this type-1 workout while Health
Connect's title uses the speed tie-breaker ("Outdoor Walking"); cosmetic, pre-existing since build 7.
