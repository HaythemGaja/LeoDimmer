#!/bin/bash
# Manual SDK-tools build for LeoDimmer (aapt2 + kotlinc + d8 + zipalign + apksigner).
# Used on machines where the Gradle daemon cannot run; the Gradle project in
# this repo is still the canonical Android Studio build -- this script produces
# the same debug APK from the same sources.
#
# v2.0 has NO library dependencies, so nothing is downloaded here and no AAR
# classes are merged. The previous version of this script fetched the Leanback
# 1.0.0 graph with `curl -sL` and never checked the result, so one failed
# download produced an APK that installed fine and died on launch with
#   NoClassDefFoundError: Failed resolution of: Landroidx/leanback/...
# With no libraries there is nothing left to miss.
# Usage: ./build-manual-apk.sh [output-apk]
# Default output: /tmp/tvdimmer-build/LeoDimmer-v<versionName>.apk
# The debug keystore lives OUTSIDE the repo at
# ~/workspace/.keystores/tvdimmer-debug.keystore (never committed).
set -e
cd "$(dirname "$0")"
# Version from app/build.gradle.kts, read here (before any cd) so the aapt2
# link step below uses the Gradle version, not a stale hardcode, and the output
# filename cannot go stale either.
VC=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)
VN=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
OUT_APK="${1:-/tmp/tvdimmer-build/LeoDimmer-v$VN.apk}"
BT="$HOME/workspace/android-build/sdk/build-tools/34.0.0"
PLAT="$HOME/workspace/android-build/sdk/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/android-build/kotlin-1.9.24/bin/kotlinc"
KLIB="$HOME/workspace/android-build/kotlin-1.9.24/lib"
SRC="$PWD/app/src/main"
OUT="/tmp/tvdimmer-build/work"
KEYSTORE="$HOME/workspace/.keystores/tvdimmer-debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/compiled"

# ---- aapt2 compile: app res (there are no library resources to merge) ----
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled/app.zip"

# ---- aapt2 link ----
# Version comes from app/build.gradle.kts (see the VC/VN read at the top
# of this script): a stale hardcoded code would make the APK look like an
# old version and refuse to update a newer install.
sed 's|<manifest |<manifest package="com.universal.tvdimmer" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  "$OUT"/compiled/*.zip \
  --min-sdk-version 28 \
  --target-sdk-version 34 \
  --version-code "$VC" \
  --version-name "$VN"

# ---- javac (R.java for app + libraries) + kotlinc ----
find "$OUT/gen" -name "*.java" > "$OUT/sources-java.txt"
javac -encoding UTF-8 -source 17 -target 17 -nowarn -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources-java.txt"
CP="$PLAT:$OUT/classes:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-stdlib-jdk7.jar:$KLIB/kotlin-stdlib-jdk8.jar:$KLIB/annotations-13.0.jar"
find "$SRC/java" -name "*.kt" > "$OUT/sources-kt.txt"
"$KOTLINC" -jvm-target 17 -no-stdlib -nowarn -cp "$CP" -d "$OUT/classes" @"$OUT/sources-kt.txt"

# ---- d8 + package + align + sign ----
"$BT/d8" --lib "$PLAT" --min-api 28 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") "$KLIB/kotlin-stdlib.jar" "$KLIB/kotlin-stdlib-jdk7.jar" "$KLIB/kotlin-stdlib-jdk8.jar" "$KLIB/annotations-13.0.jar"
cp "$OUT/base.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app.apk" classes*.dex)
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$OUT_APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$OUT_APK"
echo "APK: $OUT_APK"
