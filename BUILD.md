# Building and installing

Two Android apps live in this repo, plus a Python client:

| What | Where | Build with |
|---|---|---|
| **Buzz's Ryze Wave** — the real app (Kotlin/Compose/Room/Health Connect) | `ryzeapp/` | Gradle |
| **RyzeBridge** — a headless relay that turns a phone into a BLE radio for the laptop | `android/` | `android/build.sh` (no Gradle) |
| **ryzewave** — BLE client library and CLI | `ryzewave/` | none, run from `.venv` |

Everything below is Linux with `adb` on `PATH`. Nothing is installed outside the repo.

## Prerequisites (once)

**Java 17.** The build needs a JDK 17. On this machine the default `java` is a JRE-only 21, which fails, so the
JDK is pinned in `ryzeapp/gradle.properties`:

```
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

Change that line if your JDK 17 lives elsewhere. That property wins over `JAVA_HOME`, so on another machine either
edit it or override it per-invocation, which is what CI does:

```bash
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :app:assembleDebug
```

`tools/fastbuild.sh` relies on the pinned property; `tools/app_smoke.sh` exports `JAVA_HOME` as well. Gradle itself
comes from the wrapper (8.14.4), so you do not install it.

**Android SDK, inside the repo.** The SDK is git-ignored, so a clone has none. Install it:

```bash
android/sdk-install.sh          # cmdline-tools + platforms 34/35 + build-tools 34/35, into tools/android-sdk
```

`ryzeapp/local.properties` tells Gradle where it went, and is git-ignored too. Write it:

```bash
echo "sdk.dir=$PWD/tools/android-sdk" > ryzeapp/local.properties
```

If you later move the repo, fix that line and stop the Gradle daemons (`cd ryzeapp && ./gradlew --stop`), because
they cache the old path.

**Python venv** (only for the CLI and the helper tools):

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
```

## The fast path: edit, build, install

`tools/fastbuild.sh` is what to use during development. It runs the incremental Gradle task, keeps the daemon warm
(3 h idle timeout, set in `gradle.properties`), prints Kotlin errors, and fails loudly instead of claiming success.
After the first run a one-file edit reinstalls in seconds.

```bash
tools/fastbuild.sh                  # :app:installDebug on the only attached phone
tools/fastbuild.sh <serial>         # ...or on that phone (adb devices for the serial)
tools/fastbuild.sh --apk            # just assemble, no install
tools/fastbuild.sh --test SomeTest  # run one unit-test class
```

Never pass `--rerun-tasks` for routine work; it throws away the incremental state that makes this fast.

## The plain path

```bash
cd ryzeapp
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK lands at `ryzeapp/app/build/outputs/apk/debug/app-debug.apk`.

Run the full unit suite before committing, not on every edit:

```bash
cd ryzeapp && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest
```

## Permissions after installing

A fresh install has no runtime permissions, and the app gates the workout screen until location is on and granted.
Grant them over adb rather than tapping through dialogs:

```bash
PKG=au.buzz.ryzewave
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
         ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS ACTIVITY_RECOGNITION; do
  adb shell pm grant $PKG android.permission.$p
done
for p in STEPS HEART_RATE OXYGEN_SATURATION DISTANCE SLEEP EXERCISE EXERCISE_ROUTE; do
  adb shell pm grant $PKG android.permission.health.WRITE_$p
done
```

The `android.permission.health.*` ones are platform permissions on Android 14+, so `pm grant` works for Health
Connect (verified on Android 15 and 16). The app only ever writes, so there is no `READ_` permission to grant:
those seven `WRITE_` are exactly what the manifest declares. `WRITE_EXERCISE_ROUTE` carries the workout's GPS
track.

`tools/app_smoke.sh` does build, install, the grants, launch, wait, logcat and a screenshot in one go, into
`captures/app_smoke_<timestamp>/`. It grants everything above except `ACCESS_BACKGROUND_LOCATION`, which the
breadcrumb service needs, so grant that one by hand if you are testing breadcrumbs:

```bash
tools/app_smoke.sh              # build first
tools/app_smoke.sh --no-build 25   # install what is already built, wait 25 s
```

## Backing up and restoring your data

The app keeps everything in one SQLite database in its own private storage. Release builds are deliberately
debuggable (see the `release` block in `ryzeapp/app/build.gradle.kts`), so `adb` can reach it with `run-as`. No
root needed. Enable USB debugging on the phone first.

**Back up.** The database uses write-ahead logging, so most of your recent data lives in the `-wal` file, not in
the `.db`. Copying only the `.db` gives you a nearly empty backup. Take all three files, then fold the log in:

```bash
PKG=au.buzz.ryzewave
for f in ryzewave.db ryzewave.db-wal ryzewave.db-shm; do
  adb exec-out run-as $PKG cat databases/$f > "$f"        # -wal/-shm may not exist; that is fine
