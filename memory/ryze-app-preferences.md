---
name: ryze-app-preferences
description: Buzz's decisions for the replacement Android app - name, screens, charts, Health Connect, toolchain
metadata:
  type: project
---

Decided 2026-09-04 evening:
- App name "Buzz's Ryze Wave", package `au.buzz.ryzewave`, Gradle project in `ryzeapp/` (Kotlin 1.9.22, AGP 8.6.0, compileSdk 35, Compose, Room/KSP, Health Connect client 1.1.0-alpha11 — same versions as Buzz's `~/MyPulseApp`, whose Gradle/AGP caches make builds fast).
- Must show heart rate, steps, GPS distance, SpO2, daily step counts and daily distance; graph HR/pulse and SpO2; export to Google Health = **Health Connect** (MyPulseApp already writes HeartRateRecord there — reuse its permission flow).
- History screen: one date navigator, then **four stacked chart cards** (HR, SpO2, steps/hour, daily steps+distance) styled like Ryze Fit's blood-oxygen card (`captures/ryzefit_spo2_reference.png`): line + dots, dashed gridlines with left labels, min/max row, expandable sample list. "We might need 4 or 5 graphs."
- Spec lives in `docs/APP.md`; the build was orchestrated by a workflow (implement → integrate/build → review → fix).

Health Connect permissions can be granted over adb (`pm grant au.buzz.ryzewave android.permission.health.WRITE_HEART_RATE` etc.) on the Moto g05 / Android 15 — no dialog tapping needed for testing.

**How to apply:** keep UI changes consistent with that card style; never regress the Health Connect export; see [[ryze-wave-project]] and [[ryze-wave-distance-motivation]].

- Workout control (2026-09-05): pause/resume/stop must work from the watch buttons AND the app buttons, treated
  identically (single state machine). The phone speaks "workout paused" / "workout resumed" (text-to-speech) on every
  pause/resume regardless of source — the phone is in a pocket and its screen can't be seen. Requested after the
  17:20 run where a watch-side pause/resume was ignored and the app stayed paused for 30 minutes.
