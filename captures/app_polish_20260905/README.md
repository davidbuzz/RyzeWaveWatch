# Ship-shape polish (PLAN item 10, no watch) on the Moto g05 — 2026-09-05 10:00

Build 12 (`ryzeapp` debug, `tools/app_smoke.sh --no-build 25`, `captures/app_smoke_20260905_100003`), watch out of range,
phone Bluetooth off (the expected "Bluetooth is off; waiting" in logcat, no crash). App database untouched (HR for 3–5 Sep).

Chart "avg" label overdraw (docs/APP.md build-3 leftover):
- `hr_card_before_4sep.png` — old build: "avg 81" drawn on top of the 22:00–23:50 dots at the right edge of the plot.
- `hr_card_after_4sep.png` — new build: `LabelLayout.pick` finds the right and left edges covered and puts the label
  (now on a translucent card-coloured pill) in the midday gap, below the line; nothing is hidden.
- `hr_card_before.png` / `hr_card_after.png` — 5 Sep, where the right edge is free: the label stays there, on its pill.
  Also visible in both "after" shots: SpO2's "99" point label no longer touches the "100" axis tick (4 dp inset).

Settings / Workout nits fixed on the way:
- `settings_top.png` (old) vs `settings_watch_after.png` (new): the Watch buttons are a `FlowRow`, so "Find watch" is a
  normal one-line button on a second row instead of a two-line squashed one.
- `workout_tab.png` (old) vs `workout_tab_after.png` (new): "Distance (GPS)" fits on one line.
- `settings_2.png`, `settings_3.png`: the rest of Settings with the old build (Sampling, Stride) for reference.
The Stride / Watch edit-state fix (`EditGuard`) cannot be exercised end to end without a flow re-emission; verified by reading.

Launcher icon:
- `app_drawer.png` — the app drawer tile "Buzz's R…": deep blue adaptive icon, white rounded watch case with strap
  stubs, coral heart-rate wave.
- `app_info_icon.png` — the same icon at App-info size.

Signed release build:
- `apksigner_release.txt` — `apksigner verify --verbose --print-certs` on `app/build/outputs/apk/release/app-release.apk`:
  Verifies, v2 scheme, one signer, `CN=Buzz's Ryze Wave`, RSA 2048. Not installed (the debug build stays; same package,
  different key). Keystore + properties are git-ignored (`ryzeapp/keystore/`, `ryzeapp/keystore.properties`).

Unit tests: 307, 0 failures (`LabelLayoutTest` added: 7).
