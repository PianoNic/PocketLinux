#!/bin/bash
# Builds the APK inside WSL (Termux:X11's native X server build needs a Linux host).
# From Windows:  wsl -d Debian -- bash /mnt/c/Coding/PocketLinux/build-in-wsl.sh
# The finished APK is copied to dist/ in this repo.
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DST="$HOME/build/PocketLinux"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

mkdir -p "$DST"
rsync -a --delete \
  --exclude '/build/' --exclude '/*/build/' --exclude '/dist/' --exclude '/.gradle/' --exclude '/local.properties' --exclude '/keystore.properties' --exclude '/.claude/' \
  --exclude 'external/termux-x11/*/build/' --exclude 'external/termux-x11/lorie/.cxx/' \
  --exclude 'external/termux-x11/shell-loader/stub/build/' \
  "$SRC/" "$DST/"
cd "$DST"
echo "sdk.dir=$ANDROID_HOME" > local.properties
# Files checked out on Windows may have CRLF line endings.
sed -i 's/\r$//' gradlew
find app/src/main/assets -type f -exec sed -i 's/\r$//' {} +
chmod +x gradlew

# glibc preload library that lets .NET work without /tmp.
bash scripts/build-glibc-shim.sh

./gradlew --console=plain :app:assembleDebug "$@"

APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
VER=$(grep -m1 'versionName "' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
mkdir -p "$SRC/dist"
cp "$APK" "$SRC/dist/pocket-linux-v$VER.apk"
echo "APK: $SRC/dist/pocket-linux-v$VER.apk ($(du -h "$APK" | cut -f1))"
