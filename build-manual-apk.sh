#!/bin/bash
# Manual SDK-tools build for LeoDimmer (aapt2 + kotlinc + d8 + zipalign + apksigner).
# Used on machines where the Gradle daemon cannot run (this VM's daemon dies
# with a loopback protocol error); the Gradle project in this repo is still
# the canonical Android Studio build -- this script produces the same
# debug APK from the same sources, including the Leanback library resources.
# Usage: ./build-manual-apk.sh [output-apk]
# Default output: /tmp/tvdimmer-build/LeoDimmer-v1.6.apk
# The debug keystore lives OUTSIDE the repo at
# ~/workspace/.keystores/tvdimmer-debug.keystore (never committed).
set -e
cd "$(dirname "$0")"
OUT_APK="${1:-/tmp/tvdimmer-build/LeoDimmer-v1.6.apk}"
# Version from app/build.gradle.kts, read here (before any cd) so the
# aapt2 link step below uses the Gradle version, not a stale hardcode.
VC=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)
VN=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
BT="$HOME/workspace/android-build/sdk/build-tools/34.0.0"
PLAT="$HOME/workspace/android-build/sdk/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/android-build/kotlin-1.9.24/bin/kotlinc"
KLIB="$HOME/workspace/android-build/kotlin-1.9.24/lib"
SRC="$PWD/app/src/main"
OUT="/tmp/tvdimmer-build/work"
KEYSTORE="$HOME/workspace/.keystores/tvdimmer-debug.keystore"
MAVEN="https://dl.google.com/dl/android/maven2"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/libs" "$OUT/compiled"

# ---- Library AARs / JARs (Leanback 1.0.0 graph, as in app/build.gradle.kts) ----
cd "$OUT/libs"
dl() { [ -f "$(basename "$1")" ] || curl -sL -o "$(basename "$1")" "$MAVEN/$1"; }
dl androidx/leanback/leanback/1.0.0/leanback-1.0.0.aar
dl androidx/fragment/fragment/1.0.0/fragment-1.0.0.aar
dl androidx/core/core/1.0.0/core-1.0.0.aar
dl androidx/recyclerview/recyclerview/1.0.0/recyclerview-1.0.0.aar
dl androidx/media/media/1.0.0/media-1.0.0.aar
dl androidx/legacy/legacy-support-core-ui/1.0.0/legacy-support-core-ui-1.0.0.aar
dl androidx/customview/customview/1.0.0/customview-1.0.0.aar
dl androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.aar
dl androidx/coordinatorlayout/coordinatorlayout/1.0.0/coordinatorlayout-1.0.0.aar
dl androidx/versionedparcelable/versionedparcelable/1.0.0/versionedparcelable-1.0.0.aar
dl androidx/lifecycle/lifecycle-livedata-core/2.0.0/lifecycle-livedata-core-2.0.0.aar
dl androidx/lifecycle/lifecycle-runtime/2.0.0/lifecycle-runtime-2.0.0.aar
dl androidx/lifecycle/lifecycle-viewmodel/2.0.0/lifecycle-viewmodel-2.0.0.aar
dl androidx/lifecycle/lifecycle-livedata/2.0.0/lifecycle-livedata-2.0.0.aar
dl androidx/arch/core/core-runtime/2.0.0/core-runtime-2.0.0.aar
dl androidx/annotation/annotation/1.0.0/annotation-1.0.0.jar
dl androidx/collection/collection/1.0.0/collection-1.0.0.jar
dl androidx/lifecycle/lifecycle-common/2.0.0/lifecycle-common-2.0.0.jar
dl androidx/arch/core/core-common/2.0.0/core-common-2.0.0.jar
cd "$OUT"

# ---- aapt2 compile: app res + every library res ----
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled/app.zip"
EXTRA_PKGS=""
for aar in libs/*.aar; do
  name=$(basename "$aar" .aar)
  rm -rf "libs/$name-res" "libs/$name-classes.jar"
  unzip -o -q "$aar" -d "libs/$name-x"
  if [ -d "libs/$name-x/res" ] && [ -n "$(ls -A "libs/$name-x/res" 2>/dev/null)" ]; then
    "$BT/aapt2" compile --dir "libs/$name-x/res" -o "compiled/$name.zip"
    pkg=$(grep -o 'package="[^"]*"' "libs/$name-x/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
    [ -n "$pkg" ] && EXTRA_PKGS="$EXTRA_PKGS --extra-packages $pkg"
  fi
  [ -f "libs/$name-x/classes.jar" ] && cp "libs/$name-x/classes.jar" "libs/$name.jar"
done

# ---- aapt2 link ----
# Version comes from app/build.gradle.kts (see the VC/VN read at the top
# of this script): a stale hardcoded code would make the APK look like an
# old version and refuse to update a newer install.
sed 's|<manifest |<manifest package="com.universal.tvdimmer" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
# shellcheck disable=SC2086
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  $EXTRA_PKGS \
  "$OUT"/compiled/*.zip \
  --min-sdk-version 28 \
  --target-sdk-version 34 \
  --version-code "$VC" \
  --version-name "$VN"

# ---- javac (R.java for app + libraries) + kotlinc ----
find "$OUT/gen" -name "*.java" > "$OUT/sources-java.txt"
javac -encoding UTF-8 -source 17 -target 17 -nowarn -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources-java.txt"
CP="$PLAT:$OUT/classes:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-stdlib-jdk7.jar:$KLIB/kotlin-stdlib-jdk8.jar:$KLIB/annotations-13.0.jar"
for j in "$OUT"/libs/*.jar; do CP="$CP:$j"; done
find "$SRC/java" -name "*.kt" > "$OUT/sources-kt.txt"
"$KOTLINC" -jvm-target 17 -no-stdlib -nowarn -cp "$CP" -d "$OUT/classes" @"$OUT/sources-kt.txt"

# ---- d8 + package + align + sign ----
"$BT/d8" --lib "$PLAT" --min-api 28 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") "$KLIB/kotlin-stdlib.jar" "$KLIB/kotlin-stdlib-jdk7.jar" "$KLIB/kotlin-stdlib-jdk8.jar" "$KLIB/annotations-13.0.jar" "$OUT"/libs/*.jar
cp "$OUT/base.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app.apk" classes*.dex)
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$OUT_APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$OUT_APK"
echo "APK: $OUT_APK"
