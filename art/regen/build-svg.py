#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate art/br-mark.svg.

The mark is two letters set in Inter Display Black, with a slot cut through the
B's left stem and a radar sweep laid into the R's bowl. The letterforms are read
straight out of the font as Bezier outlines, so the curves are the type
designer's; the sweep is not drawn as a circle around the counter but measured
off the rendered R, which is what makes its edges follow the bowl.

Nothing in the app build needs this script. `art/br-mark.svg` is committed and
self-contained, and the launcher and store renders come from it. Run this only
to change the construction, and commit the SVG it writes.

    art/regen/build-svg.py [--font PATH_TO_Inter.ttc] [--out art/br-mark.svg]

Needs fontTools, Pillow, numpy and rsvg-convert. Inter is SIL OFL 1.1 with no
reserved font name. No font file ships in the app.
"""
import argparse
import math
import os
import subprocess
import tempfile

import numpy as np
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection, TTFont
from PIL import Image

CANVAS = 1296
# Android masks an adaptive icon to a shape of its choosing, and the shape that
# binds is the circle: the safe zone is a 66dp circle on the 108dp canvas. A
# wide mark that fits the safe SQUARE still loses its corners to that circle,
# measured, so what has to fit is the mark's diagonal.
# The 0.94 is breathing room rather than arithmetic: sitting exactly on the
# circle leaves the mark touching the mask edge, and the rasteriser's
# antialiasing spills a pixel past the vector bounds anyway.
SAFE_RADIUS = CANVAS * (66 / 108) / 2 * 0.94
# The store icon renders at 768 and keeps the centre 512, which is the inner two
# thirds. The circle is the tighter of the two, so clearing it clears both.

BLUE, CYAN = "#0340EB", "#03E2E5"
SUBFAMILY = "Black"
TRACKING = 0.10         # extra space between the letters, as a share of the B's width
SLOT_HEIGHT = 0.0       # of the cap height; unused while SLOT_COUNTER is set, below
SLOT_OVERHANG = 0.04    # of the B's width, past the stem
SLOT_REACH = None       # set to a multiple of the B's width to override the above
SLOT_AXIS = "horizontal"  # or "vertical": a cut running the full height instead
SLOT_POS = 0.30         # vertical only: where the cut sits across the mark's width
BAR_EXTEND = 0.18       # of the B's width: carries the B's arms left past its stem
BAR_SIDES = "top"       # or "both": extend the lower arm as well
SLOT_COUNTER = 0.35     # slot height as a share of the B's upper counter; overrides SLOT_HEIGHT
SWEEP_SPAN = (120, -20)  # degrees, 0 is east and angles grow anticlockwise
SWEEP_DEPTH = 56.0      # thickness of the sweep, measured in from the bowl's outer edge
SWEEP_INSET = 6.0       # canvas units held back from the bowl's edges, so the sweep
                        # does not spill over the letter's own outline

HEADER = """<!-- SPDX-License-Identifier: GPL-3.0-or-later
     BR mark - vector master. Regenerate with art/regen/build-svg.py; render the
     launcher foregrounds with art/regen/render-densities.sh (rsvg-convert to
     108/162/216/324/432 px = mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) and the store
     icons with art/regen/render-store-icon.sh. Editing this file directly is
     fine for small tweaks; re-run the script to re-derive it. -->
