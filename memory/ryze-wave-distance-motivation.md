---
name: ryze-wave-distance-motivation
description: Buzz's core grievance with the Ryze Fit app is its broken distance formula; the replacement app must own distance/pace itself
metadata:
  type: user
---

Buzz's main reason for replacing Ryze Fit is that its distance calculation is wrong. They want heart rate, SpO2, steps from the watch, and distance/speed derived properly, using the phone's GPS where available. They do not want the final product to depend on the vendor app in any way (parsing its logs is acceptable only as a reverse-engineering aid).

Specific symptom (Buzz, 2026-09-05): Ryze Fit got steps and/or distance wrong **especially right after a workout** — distances showed 0.0 or 0.01 km even after running 20 minutes. This is the acceptance criterion for our app: after a 20-minute run, workout distance and the day's steps/distance must be right. An audit workflow (code + synthetic 20-minute-run tests + skeptic) was run on 2026-09-05 and its result is recorded in docs/APP.md / wave_application_research.md.

**Why:** stated 2026-09-04 while we were capturing traffic; shapes what "done" means.
**How to apply:** distance/pace are computed on the phone (the watch only receives them via `FD 44`), so treat the GPS/stride distance model as a first-class feature of our app, not an afterthought. See [[ryze-wave-project]].
