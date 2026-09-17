# Pixel morning run, 2026-09-06 09:42–10:50 — bug report and repair

Build on the Pixel during the run: build 9 (488d082b, before the play-button fix and the stuck detector).

## Root cause of the blank pace / 0 distance: LOCATION SERVICES WERE OFF
`location_mode = 0` on the Pixel (not battery saver, not a policy; no app can disable system location). So the phone
GPS gave zero fixes, distance is 0, pace is "--:--", and the watch's own distance is 0 (it gets distance from the phone
GPS). Google Fit shows 4.43 km today but that is STEP-ESTIMATED, no GPS route — location was off for everyone, so there
is no track to import. Buzz's rule (memory location-off-is-critical): the app must block a workout from starting until
location is on and verified.

## The split workout (bug 1)
Announcements show three start/stop cycles: 09:42:15–10:01:16 (#5), 10:01:37–10:37:28 (#6, endTime was NULL — the stop
never finalised it), 10:39:53–10:50:02 (#7). The 09:42–10:01 → 10:01:37 gap is 21 s = the improper split. The app sent
the 10:01 stop itself (TX FD 00) with no clear trigger, while 13-byte FD 22/FD 33 pause/resume packets arrived every
1–5 s carrying what looked like junk payloads, which build 9 mirrored into the app as ~15 spoken "paused/resumed" events.

> **Correction (2026-09-17).** "The watch was flooding" was wrong, and it cost us a bad fix. This capture holds 28 FD 22
> + 33 FD 33 log lines against 2676 FD 44 and 2882 FD 01 — about 1 % of the traffic, ~30 real events in 76 minutes — and
> 46 of the 61 lines fall in the four minutes 09:42–09:45, with nine in the other 65 minutes (several of those echoes of
> the app's own commands). "Every 1–5 s" describes one minute, 09:43. The payloads were not junk either: the watch can
> only replay the last FD 44 it received, and the app had just paused and stopped sending, so every packet carried the
> same frozen bytes (captures/bridge_20260915_watchclock.md). With nobody touching the watch, 85 s including a 40 s
> FD 44 silence produced ZERO FD 22/FD 33 (captures/bridge_20260915_183348.txt). The irregular 1.2–9.4 s gaps here were
> Buzz pressing the button at an app that was not responding. The 8-second debounce built on this reading was removed
> on 2026-09-17; see docs/APP.md, "Watch pause/resume is immediate again".

## Repair
Merged #5+#6 → one workout: 09:42:15–10:37:28, 55 m 12 s, 3971 steps (watch counters summed), HR avg **141** / max 173
from 2334 stored samples (Buzz: HR was 130–150 the whole run), distance 0 (location was off). Deleted the 41-s #5 stub;
left #7 separate (2.4-min gap). Pushed to the Pixel (Room migrated v4→v5) and re-exported to Health Connect.

## Crash found and fixed
Launching build 11 on the Pixel crash-looped: `BreadcrumbService` — turning the trail OFF called `stopService` via a
start-intent, which CREATED the service (onCreate → startForeground type=location) and threw SecurityException because
location was off. Fixed: `stop()` uses `context.stopService` (never creates it); `startInForeground()` catches the
refusal and `stopSelf()` instead of crashing. App launches clean, no BreadcrumbService running.

## Fixes still to build
1. Block workout start when location is off (memory location-off-is-critical): loud message + button to system location
   settings + re-verify.
2. Step-based (stride) pace/distance fallback so pace is never a bare "--:--".
3. ~~Stop mirroring the watch's junk pause/resume flood (debounce / distinguish a real button press).~~ Dropped — see
   the correction above; there was no flood. Watch pause/resume is applied immediately (2026-09-17).

## Removed screenshot (2026-09-06 privacy audit)
`fit_journal2.png` (Google Fit journal with a small map thumbnail of the real walking route and the day's routine)
was purged from the repo and its history.
