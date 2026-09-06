#!/usr/bin/env bash
# Build a SIGNED release APK of "Buzz's Ryze Wave", verify it, and optionally publish a GitHub release.
#
#   tools/make_signed_release.sh                 build + verify only, artifacts in dist/
#   tools/make_signed_release.sh --publish       ...and create the GitHub release for the current version
#   tools/make_signed_release.sh --version 0.2   override the version name (otherwise read from Gradle)
#   tools/make_signed_release.sh --notes FILE    use FILE as the release notes body
#
# Signing needs ryzeapp/keystore.properties + the keystore it points at. Neither is in git: keep a backup
# somewhere safe, because losing the key means no one can install an upgrade over an existing install.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"      # the repo this script lives in
JDK="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
GH_REPO="davidbuzz/RyzeWaveWatch"
PUBLISH=0
VERSION=""
NOTES_FILE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1; shift ;;
    --version) VERSION="${2:?--version needs a value}"; shift 2 ;;
    --notes)   NOTES_FILE="${2:?--notes needs a file}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$REPO/ryzeapp" ] || die "no ryzeapp/ under $REPO - keep this script in the repo's tools/ directory"
cd "$REPO"
[ -x "$JDK/bin/java" ] || die "JDK 17 not at $JDK (set JAVA17)"
[ -f ryzeapp/keystore.properties ] || die "ryzeapp/keystore.properties missing - the build would be UNSIGNED"
KS=$(grep -E '^storeFile=' ryzeapp/keystore.properties | cut -d= -f2-)
[ -f "ryzeapp/$KS" ] || [ -f "$KS" ] || die "keystore '$KS' from keystore.properties not found"

# The tag must describe code that is actually published, so refuse to release a dirty or unpushed tree.
[ -z "$(git status --porcelain)" ] || die "working tree is dirty - commit or stash first"
git fetch -q origin 2>/dev/null || git fetch -q --all 2>/dev/null || true
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git ls-remote "https://github.com/$GH_REPO.git" main 2>/dev/null | cut -f1)
if [ -n "$REMOTE" ] && [ "$LOCAL" != "$REMOTE" ]; then
  echo "WARNING: local HEAD ${LOCAL:0:8} != github main ${REMOTE:0:8}."
  echo "         Push first, or the release will point at code nobody can see."
  [ "$PUBLISH" = 1 ] && die "refusing to publish out-of-sync"
fi

[ -n "$VERSION" ] || VERSION=$(grep -E '^\s*versionName\s*=' ryzeapp/app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
[ -n "$VERSION" ] || die "could not read versionName from ryzeapp/app/build.gradle.kts"
TAG="v$VERSION"
echo "[*] version $VERSION  tag $TAG  commit ${LOCAL:0:8}"

if [ "$PUBLISH" = 1 ] && gh release view "$TAG" -R "$GH_REPO" >/dev/null 2>&1; then
  die "release $TAG already exists - bump versionName in ryzeapp/app/build.gradle.kts (and versionCode) first"
fi

echo "[*] unit tests"
( cd ryzeapp && JAVA_HOME="$JDK" ./gradlew :app:testDebugUnitTest --console=plain -q ) || die "unit tests failed"

echo "[*] assembleRelease"
( cd ryzeapp && JAVA_HOME="$JDK" ./gradlew :app:assembleRelease --console=plain -q ) || die "release build failed"
APK=ryzeapp/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || die "no APK at $APK"

BT=$(ls -d tools/android-sdk/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] || die "no build-tools in tools/android-sdk (run android/sdk-install.sh)"

echo "[*] verifying signature"
"$BT/apksigner" verify --print-certs "$APK" > /tmp/apksig.$$ 2>&1 || { cat /tmp/apksig.$$; rm -f /tmp/apksig.$$; die "APK is not properly signed"; }
grep -q 'Signer #1 certificate DN' /tmp/apksig.$$ || { cat /tmp/apksig.$$; rm -f /tmp/apksig.$$; die "no signer found - UNSIGNED APK"; }
grep -E 'Verified using v[23] scheme|Number of signers|Signer #1 certificate DN|Signer #1 certificate SHA-256' /tmp/apksig.$$ | sed 's/^/    /'
rm -f /tmp/apksig.$$
# Report the debuggable flag rather than reject it: these builds are deliberately debuggable so the phone's
# owner can read their own data (see the release buildType in ryzeapp/app/build.gradle.kts). Written to a file
# first - piping into `grep -q` kills aapt2 with SIGPIPE, and under `set -o pipefail` the test then never fires.
"$BT/aapt2" dump badging "$APK" > /tmp/badging.$$ 2>/dev/null || die "aapt2 could not read $APK"
if grep -q '^application-debuggable' /tmp/badging.$$; then
  echo "    NOTE: this APK is DEBUGGABLE by design (sideload only, never the Play Store)."
  echo "          Anyone with USB debugging enabled and an authorised computer can read the app's data."
else
  echo "    not debuggable"
fi
rm -f /tmp/badging.$$

mkdir -p dist
OUT="dist/RyzeWaveWatch-$VERSION.apk"
SRC="dist/RyzeWaveWatch-$VERSION-source.zip"
cp -f "$APK" "$OUT"
git archive --format=zip --prefix="RyzeWaveWatch-$VERSION/" HEAD > "$SRC"
( cd dist && sha256sum "$(basename "$OUT")" "$(basename "$SRC")" > "RyzeWaveWatch-$VERSION.sha256" )
echo "[*] artifacts:"
ls -lh "$OUT" "$SRC" "dist/RyzeWaveWatch-$VERSION.sha256" | awk '{print "    ",$5,$9}'

if [ "$PUBLISH" = 0 ]; then
  echo
  echo "Not published. To publish this build:"
  echo "    $0 --publish"
  exit 0
fi

if [ -n "$NOTES_FILE" ]; then
  NOTES_ARG=(--notes-file "$NOTES_FILE")
else
  NOTES_ARG=(--generate-notes)
fi

echo "[*] creating GitHub release $TAG on $GH_REPO"
gh release create "$TAG" "$OUT" "$SRC" "dist/RyzeWaveWatch-$VERSION.sha256" \
  -R "$GH_REPO" --title "Buzz's Ryze Wave $VERSION" --target "$LOCAL" "${NOTES_ARG[@]}"
echo "[*] done: https://github.com/$GH_REPO/releases/tag/$TAG"
