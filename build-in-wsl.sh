#!/bin/bash
# Builds the APK inside WSL (Termux:X11's native X server build needs a Linux host).
# From Windows:  wsl -d Debian -- bash /mnt/c/Coding/linux-desktop-bundle/termux-app/build-in-wsl.sh
# The finished APK is copied to ../apk/ next to this project.
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DST="$HOME/build/termux-app"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

mkdir -p "$DST"
rsync -a --delete \
  --exclude '/build/' --exclude '/*/build/' --exclude '/.gradle/' --exclude '/local.properties' \
  --exclude 'external/termux-x11/*/build/' --exclude 'external/termux-x11/lorie/.cxx/' \
  --exclude 'external/termux-x11/shell-loader/stub/build/' \
  "$SRC/" "$DST/"
cd "$DST"
echo "sdk.dir=$ANDROID_HOME" > local.properties
# Files checked out on Windows may have CRLF line endings.
sed -i 's/\r$//' gradlew
find app/src/main/assets -type f -exec sed -i 's/\r$//' {} +
chmod +x gradlew

./gradlew --console=plain :app:assembleDebug "$@"

APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
VER=$(grep -m1 'versionName "' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
mkdir -p "$SRC/../apk"
cp "$APK" "$SRC/../apk/linux-desktop-v$VER.apk"
echo "APK: $SRC/../apk/linux-desktop-v$VER.apk ($(du -h "$APK" | cut -f1))"
