#!/bin/bash
# Cross-compiles the glibc preload library that lets .NET work without /tmp
# (glibc-shim/tmp-redirect.c) into the app's assets. Needs gcc-aarch64-linux-gnu.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/desktop/libtmp-redirect.so"
if ! command -v aarch64-linux-gnu-gcc >/dev/null; then
  echo "WARNING: aarch64-linux-gnu-gcc missing (apt install gcc-aarch64-linux-gnu)," \
       "building without the .NET /tmp fix" >&2
  exit 0
fi
aarch64-linux-gnu-gcc -O2 -Wall -shared -fPIC -o "$OUT" "$ROOT/glibc-shim/tmp-redirect.c" -ldl
echo "built $OUT"
