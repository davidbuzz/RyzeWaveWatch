---
name: location-off-is-critical
description: Phone location services OFF (no GPS) is a SEVERE CRITICAL error; the app must not let a workout start until location is on and verified
metadata:
  type: feedback
---

Buzz (2026-09-06): the phone's location services being off (no GPS data) is a SEVERE CRITICAL error in the phone's
configuration that MUST be fixed before any and all exercise. The app must:
1. auto-fix it if it can, OR
2. inform the user clearly, get the user to fix it, AND verify it is actually on BEFORE allowing any workout to start.

A workout must NOT be allowed to start while `location_mode == 0` (LOCATION_MODE_OFF). The Workout screen's Start
button gates on it: if location is off (or the fine-location permission is missing), block the start, show a loud
message and a button that opens the system location settings, then re-check and only enable Start once location is on.

**Why:** on the 2026-09-06 morning run the phone's location was off (`location_mode = 0`, not battery saver, not our
app — no app can disable system location). Result: zero GPS fixes, 0 m distance, pace stuck at "--:--", and the
watch's own distance 0 (the watch gets distance from the phone GPS). The whole run was unrecoverable for distance —
Google Fit had no track either. This must be impossible to repeat. See [[ryze-app-preferences]].

**How to apply:** `Settings.Secure.LOCATION_MODE != LOCATION_MODE_OFF` (or `LocationManager.isLocationEnabled`) is the
check. An app cannot toggle system location silently; send the user to `Settings.ACTION_LOCATION_SOURCE_SETTINGS` and
re-verify on return. Also add a step-based (stride) pace/distance fallback so pace is never a bare "--:--" (Buzz: "we
can do better").

**The wider reason (Buzz, 2026-09-06):** GPS is enforced for *every* sport, not only the obviously travelling ones,
because it is the only dataset that does not depend on which limb is moving. The wrist counter measures footfalls
in running, strokes in rowing, repetitions in the gym and road buzz on a bike, but GPS measures where the body went
in all of them, and where it is available at all it is reliably available for the whole session. It is therefore
the reference every other measurement is validated against — the stride bug of 2026-09-06 was only detectable
because GPS distance could be compared with the wrist's step count. See [[ryze-wave-distance-motivation]] and
docs/sports_and_step_counting.md.