"""


def find_font(explicit):
    if explicit:
        return explicit
    for candidate in ("/usr/share/fonts/inter/Inter.ttc",
                      "/usr/share/fonts/truetype/inter/Inter.ttc"):
        if os.path.exists(candidate):
            return candidate
    try:
        out = subprocess.run(["fc-match", "-f", "%{file}", "Inter Display:style=Black"],
                             capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        out = ""
    if not out:
        raise SystemExit("Inter not found; pass --font")
    return out


def load_face(path):
    fonts = TTCollection(path).fonts if path.endswith(".ttc") else [TTFont(path)]
    for font in fonts:
        names = {n.nameID: str(n) for n in font["name"].names if n.platformID == 3}
        family = names.get(1, "") + names.get(16, "")
        if "Display" in family and SUBFAMILY in (names.get(2, ""), names.get(17, "")):
            return font
    raise SystemExit(f"no Inter Display {SUBFAMILY} face in {path}")


def glyph_path(font, char, dx=0.0):
    glyphs = font.getGlyphSet()
    pen = SVGPathPen(glyphs)
    glyphs[font.getBestCmap()[ord(char)]].draw(TransformPen(pen, (1, 0, 0, 1, dx, 0)))
    return pen.getCommands()


def glyph_bounds(font, char):
    glyphs = font.getGlyphSet()
    pen = BoundsPen(glyphs)
    glyphs[font.getBestCmap()[ord(char)]].draw(pen)
    return pen.bounds


def rasterise(svg_text, tmpdir):
    svg_path = os.path.join(tmpdir, "probe.svg")
    png_path = os.path.join(tmpdir, "probe.png")
    with open(svg_path, "w") as fh:
        fh.write(svg_text)
    subprocess.run(["rsvg-convert", "-w", str(CANVAS), "-h", str(CANVAS),
                    svg_path, "-o", png_path], check=True)
    return np.asarray(Image.open(png_path).convert("RGBA"))[..., 3] > 128


def counters(ink):
    """The enclosed holes, as (cx, cy, r), left to right then top to bottom.

    Flood the background in from the border; whatever background survives is a
    counter. These are measured rather than derived from the glyph's bounding
    box, which puts the sweep outside the letter.
    """
    h, w = ink.shape
    outside = np.zeros_like(ink)
    stack = [(0, 0)]
    while stack:
        y, x = stack.pop()
        if y < 0 or x < 0 or y >= h or x >= w or outside[y, x] or ink[y, x]:
            continue
        outside[y, x] = True
        stack += [(y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)]

    holes = ~ink & ~outside
    found, seen = [], np.zeros_like(holes)
    for sy, sx in zip(*np.nonzero(holes)):
        if seen[sy, sx]:
            continue
        blob, stack = [], [(sy, sx)]
        while stack:
            y, x = stack.pop()
            if y < 0 or x < 0 or y >= h or x >= w or seen[y, x] or not holes[y, x]:
                continue
            seen[y, x] = True
            blob.append((y, x))
            stack += [(y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)]
        if len(blob) < 200:
            continue  # an antialiasing speck, not a counter
        ys = [p[0] for p in blob]
        xs = [p[1] for p in blob]
        found.append((sum(xs) / len(xs), sum(ys) / len(ys),
                      min(max(xs) - min(xs), max(ys) - min(ys)) / 2))
    return sorted(found, key=lambda c: (c[0], c[1]))


def bowl_profile(ink, cx, cy, a0, a1, samples=90):
    """Where the R's ink starts and ends along each ray out of its counter."""
    rows, cols = ink.shape
    reach = int(math.hypot(rows, cols))
    out = []
    for i in range(samples + 1):
        deg = a0 + (a1 - a0) * i / samples
        dx, dy = math.cos(math.radians(deg)), -math.sin(math.radians(deg))
        r_in = r_out = None
        for r in range(reach):
            x, y = int(cx + dx * r), int(cy + dy * r)
            if not (0 <= x < cols and 0 <= y < rows):
                break
            if ink[y, x]:
                if r_in is None:
                    r_in = r
                r_out = r
            elif r_in is not None:
                break
        if r_in is not None and r_out - r_in > 4:
            out.append((deg, float(r_in), float(r_out)))
    return out


def sweep_path(cx, cy, profile, inset, depth):
    def at(deg, r):
        a = math.radians(deg)
        return cx + math.cos(a) * r, cy - math.sin(a) * r

    outer = [at(d, ro - inset) for d, _, ro in profile]
    inner = [at(d, max(ri + inset, ro - inset - depth)) for d, ri, ro in reversed(profile)]
    pts = outer + inner
    return "M " + " L ".join(f"{x:.2f} {y:.2f}" for x, y in pts) + " Z"


