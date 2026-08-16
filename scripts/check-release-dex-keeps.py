#!/usr/bin/env python3
# Release DEX keep gate: do the symbols whose NAMES are a data contract still
# carry those exact names in the APK that ships?
#
# The release build runs R8 in shrink-only mode (-dontobfuscate -dontoptimize,
# see app/proguard-rules.pro). Some enum names outlive the process that wrote
# them: they are persisted to SharedPreferences and on-disk JSON and read back
# with valueOf(), or published to Home Assistant where a rider's automations
# match on them. Renaming or dropping one of those constants does not fail a
# compile and does not fail a unit test - it silently resets a saved setting to
# its default, or stops an automation firing, on the rider's phone.
#
# Every other CI check runs the debug variant, which R8 never touches, so this
# is the only one that reads the shipped artifact. It reads the DEX rather than
# R8's own usage report because the report describes what R8 says it did, and
# the question here is what the APK actually contains.
#
# Scope is deliberately narrow: symbols whose NAME is load-bearing. It excludes
# things that cannot fail. String literals (org.json field names, the
# SharedPreferences key constants) are data, not symbols, and no R8
# configuration rewrites them. Manifest-declared components are kept by the
# AAPT-generated rules whatever proguard-rules.pro says. Listing either here
# would add checks that pass unconditionally.
#
# The expected table below is hand-written from the enum declarations. Never
# generate it from a DEX, usage.txt or seeds.txt: a table derived from the
# artifact it checks agrees with the artifact by construction.
#
# Usage:
#   check-release-dex-keeps.py --apk PATH --dexdump PATH
#   check-release-dex-keeps.py --self-test
#
# Exit: 0 = every required symbol present, 1 = a required symbol is missing,
#       2 = setup error. Everything that is not a verdict about the APK exits 2,
#       so a broken invocation cannot be mistaken for R8 having stripped
#       something: missing arguments, an unreadable APK or dexdump, no DEX
#       inside the APK, a dexdump that fails, and any unexpected exception.

import argparse
import os
import re
import subprocess
import sys
import tempfile
import traceback
import zipfile
from pathlib import Path

# descriptor -> the constants that must survive under exactly these names.
#
# Persisted by name and read back with valueOf(): a missing constant throws on
# read, so the stored setting falls back to its default.
# Published by name to Home Assistant or into the close-pass event JSON: a
# missing constant breaks whatever the rider matched on.
REQUIRED = {
    "Les/jjrh/bikeradar/data/DashcamOwnership;": ["UNANSWERED", "YES", "NO"],
    "Les/jjrh/bikeradar/data/EBikeOwnership;": ["UNANSWERED", "YES", "NO"],
    "Les/jjrh/bikeradar/data/HaIntent;": ["UNSET", "YES", "NO"],
    "Les/jjrh/bikeradar/CameraLightMode;": [
        "HIGH",
        "MEDIUM",
        "LOW",
        "NIGHT_FLASH",
        "DAY_FLASH",
        "OFF",
    ],
    "Les/jjrh/bikeradar/RadarLightMode;": [
        "NIGHT_FLASH",
        "DAY_FLASH",
        "SOLID",
        "PELOTON",
        "OFF",
    ],
    "Les/jjrh/bikeradar/AttentionKind;": [
        "RADAR_BATTERY",
        "DASHCAM_BATTERY",
        "EBIKE_BATTERY",
        "AUDIO_FAILURES",
        "UNCLEAN_RESTART",
    ],
    "Les/jjrh/bikeradar/VehicleSize;": ["CAR", "TRUCK"],
    "Les/jjrh/bikeradar/ClosePassDetector$Side;": ["LEFT", "RIGHT"],
    "Les/jjrh/bikeradar/ClosePassDetector$Severity;": ["GRAZING", "VERY_CLOSE"],
}

CLASS_RE = re.compile(r"^\s*Class descriptor\s*:\s*'(.+)'\s*$")
SECTION_RE = re.compile(r"^\s*(Static fields|Instance fields|Direct methods|Virtual methods)\s")
NAME_RE = re.compile(r"^\s*name\s*:\s*'(.+)'\s*$")


def parse_static_fields(dump):
    """Map class descriptor -> set of its static field names, from dexdump -f.

    Instance fields and methods repeat the same "name : 'x'" shape, so the
    section header is what says which of them the current name belongs to.
    """
    found = {}
    descriptor = None
    in_static = False
    for line in dump.splitlines():
        m = CLASS_RE.match(line)
        if m:
            descriptor = m.group(1)
            in_static = False
            found.setdefault(descriptor, set())
            continue
        m = SECTION_RE.match(line)
        if m:
            in_static = m.group(1) == "Static fields"
            continue
        if in_static and descriptor:
            m = NAME_RE.match(line)
            if m:
                found[descriptor].add(m.group(1))
    return found


def missing_symbols(found, required):
    """List of (descriptor, constant or None) for everything absent.

    A constant of None means the whole class is gone.
    """
    out = []
    for descriptor, constants in sorted(required.items()):
        if descriptor not in found:
            out.append((descriptor, None))
            continue
        for constant in constants:
            if constant not in found[descriptor]:
                out.append((descriptor, constant))
    return out


