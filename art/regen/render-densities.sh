#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Render the launcher-foreground density PNGs from the SVG master.
# Requires rsvg-convert (librsvg). Run from the repo root or anywhere.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
svg="$here/../br-mark.svg"
res="$here/../../app/src/main/res"

for spec in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    d="${spec%%:*}"; px="${spec##*:}"
    rsvg-convert -w "$px" -h "$px" "$svg" -o "$res/drawable-$d/ic_launcher_foreground.png"
    echo "drawable-$d/ic_launcher_foreground.png  ${px}x${px}"
done