def build(font, tmpdir):
    bx0, by0, bx1, by1 = glyph_bounds(font, "B")
    rx0, ry0, rx1, ry1 = glyph_bounds(font, "R")
    shift = bx1 + (bx1 - bx0) * TRACKING - rx0

    x0, x1 = bx0, rx1 + shift
    y0, y1 = min(by0, ry0), max(by1, ry1)
    # Fit the diagonal, not the wider side: the corners are what the circular
    # mask takes off first. The arm extension widens the mark past the glyphs,
    # so it has to be in the width before the scale is worked out, not after.
    reach_font = (bx1 - bx0) * BAR_EXTEND
    drawn_width = (x1 - x0) + reach_font
    scale = SAFE_RADIUS / (math.hypot(drawn_width, y1 - y0) / 2)
    # Centre what is drawn, not what the glyphs alone span: the arms hang off
    # the left, so centring the letters leaves the whole mark sitting left of
    # centre inside the launcher's circle.
    ox = CANVAS / 2 - (x0 + (x1 - x0) / 2) * scale + reach_font * scale / 2
    oy = CANVAS / 2 + (y0 + (y1 - y0) / 2) * scale

    letters = (
        f'<g transform="translate({ox:.2f} {oy:.2f}) scale({scale:.5f} {-scale:.5f})">'
        f'<path d="{glyph_path(font, "B")}"/>'
        f'<path d="{glyph_path(font, "R", shift)}"/></g>'
    )

    ink = rasterise(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}" '
        f'width="{CANVAS}" height="{CANVAS}"><g fill="black">{letters}</g></svg>',
        tmpdir,
    )
    holes = counters(ink)
    left_holes = sorted([h for h in holes if h[0] < CANVAS / 2], key=lambda c: c[1])
    right_holes = [h for h in holes if h[0] >= CANVAS / 2]
    if len(left_holes) < 2 or not right_holes:
        raise SystemExit("expected two counters in the B and one in the R")
    ucx, ucy, ur = left_holes[0]
    rcx, rcy, _ = min(right_holes, key=lambda c: c[1])

    ys, xs = np.nonzero(ink)
    cap = ys.max() - ys.min()
    ink_left = xs.min()
    b_right = ox + bx1 * scale
    ink_right = xs.max()
    slot_thickness = 2 * ur * SLOT_COUNTER if SLOT_COUNTER else cap * SLOT_HEIGHT
    if not slot_thickness:
        slot_rect = ""
    elif SLOT_AXIS == "vertical":
        # A vertical cut runs the whole height of the mark. It reads at launcher
        # size, and it was rejected on looks; the branch is kept because that is
        # a taste judgement someone may want to revisit.
        slot_x = ink_left + (ink_right - ink_left) * SLOT_POS - slot_thickness / 2
        slot_rect = (f'<rect x="{slot_x:.1f}" y="{ys.min() - 8:.1f}" '
                     f'width="{slot_thickness:.1f}" height="{cap + 16:.1f}" fill="black"/>')
    else:
        # The upper counter's top edge carried out through the stem and no
        # further. SLOT_REACH overrides that with a multiple of the B's width.
        if SLOT_REACH is None:
            slot_w = (ucx - ur) - ink_left + (b_right - ink_left) * SLOT_OVERHANG
        else:
            slot_w = (b_right - ink_left) * SLOT_REACH
        slot_rect = (f'<rect x="{ink_left - 4:.1f}" y="{ucy - ur:.1f}" '
                     f'width="{slot_w:.1f}" height="{slot_thickness:.1f}" fill="black"/>')

    # The B's arms carried a little past its stem, which widens the letter
    # without touching the typeface's own shapes. Drawn in canvas space, so they
    # take a canvas-space copy of the gradient rather than the letters' one,
    # which lives in font units inside the flipped group.
    bars = ""
    if BAR_EXTEND:
        top, bottom = ys.min(), ys.max()
        arm = (ucy - ur) - top
        lcy, lr = left_holes[1][1], left_holes[1][2]
        foot = bottom - (lcy + lr)
        reach = (b_right - ink_left) * BAR_EXTEND
        bars = (f'<rect x="{ink_left - reach:.1f}" y="{top:.1f}" '
                f'width="{reach + 2:.1f}" height="{arm:.1f}" fill="url(#inkc)"/>')
        if BAR_SIDES != "top":
            bars += (f'<rect x="{ink_left - reach:.1f}" y="{bottom - foot:.1f}" '
                     f'width="{reach + 2:.1f}" height="{foot:.1f}" fill="url(#inkc)"/>')

    profile = bowl_profile(ink, rcx, rcy, *SWEEP_SPAN)
    if not profile:
        raise SystemExit("no bowl found on the R")
    beam = sweep_path(rcx, rcy, profile, SWEEP_INSET, SWEEP_DEPTH)
    mid = sum(SWEEP_SPAN) / 2
    # Where the sweep's gradient reaches full white. It only has to outrun the
    # bowl, so it is a share of the canvas rather than a measured length.
    fade = CANVAS * 0.2
    tip = (rcx + fade * math.cos(math.radians(mid)), rcy - fade * math.sin(math.radians(mid)))

    return (
        HEADER
        + f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}">\n'
        '<defs>\n'
        '<linearGradient id="ink" gradientUnits="userSpaceOnUse" '
        f'x1="{x0:.0f}" y1="{y0:.0f}" x2="{x1:.0f}" y2="{y1:.0f}">'
        f'<stop offset="0" stop-color="{BLUE}"/><stop offset="1" stop-color="{CYAN}"/>'
        '</linearGradient>\n'
        '<linearGradient id="beam" gradientUnits="userSpaceOnUse" '
        f'x1="{rcx:.1f}" y1="{rcy:.1f}" x2="{tip[0]:.1f}" y2="{tip[1]:.1f}">'
        '<stop offset="0" stop-color="#ffffff" stop-opacity="0"/>'
        '<stop offset="1" stop-color="#ffffff" stop-opacity="1"/>'
        '</linearGradient>\n'
        '<linearGradient id="inkc" gradientUnits="userSpaceOnUse" '
        f'x1="{ink_left:.0f}" y1="{ys.max():.0f}" x2="{ink_right:.0f}" y2="{ys.min():.0f}">'
        f'<stop offset="0" stop-color="{BLUE}"/><stop offset="1" stop-color="{CYAN}"/>'
        '</linearGradient>\n'
        + (f'<mask id="slot"><rect width="{CANVAS}" height="{CANVAS}" fill="white"/>'
           f'{slot_rect}</mask>\n' if slot_rect else "")
        + '</defs>\n'
        + (f'<g mask="url(#slot)">' if slot_rect else "<g>")
        + f'{bars}<g fill="url(#ink)">{letters}</g></g>\n'
        f'<path d="{beam}" fill="url(#beam)"/>\n'
        '</svg>\n'
    )


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser()
    p.add_argument("--font")
    p.add_argument("--out", default=os.path.join(here, os.pardir, "br-mark.svg"))
    p.add_argument("--tracking", type=float,
                   help="override the space between the letters, for comparing options")
    p.add_argument("--slot-reach", type=float,
                   help="override the slot's length, as a multiple of the B's width")
    p.add_argument("--slot-vertical", type=float, metavar="POS",
                   help="cut vertically instead, at POS across the mark's width")
    p.add_argument("--bar-extend", type=float,
                   help="carry the B's arms left past its stem, as a share of the B's width")
    p.add_argument("--bar-sides", choices=("top", "both"),
                   help="extend the upper arm only, or both")
    # The two ways of sizing the slot are exclusive rather than ranked, so
    # passing both is an error instead of one quietly winning.
    height = p.add_mutually_exclusive_group()
    height.add_argument("--slot-counter", type=float, metavar="SHARE",
                        help="slot height as a share of the B's upper counter; 0 removes it")
    height.add_argument("--slot-height", type=float, metavar="SHARE",
                        help="slot height as a share of the cap height instead")
    args = p.parse_args()

    global TRACKING, SLOT_HEIGHT, SLOT_REACH, SLOT_AXIS, SLOT_POS
    global BAR_EXTEND, BAR_SIDES, SLOT_COUNTER
    if args.bar_extend is not None:
        BAR_EXTEND = args.bar_extend
    if args.bar_sides is not None:
        BAR_SIDES = args.bar_sides
    if args.slot_counter is not None:
        SLOT_COUNTER = args.slot_counter
    if args.tracking is not None:
        TRACKING = args.tracking
    if args.slot_height is not None:
        # SLOT_COUNTER gates SLOT_HEIGHT, so it has to stand down or the flag
        # does nothing at all.
        SLOT_HEIGHT = args.slot_height
        SLOT_COUNTER = None
    if args.slot_reach is not None:
        SLOT_REACH = args.slot_reach
    if args.slot_vertical is not None:
        SLOT_AXIS = "vertical"
        SLOT_POS = args.slot_vertical

    font = load_face(find_font(args.font))
    with tempfile.TemporaryDirectory(prefix="br-mark-") as tmpdir:
        svg = build(font, tmpdir)
    with open(args.out, "w") as fh:
        fh.write(svg)
    print(f"{os.path.normpath(args.out)}  {len(svg.encode('utf-8'))} bytes")


if __name__ == "__main__":
    main()
