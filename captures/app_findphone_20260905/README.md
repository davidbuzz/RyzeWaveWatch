# Find-my-phone ringer verification (build 11, 2026-09-05, Moto g05, watch absent)

What was done on the phone (scripted, `steps.txt` has the timestamps; `logcat.txt` the app's lines):

1. App launched by `tools/app_smoke.sh --no-build 25` (phone Bluetooth off, watch out of range, so the link stays
   in "Bluetooth is off; waiting" — connection failures are expected and irrelevant here).
2. `adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.FIND_PHONE --ez start true`
   injects `WatchEvent.FindPhone(start = true)` into `App.graph.findPhone.onEvent` — the same entry point the
   `WatchApi.events` collector in `GraphFactory` feeds when the watch sends `D1 0A 01`.
   `01_ringing_home.png` (heads-up), `02_ringing_shade.png` (the notification with its Stop action),
   `dumpsys_audio_ringing.txt` (the MediaPlayer on USAGE_ALARM), `notification_dump_ringing.txt`.
3. `tools/app_tap.sh Stop` taps the notification's Stop action → `WatchService` `ACTION_FIND_PHONE_STOP` →
   `FindPhoneRinger.stop("user")`. `03_after_stop.png`; `notification_count_after_stop.txt` = 0.
4. Second injection, plus a duplicate start 3 s later (ignored), then nothing for 32 s: the 30 s timeout ends it.
   `04_ringing_again.png`, `05_after_timeout.png`; `notification_count_after_timeout.txt` = 0.
5. Third injection followed by `--ez start false` (what `D1 0A 00` from the watch becomes): stopped by the watch.
   `notification_count_after_watch_stop.txt` = 0.

The alarm-stream volume is raised to the maximum for the ring and restored afterwards (logcat: "alarm volume raised
6 -> 7", "restored to 6").
