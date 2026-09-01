# BR mark

`br-mark.svg` is the vector master for the Bike Radar "BR" logo. The launcher
foreground PNGs under `app/src/main/res/drawable-*/ic_launcher_foreground.png`,
the store icons under `fastlane/metadata/android/*/images/icon.png`, and the
in-app `BrMark` composable are all rendered from it.

The mark is two blue letters, separate rather than joined, with two details. The
B's top arm is carried a little to the left past its stem, which widens the
letter and gives the mark a silhouette of its own, and a shallow slot separates
that arm from the stem. A radar sweep lies across the R's bowl, brightest at the
outer curve and dissolving to nothing toward the pivot. The background is fully
transparent, so the mark sits on any surface (the launcher icon composites it
over its own white adaptive-background layer; in-app it draws straight on the
screen).

The arm is the detail that carries at icon size. The slot is not: at 48 px it
changes under 1% of the canvas, so it is there for the large renders, the About
screen and the store icon. Judge any change to it at both sizes, because the two
disagree.

The letterforms are Inter Display Black, read out of the font as outlines. Inter
is under the SIL Open Font License 1.1 with no reserved font name. No font file
ships in the app.

## Rendering the density PNGs

```bash
art/regen/render-densities.sh      # needs rsvg-convert (librsvg)
```

Writes the five densities (108/162/216/324/432 px = mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi).
After rendering, re-record the Roborazzi goldens (the mark appears in the top bar
and About screen): `scripts/dev gradle :app:recordRoborazziDebug`. The portrait
screenshots under `screenshots/` and `fastlane/.../phoneScreenshots/` are copies
of goldens, so re-copy those too.

## Rendering the store listing icons

```bash
art/regen/render-store-icon.sh     # needs rsvg-convert and ImageMagick
```

Writes a 512 px `icon.png` for every locale that has one: the mark on a white
field, cropped to the inner 2/3 of the adaptive-icon canvas so the store listing
matches what launchers show. Output is deterministic (metadata stripped), so
re-running it on an unchanged SVG is a no-op for git.

**The masks are a design constraint, not a post-step.** Android masks an
adaptive icon to a shape of its choosing, and the circle is the one that binds:
the safe zone is a 66dp circle on the 108dp canvas, so what has to fit is the
mark's **diagonal**, not its width. A wide short mark sized to the safe square
loses its corners to that circle, which is how a draft of this mark was clipped
on a phone before the generator was changed to fit the diagonal. The store crop,
which keeps the centre 512 of a 768 render, is the looser of the two.

Two consequences the generator handles and a hand-edit would not. The arm
extension widens the mark past the glyphs, so it is part of the width the scale
is worked out from. And the mark is centred on **what is drawn** rather than on
the glyphs, because the arms hang off the left and centring the letters alone
leaves the whole thing visibly left of centre inside the circle.

## Editing / regenerating

For a small tweak (a colour stop, the slot height, the sweep depth), edit
`br-mark.svg` directly.

To regenerate it from the font:

```bash
python3 art/regen/build-svg.py     # rewrites br-mark.svg
```

That script needs fontTools, Pillow, numpy, rsvg-convert and Inter installed.
**Nothing in the app build needs any of that** - `br-mark.svg` is committed and
self-contained, and every render comes from it. The script exists to change the
construction, not to build the app. Its constants at the top are the whole
design: tracking, arm extension, slot height, sweep span and depth. Each has a
matching flag for trying a value without touching the file.

Note what that portability claim does not cover. The sweep's edges are measured
off a raster, so the output depends on the installed Inter release and on
librsvg. It regenerates byte for byte here; on another machine, or after an
Inter update, it could differ. The committed SVG is the master precisely so that
never matters to a build.

### Design construction (for a redraw from scratch)

- Two separate letters, B then R, set in a heavy geometric sans with normal
  tracking. They must not touch: keeping them apart is what gives the mark its
  own silhouette.
- The B's top **arm carried left past its stem**, by about a fifth of the B's
  width. Enough to change the silhouette, not enough to read as a bracket. The
  lower arm stays where the typeface put it.
- A shallow **slot** under that arm, about a third of the height of the B's
  upper counter, separating the arm from the stem.
- A **sweep** laid over the R's bowl, white fading to transparent toward the
  pivot, its inner and outer edges following the bowl's own curves.

The sweep's edges are the reason the script rasterises the letters before it
finishes: they are measured off the rendered R by casting rays out of its
counter, not drawn as a circle around it. A circle does not follow the bowl, and
the difference is obvious at any size.

The fill is a blue-to-cyan gradient, deep blue at the lower left of the B
brightening to cyan at the R.
