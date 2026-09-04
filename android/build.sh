#!/usr/bin/env bash
# Build, sign and install the headless RyzeBridge APK without Gradle (aapt2 + javac + d8 + zipalign + apksigner).
# Requires the in-repo SDK from android/sdk-install.sh.  Usage: android/build.sh [--no-install]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/android"
SDK="$ROOT/tools/android-sdk"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
OUT="$A/build"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[*] aapt2 link (manifest only, no resources)"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest "$A/AndroidManifest.xml" -I "$PLAT" --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34

echo "[*] javac"
find "$A/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[*] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[*] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$A/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "[*] creating debug keystore $KS"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=RyzeBridge Debug" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/ryzebridge.apk" "$OUT/aligned.apk"
echo "[*] built $OUT/ryzebridge.apk ($(stat -c %s "$OUT/ryzebridge.apk") bytes)"

if [ "${1:-}" != "--no-install" ]; then
  echo "[*] installing"
  adb install -r "$OUT/ryzebridge.apk"
  for p in android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN android.permission.POST_NOTIFICATIONS; do
    adb shell pm grant au.buzz.ryzebridge "$p" 2>/dev/null || echo "    (could not grant $p)"
  done
  echo "[*] installed + permissions granted. Try: tools/bridge.py info"
fi
