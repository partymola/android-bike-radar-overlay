# BR mark

`br-mark.svg` is the vector master for the Bike Radar "BR" logo. The launcher
foreground PNGs under `app/src/main/res/drawable-*/ic_launcher_foreground.png`
and the in-app `BrMark` composable are all rendered from it.

The mark is blue "BR" letters with a radar-sweep fan on the R: solid at the R's
top curve, dissolving to transparent toward the pivot. The background is fully
transparent, so it sits on any surface (the launcher icon composites it over its
own white adaptive-background layer; in-app it draws straight on the screen).

## Rendering the density PNGs

```bash
art/regen/render-densities.sh      # needs rsvg-convert (librsvg)
```

Writes the five densities (108/162/216/324/432 px = mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi).
After rendering, re-record the Roborazzi goldens (the mark appears in the top bar
and About screen): `scripts/dev gradle :app:recordRoborazziDebug`.

## Editing / regenerating

For a small tweak (a colour stop, the sweep shape), edit `br-mark.svg` directly.

To regenerate it from the committed intermediates:

```bash
python3 art/regen/build-svg.py     # rewrites br-mark.svg, no raster needed
```

`regen/` holds everything the build script needs:

| File | What it is |
|------|-----------|
| `letterforms.path` | potrace of the full "BR" alpha silhouette (the letter shapes) |
| `b-eraser.path` | potrace of the B as a connected component; dilated in the mask to carve the clear channel between the B and the sweep fan |
| `gradient.txt` | the single blue->cyan fill gradient: `x1 y1 x2 y2 c0 c1` (userSpace coords + two hex stops) |

### How the intermediates were derived (needs the original raster)

The `.path` files came from `potrace -s --flat` over the alpha channel of the
original 432 px foreground (the letterforms were authored art; we traced them
rather than redraw). The gradient was a least-squares plane fit of the RGB
channels over the opaque letter pixels (excluding the light sweep), reduced to a
single blue->cyan axis with the red channel pinned near zero. A prior version
fitted the B and R with two separate gradients hard-split at the midline, which
left a visible vertical seam where the B bridges into the R; the single gradient
here removes it. These steps only need re-running if the source letterforms
change; day-to-day the committed intermediates + `build-svg.py` are enough.
