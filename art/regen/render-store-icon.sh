#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Render the 512 px store-listing icon (fastlane images/icon.png) from the
# SVG master: the mark on a white field, cropped to the inner 2/3 of the
# adaptive-icon canvas so it matches what launchers show. Requires
# rsvg-convert (librsvg) and ImageMagick.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
svg="$here/../br-mark.svg"
out="$here/../../fastlane/metadata/android/en-US/images/icon.png"

tmp="$(mktemp --suffix=.png)"
trap 'rm -f "$tmp"' EXIT
rsvg-convert -w 768 -h 768 "$svg" -o "$tmp"
magick "$tmp" -background white -flatten -gravity center \
    -crop 512x512+0+0 +repage -strip "$out"
echo "$out  512x512"