done
sqlite3 ryzewave.db "PRAGMA wal_checkpoint(TRUNCATE);"    # merges the log into the .db
rm -f ryzewave.db-wal ryzewave.db-shm                     # now ryzewave.db is complete on its own
sqlite3 ryzewave.db "pragma integrity_check;"             # should print: ok
```

Your GPX exports are separate and need no `run-as`:

```bash
adb pull /sdcard/Android/data/au.buzz.ryzewave/files/gpx ./gpx
```

**Restore.** Stop the app first, or it will overwrite what you push. The `-wal` and `-shm` must be deleted, or
SQLite will replay a log that no longer matches the database:

```bash
PKG=au.buzz.ryzewave
adb shell am force-stop $PKG
adb push ryzewave.db /data/local/tmp/restore.db
adb shell "run-as $PKG cp /data/local/tmp/restore.db databases/ryzewave.db"
adb shell "run-as $PKG rm -f databases/ryzewave.db-wal databases/ryzewave.db-shm"
adb shell rm -f /data/local/tmp/restore.db
```

Then open the app. Restoring a database from a *newer* build than the one installed will fail, because Room
refuses to open a schema it does not know; install that version or newer first.

Working from this repo, `tools/pull_app_data.sh <serial> <name>` does the whole backup, checkpoint included, and
writes a row-count summary to `captures/<name>/`.

**What this does not cover.** Uninstalling the app erases the database, and reinstalling does not bring it back.
Take a backup before an uninstall, including before installing a release build over a development one, since the
different signing key forces an uninstall. Settings live separately in a DataStore file
(`run-as $PKG cat files/datastore/settings.preferences_pb`), and the watch itself still holds roughly the last
week of history, so a fresh install refills much of the recent data on the next sync.

## Two phones, one watch

The watch accepts a single BLE link. With both phones attached, whichever has auto-connect enabled holds it and the
other cannot reach it. Turn auto-connect off in the app's Settings on the phone that should stay out of the way.
Also force-stop the vendor app and the bridge before testing, since all three share the phone's GATT link:

```bash
adb shell am force-stop com.yc.ryzefit au.buzz.ryzebridge
```

## Release build

```bash
cd ryzeapp && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease
```

This is signed only when `ryzeapp/keystore.properties` exists, giving `storeFile` (relative to `ryzeapp/`),
`storePassword`, `keyAlias` and `keyPassword`. Both the keystore and that properties file are git-ignored and are
not in this repo; create your own with `keytool -genkeypair` if you need a signed build. Without them the release
task produces an unsigned APK.

## RyzeBridge (the headless relay)

No Gradle. It compiles with the in-repo SDK's `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`:

```bash
android/build.sh                # build, sign, install, grant Bluetooth permissions
android/build.sh --no-install   # build only
```

Its debug signing key is generated on first run and is git-ignored, so the first install after a fresh clone may
need `adb uninstall au.buzz.ryzebridge` if an older build signed by a different key is present. Drive it with
`tools/bridge.py`.

## When a build fails

- `Execution failed for ClasspathEntrySnapshotTransform` naming a path that no longer exists: a stale daemon from
  before the repo moved. `cd ryzeapp && ./gradlew --stop`, then build again.
- `Unsupported class file major version` or a toolchain complaint: the build picked up Java 21. Check
  `org.gradle.java.home` in `ryzeapp/gradle.properties`.
- `SDK location not found`: `ryzeapp/local.properties` is missing or points somewhere wrong. See prerequisites.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the installed app was signed with a different key. `adb uninstall
  au.buzz.ryzewave` first. This happens after the debug keystore is regenerated.
