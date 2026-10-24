#!/usr/bin/env bash
# Build the Android app:  $DIST/pdfromeo-<version>-android.apk
#
# The APK is the browser build (apps/pdfcraft-web) in a WebView shell (src/, AndroidManifest.xml).
# It is built with the SDK tools directly (aapt2, javac, d8, apksigner); no Gradle, no NDK.
#
# Usage: packaging/android/package.sh [--skip-build]
#
# Needs: trunk and the wasm32-unknown-unknown target (as packaging/web/package.sh), a JDK (17+),
# and the Android SDK with build-tools and a platform (sdkmanager "build-tools;35.0.0"
# "platforms;android-35"), found through ANDROID_HOME.
#
# Signing (env):
#   ANDROID_KEYSTORE        keystore (default ~/.android/pdfromeo-release.jks, created when missing;
#                           keep it: updates must be signed with the same key)
#   ANDROID_KEYSTORE_PASS   its password (default: read from <keystore>.pass, written on creation)
#   ANDROID_KEY_ALIAS       key alias (default pdfromeo)
set -euo pipefail
# shellcheck source=../env.sh
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
HERE="$ROOT/packaging/android"

if [[ "${1:-}" != "--skip-build" ]]; then
  command -v trunk >/dev/null || { echo "error: trunk not found (cargo install trunk --locked)" >&2; exit 1; }
  (cd "$ROOT/apps/pdfcraft-web" && trunk build --release)
fi
SITE="$ROOT/dist/web"
[[ -f "$SITE/index.html" ]] || { echo "error: $SITE/index.html missing; run without --skip-build" >&2; exit 1; }

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
[[ -x "$BT/aapt2" ]] || { echo "error: no build-tools in $ANDROID_HOME" >&2; exit 1; }
[[ -f "$PLATFORM/android.jar" ]] || { echo "error: no platform in $ANDROID_HOME" >&2; exit 1; }
JAR="$PLATFORM/android.jar"

# 0.13.0 -> 1300: major*10000 + minor*100 + patch.
IFS=. read -r MAJ MIN PAT <<<"${VERSION%%-*}"
VERSION_CODE=$((10#$MAJ * 10000 + 10#$MIN * 100 + 10#${PAT:-0}))

WORK="$CARGO_TARGET_DIR/android-package"
APK="$DIST/pdfromeo-$VERSION-android.apk"
rm -rf "$WORK"
mkdir -p "$WORK"/{res,assets/web,classes,dex,compiled}
echo "==> Pdfromeo $VERSION for Android (versionCode $VERSION_CODE), $(basename "$BT"), $(basename "$PLATFORM")"

# ---- resources: launcher icons from the app icon -----------------------------------------------
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dir="$WORK/res/mipmap-${d%%:*}"
  mkdir -p "$dir"
  sips -s format png -z "${d##*:}" "${d##*:}" "$ROOT/assets/app-icon/pdfcraft-1024.png" --out "$dir/ic_launcher.png" >/dev/null
done
sed -e "s/@VERSION@/$VERSION/g" -e "s/@VERSION_CODE@/$VERSION_CODE/g" "$HERE/AndroidManifest.xml" >"$WORK/AndroidManifest.xml"
cp -R "$SITE/." "$WORK/assets/web/"
copy_docs "$WORK/assets"

"$BT/aapt2" compile --dir "$WORK/res" -o "$WORK/compiled/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$WORK/AndroidManifest.xml" -A "$WORK/assets" \
  --min-sdk-version 26 --target-sdk-version 35 -o "$WORK/unsigned.apk" "$WORK/compiled/res.zip"

# ---- code --------------------------------------------------------------------------------------
find "$HERE/src" -name '*.java' >"$WORK/sources.txt"
javac --release 11 -Xlint:-options -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')
(cd "$WORK/dex" && zip -q "$WORK/unsigned.apk" classes.dex)

# ---- align and sign ----------------------------------------------------------------------------
KEYSTORE="${ANDROID_KEYSTORE:-$HOME/.android/pdfromeo-release.jks}"
ALIAS="${ANDROID_KEY_ALIAS:-pdfromeo}"
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  PASS="${ANDROID_KEYSTORE_PASS:-$(openssl rand -hex 24)}"
  (umask 077 && printf '%s' "$PASS" >"$KEYSTORE.pass")
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Pdfromeo" >/dev/null
  warn "android: created signing key $KEYSTORE (password in $KEYSTORE.pass); back both up"
fi
PASS="${ANDROID_KEYSTORE_PASS:-$(cat "$KEYSTORE.pass")}"
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
rm -f "$APK"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$APK" "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose "$APK" | head -5
echo "==> done"
ls -lh "$APK"
