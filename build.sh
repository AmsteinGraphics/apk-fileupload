#!/usr/bin/env bash
# Build the debug APK without Android Studio.
# Edit TOOLCHAIN if you installed the JDK / SDK somewhere else.
set -euo pipefail

TOOLCHAIN="${TOOLCHAIN:-$HOME/android-build}"
export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")"
./gradlew assembleDebug "$@"

APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "APK built: $(pwd)/$APK"
ls -lh "$APK"
