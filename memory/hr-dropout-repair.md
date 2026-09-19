---
name: hr-dropout-repair
description: The watch's optical HR drops out mid-run (2026-09-19, 5.5 min at ~100 bpm); the app now estimates HR, flags/replaces dropouts, speaks a warning, and measures HRR
metadata:
  type: project
---

The Ryze Wave's optical sensor loses the wrist mid-run and reports ~95-113 bpm for minutes at an unchanged pace
(2026-09-19 18:36 run, minutes 14-18; Buzz says it has happened before). Nothing in the packet marks it.

**What the app does now (build of 2026-09-19):** `workout/HrEstimator` (EKF over [hr, offset], target fitted to
Buzz's nine runs: HR = 134 + 7·speed + 19.3·climb, resting-rate ramp below walking pace, exercising floor 125 bpm
once warmed up and only while moving, 3σ innovation gate, 15 s to flag). While flagged the app believes the
estimate, stores the watch value in `hr_sample.measured` (schema 7: estimate/flagged/measured), and speaks
"heart rate reading looks wrong, watch says N, expect about M" at most once a minute. The warm-up and cool-down
ramps are never touched - Buzz cares about them as recovery data. "Repair heart rate" on a workout replays it.
`workout/HeartRateRecovery` = peak − rate 1 and 2 min after the last ≥3-min bout (Cleveland Clinic bands).

**Lessons:** a fix gap of >15 s means the phone sat still - never freeze the last speed across it. The live
calorie figure is a distance/HR hybrid only the controller computes: a repair must add the HR-formula *delta*
over the replaced samples, not recompute the session from HR (that overstated 355 -> 549; correct was 387).

**State:** the 2026-09-19 run on the Pixel is repaired (274 samples, avg 134->140, kcal 355->387, HC re-exported)
via the app's button plus one safe DB swap for the calorie fix ([[db-surgery-on-phone]] procedure, worked cleanly).
Pixel runs the build with the calorie-delta fix. Requested next by Buzz: HRR + resting HR as daily/after-exercise
vitals on the home screen and in Health Connect (HRR into the session notes; RHR as RestingHeartRateRecord).
