# Build 11 GPS breadcrumb, on-device check (Moto g05), 2026-09-06 10:31

The Settings switch is wired through a GraphFactory collector to the location foreground service. Verified with the
debug hook `am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.LINK --ez breadcrumb
true|false` (the same action that parks a phone off the watch with `--ez auto false`).

- ON: `GraphFactory breadcrumb on: starting the service` → `Breadcrumb: breadcrumb service started` →
  `activity transitions requested`; the BreadcrumbService stays running (3 service records) across a 12 s wait.
- Stored 0 crumbs while the phone sat still on the desk — correct: the gate records only while the phone reports
  walking/running/cycling, not while still.
- OFF: service stops. No crashes. Background-location permission granted.

A real trail (crumbs actually stored) happens when the phone is carried while moving; rebuild a workout from it with
"Rebuild from breadcrumb" on the workout detail. 380 unit tests (BreadcrumbGateTest, BreadcrumbReconstructionTest,
MigrationTest for the v5 table).
