#!/usr/bin/env bash
# Fordítás a saját, home könyvtárba telepített eszközlánccal.
#   ./build.sh              -> debug APK
#   ./build.sh installDebug -> fordít és felrakja a csatlakoztatott telefonra
set -euo pipefail

# A projekt két gépen is fordul, ezért az eszközlánc helye platformfüggő.
if [ "$(uname)" = "Darwin" ]; then
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
else
    export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk-17.0.20+8}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
fi
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")"
./gradlew "${@:-assembleDebug}"

if [ "${1:-assembleDebug}" = "assembleDebug" ]; then
    echo
    echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
fi
