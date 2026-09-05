# Sleep data bug and repair, night of 2026-09-05 → 06 (Pixel 9a, build f0abe70)

What Buzz reported: the app showed 3 h 12 m of sleep for a night he slept 10+ hours (rough estimate 19:30–06:30). He
remembers waking to a bright watch screen and covering it with his hand; the unintended touches put the watch into an
exercise mode.

Pulled with `run-as cat` at ~08:50 (`ryzewave.db` and `logcat.txt` are git-ignored).

## Findings
- The watch only STAGED sleep from 00:26 to 06:11, with a gap 01:22–02:59 (16 rows, 248 min, 35 min awake).
- 594 LIVE heart-rate samples from 19:32 to 22:00 (dense: 228 and 249 per hour at 20:00 and 21:00) = the watch was in a
  live/exercise mode with the screen on; the watch does not run sleep detection in that mode.
- Heart rate (AUTO/HISTORY, every 5–10 min) survived all night: 63–78 bpm from 22:00 to 07:30 (106 samples, avg 69);
  74–85 bpm from 19:40 to 22:10 (screen-on period). Steps: 0 in the 21:00 and 23:00 hours, none until 04:00.
- SpO2 overnight: 92–99 %; 92–93 % in the 21:00–23:00 and 07:00 hours (marginal, not fine — Buzz's note), nothing below 92.
- No workout row reached the app (the accidental mode was watch-side).

## Repair (09:05, before the new build)
Three `sleep_stage` rows with stage code 5 (generic asleep, not deep/light/REM) filled 22:15–00:26, 01:22–02:59 and
06:11–07:00 around the watch's real stages: night = 22:15–07:00 (525 min span), 490 min asleep, 35 awake. The installed
build counts unknown codes toward the total and exports them as STAGE_TYPE_UNKNOWN; the export wrote one sleep record and
Buzz confirmed Google Health went from ~3 h to ~8 h. `upsertSleep` never deletes a range, so a re-sync cannot remove
these rows.

## Follow-ups (in progress)
Proper generic-asleep support in the app (own lane/label), a debug SLEEP broadcast for future repairs, and a night
workout guard (watch-started workout at night with resting HR and no movement → warn, then auto-stop).
