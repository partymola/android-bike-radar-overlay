#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Render the 512 px store-listing icon (fastlane images/icon.png) from the
# SVG master: the mark on a white field, cropped to the inner 2/3 of the
# adaptive-icon canvas so it matches what launchers show. Requires
# rsvg-convert (librsvg) and ImageMagick.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
svg="$here/../br-mark.svg"
meta="$here/../../fastlane/metadata/android"

tmp="$(mktemp --suffix=.png)"
trap 'rm -f "$tmp"' EXIT
rsvg-convert -w 768 -h 768 "$svg" -o "$tmp"

# Every locale that has an icon gets the same render. Writing only en-US left
# the others holding the previous mark, with nothing reporting the mismatch.
shopt -s nullglob
rendered=0
for out in "$meta"/*/images/icon.png; do
    magick "$tmp" -background white -flatten -gravity center \
        -crop 512x512+0+0 +repage -strip "$out"
    echo "$out  512x512"
    rendered=$((rendered + 1))
done
# With nullglob a moved or mistyped metadata tree matches no locale and the
# loop is silent, which is the same failure the loop was added to fix one
# level up: a success that wrote nothing.
if [ "$rendered" -eq 0 ]; then
    echo "no icon.png under $meta - nothing rendered" >&2
    exit 1
fi
