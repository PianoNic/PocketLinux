#!/bin/bash
# Renders the legacy (pre-Android 8) launcher PNGs from art/pocket-linux-icon.svg.
# Needs rsvg-convert and ImageMagick (WSL: apt install librsvg2-bin imagemagick).
set -e
cd "$(dirname "$0")"
RES=../app/src/main/res
tmp=$(mktemp -d)
rsvg-convert -w 768 -h 768 pocket-linux-icon.svg -o "$tmp/full.png"
# Launchers show the middle 72/108 of the adaptive canvas.
convert "$tmp/full.png" -gravity center -crop 512x512+0+0 +repage "$tmp/visible.png"
convert -size 512x512 xc:none -fill white -draw "roundrectangle 0,0 511,511 112,112" "$tmp/sq.png"
convert -size 512x512 xc:none -fill white -draw "circle 256,256 256,0" "$tmp/ci.png"
convert "$tmp/visible.png" "$tmp/sq.png" -compose CopyOpacity -composite "$tmp/square.png"
convert "$tmp/visible.png" "$tmp/ci.png" -compose CopyOpacity -composite "$tmp/round.png"
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dir=${d%%:*}; px=${d##*:}
  convert "$tmp/square.png" -resize ${px}x${px} "$RES/mipmap-$dir/ic_launcher.png"
  convert "$tmp/round.png"  -resize ${px}x${px} "$RES/mipmap-$dir/ic_launcher_round.png"
done
rm -rf "$tmp"
echo "legacy icons written"
