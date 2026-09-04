---
name: ryze-wave-distance-motivation
description: Buzz's core grievance with the Ryze Fit app is its broken distance formula; the replacement app must own distance/pace itself
metadata:
  type: user
---

Buzz's main reason for replacing Ryze Fit is that its distance calculation is wrong. They want heart rate, SpO2, steps from the watch, and distance/speed derived properly, using the phone's GPS where available. They do not want the final product to depend on the vendor app in any way (parsing its logs is acceptable only as a reverse-engineering aid).

Specific symptom (Buzz, 2026-09-05): Ryze Fit got steps and/or distance wrong **especially right after a workout** — distances showed 0.0 or 0.01 km even after running 20 minutes. This is the acceptance criterion for our app: after a 20-minute run, workout distance and the day's steps/distance must be right. An audit workflow (code + synthetic 20-minute-run tests + skeptic) was run on 2026-09-05 and its result is recorded in docs/APP.md / wave_application_research.md.

Audit verdict (2026-09-05 morning): the app's own daily steps/distance path is sound; confirmed defects were (1) Health Connect records all stamped clientRecordVersion=1 so in-progress hour/day records froze in HC (a '0.01 km all day' symptom, HC only), (2) a zero-total B1 push overwriting the finished hour until the next sync, (3) the GPS jitter filter bypassed when the phone reports speed (+48 % on synthetic noisy runs), plus the hard 20 m accuracy gate as the exact 0.0-km failure mode on bad signal. A fixer is applying these; synthetic 20-minute-run tests are permanent regressions. Notification format C5 verified on the wrist the same morning.

Build 4 (07:53): the four confirmed defects fixed and proven by synthetic runs (good GPS −0.08 %, no Doppler +1.5 %, gaps ≤0.1 %); 221 tests. Build 5 (07:59): accuracy gate 20 → 60 m; synthetic 25/35 m runs −0.08 % with Doppler, −0.14 % without; 225 tests. Phone notifications being built next.

**Why:** stated 2026-09-04 while we were capturing traffic; shapes what "done" means.
**How to apply:** distance/pace are computed on the phone (the watch only receives them via `FD 44`), so treat the GPS/stride distance model as a first-class feature of our app, not an afterthought. See [[ryze-wave-project]].
