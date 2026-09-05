# Sleep history card on the Moto g05 — 2026-09-05 09:38

Build 10 (`ryzeapp` debug, installed with `tools/app_smoke.sh --no-build 25`, watch out of range, phone Bluetooth off — the
expected "Bluetooth is off; waiting" in logcat). The app database already held the 62 `32` stage records synced from the
watch on 2026-09-04/05; nothing was replaced.

- `home.png` — dashboard top (connection, today, vitals).
- `home_sleep.png` — dashboard scrolled to the compact sleep card: "Last night: 6 h 19 m", bed / rise, stage strip.
- `history_bottom.png` — History tab scrolled to the fifth card "Sleep" for the newest night (to Sat 5 Sep): hypnogram
  (awake / REM / light / deep lanes, hourly ticks 00:00 … 06:00), totals line
  "6 h 19 m asleep · deep 1:55 · light 4:12 · REM 0:12 · awake 0:31", "Bed 23:27 · rise 06:17", stats row, "Last night's stages (32)".
- `history_tooltip.png` — a deep block tapped (tooltip with its times and length).
- `history_stages.png` — the expanded stage list.

The same 32 rows are replayed by `SleepChartDataTest.replaysTheSyncedNightTo20260905`, which asserts these totals.
