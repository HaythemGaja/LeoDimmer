#!/bin/bash
# Build the debug APK with the Gradle wrapper (Android Studio project build).
# Usage: ./build-apk.sh
# Output: app/build/outputs/apk/debug/app-debug.apk
set -e
cd "$(dirname "$0")"
if [ ! -f gradlew ]; then
  echo "No gradlew wrapper in this checkout yet. In Android Studio, use Build > Make Project,"
  echo "or generate the wrapper once with a local Gradle install: gradle wrapper --gradle-version 8.5"
  exit 1
fi
./gradlew :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
