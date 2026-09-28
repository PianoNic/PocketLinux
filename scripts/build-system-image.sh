#!/bin/bash
# Builds the pre-installed system the app downloads on first start: the Termux base system
# with the desktop already installed, so the phone does not install ~400 packages itself.
#
# Runs the app's own first-run setup inside the official Termux Docker image and packs the
# result in the Termux bootstrap format (files, SYMLINKS.txt), plus EXECUTABLES.txt for the
# file modes. Paths stay com.termux here, the app moves them to its own ID while unpacking.
#
# Needs Docker on an arm64 host (CI: ubuntu-24.04-arm). Other hosts work through emulation,
# but slowly. SETUP overrides the setup command for quick tests.
# Output: dist/pocket-linux-system-aarch64.zip and its .sha256
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/dist/pocket-linux-system-aarch64.zip"
NAME="pocket-system-$$"
SETUP=${SETUP:-bash /src/firstrun.sh}
WORK=$(mktemp -d)
trap 'docker rm -f "$NAME" >/dev/null 2>&1; rm -rf "$WORK"' EXIT

docker run --platform linux/arm64 --name "$NAME" -v "$ROOT/app/src/main/assets/desktop:/src:ro" \
  termux/termux-docker:aarch64 bash -c "set -e; $SETUP
    apt-get clean; rm -rf \$PREFIX/tmp/*
    date -u +%F > \$PREFIX/etc/pocket-linux-system"   # tells firstrun.sh it runs on this system
docker cp "$NAME:/data/data/com.termux/files/usr" "$WORK/usr"

mkdir -p "$(dirname "$OUT")"
python3 - "$WORK/usr" "$OUT" <<'PY'
import os, stat, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
links, exes = [], []
with zipfile.ZipFile(out + ".part", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for d, dirs, files in os.walk(src):
        for n in sorted(dirs + files):
            p = os.path.join(d, n)
            rel = os.path.relpath(p, src)
            mode = os.lstat(p).st_mode
            if stat.S_ISLNK(mode):
                links.append(os.readlink(p) + "←./" + rel)   # same format as Termux's bootstrap
            elif stat.S_ISDIR(mode):
                z.write(p, rel + "/")
            elif stat.S_ISREG(mode):
                z.write(p, rel)
                if mode & 0o111:
                    exes.append(rel)
    z.writestr("SYMLINKS.txt", "\n".join(links) + "\n")
    z.writestr("EXECUTABLES.txt", "\n".join(exes) + "\n")
os.replace(out + ".part", out)
print(f"{out}: {os.path.getsize(out) >> 20} MB, {len(links)} symlinks, {len(exes)} executables")
PY
sha256sum "$OUT" | cut -d' ' -f1 > "$OUT.sha256"
cat "$OUT.sha256"
