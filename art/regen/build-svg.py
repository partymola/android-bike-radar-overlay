#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerate art/br-mark.svg from the committed intermediates in this folder.
# Raster-free: reads the traced paths + fitted gradient, writes the SVG master.
#
#   python3 art/regen/build-svg.py
#
# See art/README.md for how the intermediates themselves were derived from the
# original raster (potrace traces + a regression gradient fit).

import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "br-mark.svg")


def read(name):
    with open(os.path.join(HERE, name)) as f:
        return f.read().strip()


# Full BR silhouette and the B-only shape, both in potrace output space
# (translate(0,1296) scale(0.1,-0.1) maps them into the 1296 viewBox).
letterforms = read("letterforms.path")
b_eraser = read("b-eraser.path")

# Single continuous blue->cyan gradient: "x1 y1 x2 y2 c0 c1" in userSpace units.
x1, y1, x2, y2, c0, c1 = read("gradient.txt").split()

# Radar sweep wedge (viewBox coords): apex at the pivot, opening to the R's top
# curve. The fan dissolves top->bottom via `soft`; the eraser clips a clear
# channel along the B contour so the fan never touches the letter.
WEDGE = "M 693 483 L 900 450 L 774 645 L 717 618 Z"

svg = f"""<!-- SPDX-License-Identifier: GPL-3.0-or-later
     BR mark - vector master. Regenerate with art/regen/build-svg.py; render the
     launcher foregrounds with art/regen/render-densities.sh (rsvg-convert to
     108/162/216/324/432 px = mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi). Editing this file
     directly is fine for small tweaks; re-run the script to re-derive it. -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1296 1296">
<defs>
<linearGradient id="ink" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">
<stop offset="0" stop-color="#{c0}"/><stop offset="1" stop-color="#{c1}"/>
</linearGradient>
<!-- sweep dissolve: solid at the R's top curve, fading to transparent toward the pivot -->
<linearGradient id="soft" gradientUnits="userSpaceOnUse" x1="795" y1="489" x2="747" y2="627">
<stop offset="0" stop-color="#ffffff"/><stop offset="0.11" stop-color="#e8e8e8"/><stop offset="0.30" stop-color="#5a5a5a"/><stop offset="0.50" stop-color="#242424"/><stop offset="0.68" stop-color="#000000"/>
</linearGradient>
<clipPath id="fanClip"><path d="{WEDGE}"/></clipPath>
<mask id="sweep">
<rect width="1296" height="1296" fill="#fff"/>
<path d="{WEDGE}" fill="url(#soft)"/>
<g clip-path="url(#fanClip)"><g transform="translate(0,1296) scale(0.1,-0.1)">
<path d="{b_eraser}" fill="#000" stroke="#000" stroke-width="480" stroke-linejoin="round"/>
</g></g>
</mask>
</defs>
<g mask="url(#sweep)"><g transform="translate(0,1296) scale(0.1,-0.1)"><path d="{letterforms}" fill="url(#ink)"/></g></g>
</svg>
"""

with open(OUT, "w") as f:
    f.write(svg)
print("wrote", os.path.relpath(OUT, os.path.join(HERE, os.pardir, os.pardir)))