def setup_error(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def dump_apk(apk, dexdump):
    """Dump every DEX in the APK.

    dexdump also reads an APK directly, but whether that covers every dex in a
    multidex APK is unverified in both directions. Extracting each entry and
    dumping it is the version whose coverage can be read off the code, which is
    what a gate wants: the failure mode of guessing wrong here is seeing fewer
    classes than shipped.
    """
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as z:
            dex_names = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            if not dex_names:
                setup_error(f"no classes*.dex inside {apk}")
            z.extractall(tmp, members=dex_names)
        chunks = []
        for name in dex_names:
            r = subprocess.run(
                [dexdump, "-f", str(Path(tmp) / name)],
                capture_output=True,
                text=True,
                # DEX strings are MUTF-8, so a non-ASCII identifier would raise
                # under strict decoding and exit as though R8 had stripped
                # something. Replacing is right: the names being matched are
                # ASCII, and a mangled byte elsewhere must not become a verdict.
                errors="replace",
            )
            if r.returncode != 0:
                setup_error(f"dexdump failed on {name}: {r.stderr.strip()}")
            chunks.append(r.stdout)
        return "\n".join(chunks)


SELF_TEST_DUMP = """
  Class descriptor  : 'Lcom/example/Kept;'
  Static fields     -
    #0              : (in Lcom/example/Kept;)
      name          : 'ALPHA'
      type          : 'Lcom/example/Kept;'
    #1              : (in Lcom/example/Kept;)
      name          : 'BETA'
      type          : 'Lcom/example/Kept;'
  Instance fields   -
    #0              : (in Lcom/example/Kept;)
      name          : 'GAMMA'
      type          : 'I'
  Direct methods    -
    #0              : (in Lcom/example/Kept;)
      name          : 'DELTA'
"""


def self_test():
    """Prove the parser can report a miss, not just agree with itself.

    Without this the gate has only ever been run against an APK that satisfies
    it, which is indistinguishable from a parser that matches nothing.
    """
    found = parse_static_fields(SELF_TEST_DUMP)
    checks = [
        (
            "static fields are read",
            found.get("Lcom/example/Kept;") == {"ALPHA", "BETA"},
        ),
        (
            "an instance field is not read as static",
            missing_symbols(found, {"Lcom/example/Kept;": ["GAMMA"]})
            == [("Lcom/example/Kept;", "GAMMA")],
        ),
        (
            "a method name is not read as a static field",
            missing_symbols(found, {"Lcom/example/Kept;": ["DELTA"]})
            == [("Lcom/example/Kept;", "DELTA")],
        ),
        (
            "a present constant passes",
            missing_symbols(found, {"Lcom/example/Kept;": ["ALPHA", "BETA"]}) == [],
        ),
        (
            "a stripped constant is reported",
            missing_symbols(found, {"Lcom/example/Kept;": ["ALPHA", "OMEGA"]})
            == [("Lcom/example/Kept;", "OMEGA")],
        ),
        (
            "an absent class is reported once",
            missing_symbols(found, {"Lcom/example/Gone;": ["ALPHA", "BETA"]})
            == [("Lcom/example/Gone;", None)],
        ),
    ]
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"{'ok  ' if ok else 'FAIL'} {name}", file=sys.stderr if failed else sys.stdout)
    if failed:
        sys.exit(1)
    print(f"self-test passed ({len(checks)} checks)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--dexdump")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        # Refused rather than ignored: returning here after a self-test would
        # report success for a run that never opened the APK it was handed.
        if args.apk or args.dexdump:
            setup_error("--self-test does not take --apk or --dexdump; run it on its own")
        self_test()
        return

    if not args.apk or not args.dexdump:
        setup_error("--apk and --dexdump are both required")
    if not Path(args.apk).is_file():
        setup_error(f"no APK at {args.apk}")
    if not Path(args.dexdump).is_file():
        setup_error(f"no dexdump at {args.dexdump}")
    if not os.access(args.dexdump, os.X_OK):
        setup_error(f"dexdump at {args.dexdump} is not executable")

    found = parse_static_fields(dump_apk(args.apk, args.dexdump))
    # A dump with no classes in it is a broken gate, not a verdict about the
    # APK. Without this it reports all nine classes absent, which is loud but
    # wears exit 1 and reads as R8 having stripped everything - a shape R8 does
    # not produce in shrink-only mode.
    if not found:
        setup_error(f"dexdump produced no classes from {args.apk}")

    missing = missing_symbols(found, REQUIRED)
    if not missing:
        total = sum(len(v) for v in REQUIRED.values())
        print(f"release DEX keeps: {total} constants across {len(REQUIRED)} enums present")
        return

    print("R8 removed or renamed symbols the app matches on by name:", file=sys.stderr)
    for descriptor, constant in missing:
        where = descriptor if constant is None else f"{descriptor} {constant}"
        what = "whole class absent" if constant is None else "constant absent"
        print(f"  {where}  ({what})", file=sys.stderr)
    print(
        "\nThe release build is shrink-only for this reason. Either revert the "
        "R8 configuration change that caused it, or add a keep rule for the "
        "class above in app/proguard-rules.pro. Removing an entry from this "
        "gate's table is only correct when the app has genuinely stopped "
        "persisting or publishing that name.",
        file=sys.stderr,
    )
    sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        # Exit 2, not 1: a crash in here is a broken gate, and reporting it as
        # exit 1 would read identically to R8 having stripped a name.
        traceback.print_exc()
        setup_error("unexpected failure in the gate itself")
