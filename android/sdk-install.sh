#!/usr/bin/env bash
# Install a minimal Android SDK inside the repo (tools/android-sdk): cmdline-tools, platform 34, build-tools 34.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/tools/android-sdk"
mkdir -p "$SDK/cmdline-tools"
cd "$SDK"
if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then
  for id in 13114758 11076708 10406996; do
    URL="https://dl.google.com/android/repository/commandlinetools-linux-${id}_latest.zip"
    echo "[*] trying $URL"
    if curl -fsSL -o cmdtools.zip "$URL"; then break; fi
  done
  rm -rf cmdline-tools/latest cmdline-tools/cmdline-tools
  unzip -q cmdtools.zip -d cmdline-tools && mv cmdline-tools/cmdline-tools cmdline-tools/latest && rm cmdtools.zip
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SM" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
"$SM" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0"
echo "[*] SDK ready:"; ls "$SDK/build-tools" "$SDK/platforms"
