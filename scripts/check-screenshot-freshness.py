#!/usr/bin/env python3
# Are the screenshots in the README and the store listing still copies of a
# current Roborazzi golden?
#
# Every portrait image under screenshots/ and fastlane/.../phoneScreenshots/ is
# a byte copy of a golden from app/src/test/snapshots/images/. Nothing keeps
# them that way: the goldens re-record whenever a screen changes, the copies do
# not, and no other gate can see inside a PNG. They had drifted far enough to
# advertise a credential-encryption layer the app does not have, an entity list
# it no longer renders, and a version from the alpha series.
#
# What it does NOT check, all three worth knowing before trusting a pass:
#
#  - That an image is a copy of the RIGHT golden. It tests membership of the
#    golden set, so the licences screen sitting where the About screen belongs
#    passes. The README alt text is the only statement of which screen goes in
#    which slot, and it is prose.
#  - The landscape images at all. They are genuine device captures of the ride
#    overlay, which no golden reproduces - the overlay goldens are a 390px-wide
#    strip of the View alone. Note what that means: device captures are the
#    class that can carry a real Home Assistant host and real device names, and
#    they are exactly the class this cannot inspect. It covers the safe images.
#  - Whether a skipped image is really a device capture. Landscape is decided
#    from the IHDR width and height, which is data inside the file being
#    checked, so a portrait image edited to claim landscape is exempt.
#
# Report-only, and wired into CI as a non-blocking step. Not a gate: the
# goldens re-record on any UI change, so blocking would turn CI red on every
# UI pull request until the marketing copies were refreshed in the same commit,
# and a check that fires on routine work gets bypassed. Running it on every
# push is the point - the encryption claim survived for months precisely
# because nothing ran.
#
# Usage:
#   check-screenshot-freshness.py
#
# Exit: 0 = every portrait image matches a current golden, 1 = at least one is
#       stale, 2 = setup error, including finding nothing to check.

import hashlib
import struct
import sys
from pathlib import Path

GOLDEN_DIR = Path("app/src/test/snapshots/images")
PUBLIC_DIRS = [
    Path("screenshots"),
    Path("fastlane/metadata/android/en-US/images/phoneScreenshots"),
    Path("fastlane/metadata/android/es-ES/images/phoneScreenshots"),
]


def setup_error(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def main():
    if len(sys.argv) > 1:
        setup_error(f"takes no arguments, got {' '.join(sys.argv[1:])}")

    goldens = {hashlib.md5(p.read_bytes()).hexdigest() for p in GOLDEN_DIR.glob("*.png")}
    if not goldens:
        setup_error(f"no goldens under {GOLDEN_DIR}")

    for directory in PUBLIC_DIRS:
        if not directory.is_dir():
            setup_error(f"no directory at {directory}; has it moved?")

    stale, checked, skipped = [], 0, 0
    for directory in PUBLIC_DIRS:
        for image in sorted(directory.glob("*.png")):
            data = image.read_bytes()
            width, height = struct.unpack(">II", data[16:24])
            if width > height:
                skipped += 1
                continue
            checked += 1
            if hashlib.md5(data).hexdigest() not in goldens:
                stale.append(image)

    # Reported as a setup error, not a pass. Finding nothing to compare is how
    # this check would go quiet after a directory move, which is the same way
    # the images went stale in the first place.
    if checked == 0:
        setup_error("found no portrait screenshots to check")

    if stale:
        print("Screenshots that are no longer a copy of any current golden:", file=sys.stderr)
        for image in stale:
            print(f"  {image}", file=sys.stderr)
        print(
            "\nRe-copy each from the golden of the screen it shows, under "
            f"{GOLDEN_DIR}. Do not re-capture from a device: that yields a "
            "different size and can carry a real Home Assistant host, real "
            "device names and other things the fixtures keep out.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"screenshots fresh: {checked} match a current golden, {skipped} landscape skipped")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        # Exit 2, not 1: a crash here is a broken check, and exit 1 would read
        # as "a screenshot is stale". A truncated PNG reaches this path.
        import traceback

        traceback.print_exc()
        setup_error("unexpected failure in the check itself")
