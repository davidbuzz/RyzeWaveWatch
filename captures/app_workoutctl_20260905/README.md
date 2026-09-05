# Workout-control build — on-device verification (2026-09-05)

Build + full unit tests pass in the `workout-control` worktree (377 tests, 0 failures; `:app:assembleDebug`
green). APK: `ryzeapp/app/build/outputs/apk/debug/app-debug.apk`.

**On-device install / screenshots were NOT captured**: the pinned test phone (Moto g05, `MOTO_SERIAL`) was not
attached during this session — `adb devices` showed only the Pixel 9a (`PIXEL_SERIAL`). The task pins the test
device to the Moto, so the app was deliberately not installed on the Pixel. Run the steps below when the Moto is
back to fill this folder.

## Install + open the Workout tab
    export ANDROID_SERIAL=MOTO_SERIAL
    cd ryzeapp && ../tools/app_smoke.sh --no-build 25          # installs the built APK, grants perms (incl. ACTIVITY_RECOGNITION), launches
    adb exec-out screencap -p > ../captures/app_workoutctl_20260905/workout_tab.png

## Exercise the state machine with the debug trigger (NO real watch workout)
If the watch is connected, disconnect it first (app Settings → the connection card, or `adb shell svc bluetooth
disable`) so `start` does not send `FD 11` to a worn watch.

    adb logcat -c
    # start a local workout (foreground service, GPS, TTS); watch disconnected so no FD 11 goes out
    adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op start
    sleep 3
    # inject a rising watch session-step count
    adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op steps --ei n 1234
    # watch-originated pause / resume / stop (drives the SAME controller path as the app buttons)
    adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op pause
    sleep 2
    adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op resume
    sleep 2
    adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op stop
    adb logcat -d -v time > logcat.txt

## What to look for in logcat
- `WorkoutService  announce: "workout started" / "workout paused" / "workout resumed" / "workout stopped"` (INFO, one each)
- `DebugEvent  injecting WatchEvent.WorkoutControl(action=PAUSE)` etc.
- GPS stays on while paused (no `removeLocationUpdates` on pause; only on stop)
- the stored row is finalised even when stopped from paused, and `Workout.steps` = 1234
