---
name: Release-signed build on Pixel
description: The Pixel runs the release-signed RyzeWave APK; install updates with assembleRelease + adb install -r, never uninstall (destroys ryzewave.db)
type: project
originSessionId: 6e315d9c-1004-4dfd-9c25-ba5107492366
---
The Pixel (PIXEL_SERIAL) carries the **release-signed** RyzeWave build (Buzz's key via `ryzeapp/keystore.properties`; the release build is deliberately debuggable). `tools/fastbuild.sh` / `installDebug` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE against it.

**Why:** Discovered 2026-09-07 when a debug install was rejected. Uninstalling to switch signatures would wipe the on-phone `ryzewave.db` (real workout history) — never do that.

**How to apply:** To update the app on the Pixel: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease` then `adb install -r app/build/outputs/apk/release/app-release.apk`. Never `adb uninstall` or `pm clear` the app.
