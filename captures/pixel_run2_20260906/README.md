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
the 10:01 stop itself (TX FD 00) with no clear trigger, while the WATCH was flooding pause/resume (13-byte FD 22/FD 33
every 1–5 s, carrying junk payloads) which build 9 mirrored into the app as ~15 spoken "paused/resumed" events.

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
3. Stop mirroring the watch's junk pause/resume flood (debounce / distinguish a real button press).
