#!/bin/bash
# Cross-compiles the preload libraries into the app's assets:
#  - libtmp-redirect.so (glibc, needs gcc-aarch64-linux-gnu): lets .NET work without /tmp
#  - libdevice-name.so (bionic, needs the NDK): shows the phone's name instead of u0_a123
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/desktop"

if command -v aarch64-linux-gnu-gcc >/dev/null; then
  aarch64-linux-gnu-gcc -O2 -Wall -shared -fPIC -o "$ASSETS/libtmp-redirect.so" "$ROOT/glibc-shim/tmp-redirect.c" -ldl
  echo "built $ASSETS/libtmp-redirect.so"
else
  echo "WARNING: aarch64-linux-gnu-gcc missing (apt install gcc-aarch64-linux-gnu)," \
       "building without the .NET /tmp fix" >&2
fi

NDK="${ANDROID_HOME:-$HOME/android-sdk}/ndk/$(sed -n 's/^ndkVersion=//p' "$ROOT/gradle.properties")"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
if [ -x "$CC" ]; then
  "$CC" -O2 -Wall -shared -fPIC -o "$ASSETS/libdevice-name.so" "$ROOT/glibc-shim/device-name.c"
  echo "built $ASSETS/libdevice-name.so"
else
  echo "WARNING: NDK clang missing ($CC), building without the device name fix" >&2
fi
