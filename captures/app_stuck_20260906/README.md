# Build 10 stuck-in-exercise-mode detector, on-device check (Moto g05, Bluetooth off, watch away), 2026-09-06 10:12

Driven through the debug receiver (`am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a
au.buzz.ryzewave.debug.WORKOUT`): op `watchstart` with `--el window 20000 --el grace 15000 --ez night false --ei hr 58`,
then op `steps --ei n <count>` every 5 s. Full log excerpt in `logcat_scenarios.txt`.

## Scenario 1 — watch-started workout, session steps FLAT (100 → 100)
- 10:12:27 `watching watch-originated workout … window 20 s`
- 10:12:57 `steps=none(watch 100→100) gps=? hr=none(max 58 vs rest 65, sleeping) motion=none` →
  `LIKELY STUCK (NORMAL): no activity: steps+hr+motion measured none, gps unknown` (daytime → NORMAL, not HIGH)
- 10:12:57 spoken: "Workout running but no activity detected. Tap to stop."; high-priority notification posted
- 10:13:12 `grace timer fired after 15 s` → `auto-stopping the workout` → `stopping the watch workout (auto-stop, no
  activity)` → session ended (the FD 00 to the watch fails here only because Bluetooth is off on the test phone).

## Scenario 2 — watch-started workout, session steps RISING (140 → 300)
- `steps=ACTIVE(watch 140→300)` → live on steps; no warning, no auto-stop. Crashes: 0.

The accelerometer motion signal was measured (phone on the desk: `motion=none(max var …)`), heart rate fed at 58 bpm
against the 65 bpm fallback baseline (sleeping level), which with `night=false` stays NORMAL urgency as designed.
