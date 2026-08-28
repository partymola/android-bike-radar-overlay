#!/usr/bin/env python3
"""Report the licence of every artifact on the release runtime classpath.

The app is GPL-3.0-or-later. The Settings licences screen lists DIRECT
dependencies by design and says so; this covers the other half - a transitive
arriving under a licence GPL-3 cannot absorb, introduced by a dependency bump
that nobody reads as a licensing change.

The classpath is a superset of what ships: R8 removes classes, and four of the
coordinates are BOMs or metadata-only modules that carry no code. A superset is
the safe direction for a licence check. It is not a strict superset, though -
`coreLibraryDesugaring` resolves through its own configuration and is NOT
covered here (it is not enabled at the current minSdk, and
`SettingsLicencesCoverageTest` watches for the declaration appearing).

REPORT-ONLY by default: it prints and exits 0. `--strict` makes an
unrecognised or unresolvable licence exit 1. The report-only default is
deliberate and matches `check-screenshot-freshness.py`: this check needs the
network, and a gate that reds because a CDN blinked teaches people to ignore
it. Read the log, not the exit status, until it has been quiet for a while.
Promoting to `--strict` also means deleting the whole `set +e` ... `exit 0`
wrapper around the invocation in `ci.yml`, which would otherwise discard the
only signal `--strict` produces. Deleting just its `if` line is worse than
leaving it alone: the trailing `exit 0` then swallows the exit-2 abort too.

Exit 2 is the exception and is NOT report-only: it means the check examined
nothing (an unreadable coordinate list, a list that does not look like this
app's classpath, or a run that reached no POM at all). "Checked nothing" must
never read as "found nothing", so the caller is expected to treat 2 as fatal.

Note what exit 2 deliberately does NOT cover: a run where every POM was read
and every licence was unrecognised. That is the loudest finding this script
can produce, so it prints the report and exits 0 (or 1 under `--strict`).

Why it fetches POMs directly, rather than the two routes that look cheaper:

  - The Gradle module cache does not hold a .pom for most of what ships
    (core-ktx, material3 and navigation-compose have none), so a cache reader
    silently skips the majority and reports a clean run.
  - GitHub's dependency-graph SBOM enumerates the shipped set correctly but
    resolves a licence for 13 of 149 artifacts, and none of the 123 AndroidX
    ones.

Both were measured, not assumed. The upstream POMs DO carry the licence, so
one HTTP GET per coordinate answers what neither of those can - and it needs
no Gradle API, and so no configuration-cache exemption.

Usage:
    check-transitive-licences.py --coordinates FILE [--strict] [--cache DIR]
    check-transitive-licences.py --self-test
"""
import argparse
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

POM_NS = "{http://maven.apache.org/POM/4.0.0}"

# Repositories to try, in order. Google's first: everything AndroidX and
# Compose lives there, which is the bulk of what ships.
REPOS = (
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
)

# Exact licence strings accepted as GPL-3.0-or-later compatible, lowercased
# for comparison. Deliberately a list of exact spellings rather than a regex
# on "apache": an unfamiliar spelling should reach a human, and a substring
# match would silently absorb "Apache License 2.0 with Commons Clause", which
# is not a free licence at all.
ALLOWED = {
    "the apache software license, version 2.0",
    "the apache license, version 2.0",
    "apache license, version 2.0",
    "apache license version 2.0",
    "apache 2.0",
    "apache-2.0",
    "the mit license",
    "mit license",
    "mit",
}

# Anti-vacuity: a coordinate list that resolved to nothing, or lost the app's
# own dependencies, must fail loudly rather than report a clean sweep. Every
# build of this app ships AndroidX and the Kotlin stdlib; if neither appears,
# the list is not what it claims to be.
REQUIRED_SUBSTRINGS = ("androidx.", "org.jetbrains.kotlin:kotlin-stdlib")
MIN_COORDINATES = 20


def pom_url(repo, group, name, version):
    return f"{repo}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"


def fetch(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": "licence-check"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", errors="replace")


def fetch_pom(group, name, version, cache_dir, timeout):
    """POM text for a coordinate, from cache or the first repo that has it."""
    key = f"{group}--{name}--{version}.pom"
    cached = os.path.join(cache_dir, key) if cache_dir else None
    if cached and os.path.exists(cached):
        with open(cached, encoding="utf-8", errors="replace") as f:
            return f.read()
    for repo in REPOS:
        try:
            text = fetch(pom_url(repo, group, name, version), timeout)
        except (urllib.error.HTTPError, urllib.error.URLError, OSError):
            continue
        if cached:
            os.makedirs(cache_dir, exist_ok=True)
            with open(cached, "w", encoding="utf-8") as f:
                f.write(text)
        return text
    return None


def licences_in(pom_text):
    """Licence names declared in this POM, or None when it declares none.

    An empty <licenses/> and a missing one both come back falsy, and
    resolve_licences chases the parent for either: a POM that declares an empty
    block in its own right is not something these repositories produce, and
    treating it as final would report "undeclared" for an artifact whose parent
    states the licence plainly.
    """
    try:
        root = ET.fromstring(pom_text)
    except ET.ParseError:
        return None
    block = root.find(f"{POM_NS}licenses")
    if block is None:
        return None
    out = []
    for lic in block.findall(f"{POM_NS}license"):
        name = lic.findtext(f"{POM_NS}name")
        if name:
            out.append(name.strip())
    return out


def parent_of(pom_text):
    try:
        root = ET.fromstring(pom_text)
    except ET.ParseError:
        return None
    p = root.find(f"{POM_NS}parent")
    if p is None:
        return None
    g = p.findtext(f"{POM_NS}groupId")
    a = p.findtext(f"{POM_NS}artifactId")
    v = p.findtext(f"{POM_NS}version")
    return (g, a, v) if g and a and v else None


def resolve_licences(group, name, version, cache_dir, timeout, depth=0):
    """Licence names for a coordinate, following <parent> when it declares none."""
    if depth > 4:
        return None
    text = fetch_pom(group, name, version, cache_dir, timeout)
    if text is None:
        return None
    found = licences_in(text)
    if found:
        return found
    parent = parent_of(text)
    if parent:
        return resolve_licences(*parent, cache_dir, timeout, depth + 1)
    return found  # [] or None - the caller distinguishes them


def classify(names):
    if names is None:
        return "unresolved"
    if not names:
        return "undeclared"
    return "ok" if all(n.strip().lower() in ALLOWED for n in names) else "unrecognised"


def self_test():
    """Prove the classifier can REJECT. A gate that only ever passes is not a
    gate, and this one's whole job is to notice an unfamiliar licence."""
    cases = [
        (["The Apache Software License, Version 2.0"], "ok"),
        (["Apache-2.0"], "ok"),
        (["Eclipse Public License 1.0"], "unrecognised"),
        (["Apache License 2.0 with Commons Clause"], "unrecognised"),
        (["The Apache Software License, Version 2.0", "Proprietary"], "unrecognised"),
        ([], "undeclared"),
        (None, "unresolved"),
    ]
    bad = [(n, want, classify(n)) for n, want in cases if classify(n) != want]
    for names, want, got in bad:
        print(f"SELF-TEST FAIL: {names!r} wanted {want}, got {got}")
    if bad:
        return 1
    # And that the anti-vacuity guard rejects a list that lost its contents.
    if check_coordinates_sane([]) is None:
        print("SELF-TEST FAIL: an empty coordinate list must be rejected")
        return 1
    if check_coordinates_sane(["com.example:thing:1.0"] * 50) is None:
        print("SELF-TEST FAIL: a list with no AndroidX or stdlib must be rejected")
        return 1
    # And that a real list reaching no POM at all is caught one stage later.
    if not resolution_is_vacuous(0, 138):
        print("SELF-TEST FAIL: zero resolutions over a real list must be rejected")
        return 1
    if resolution_is_vacuous(1, 138):
        print("SELF-TEST FAIL: a single resolution must not read as vacuous")
        return 1
    # The quantity, not just the predicate. The defect this guards against was
    # never in resolution_is_vacuous; it was main() feeding it len(ok), which
    # made an all-unrecognised run abort and suppress its own report. Pinning
    # the predicate alone leaves that revertible in silence, so pin the
    # counting rule against real bucket shapes.
    coords32 = ["g:a:1"] * 32
    all_unrecognised = {"ok": [], "unrecognised": coords32, "undeclared": [], "unresolved": []}
    all_undeclared = {"ok": [], "unrecognised": [], "undeclared": coords32, "unresolved": []}
    total_outage = {"ok": [], "unrecognised": [], "undeclared": [], "unresolved": coords32}
    for name, buckets, want_vacuous in (
        ("an all-unrecognised run", all_unrecognised, False),
        ("an all-undeclared run", all_undeclared, False),
        ("a total lookup outage", total_outage, True),
    ):
        got = resolution_is_vacuous(resolved_count(buckets, coords32), len(coords32))
        if got != want_vacuous:
            verb = "must not abort" if not want_vacuous else "must abort"
            print(f"SELF-TEST FAIL: {name} {verb}")
            return 1
    print(f"self-test ok ({len(cases)} classifier cases + 7 anti-vacuity cases)")
    return 0


def resolved_count(buckets, coords):
    """How many coordinates yielded licence metadata, whatever the verdict.

    Deliberately NOT `len(buckets["ok"])`. "ok" means the licence was
    allow-listed, so counting it here would make an all-unrecognised run abort
    as though nothing had been looked at, suppressing the report that names the
    offenders. That run is this script's single loudest finding.
    """
    return len(coords) - len(buckets["unresolved"])


def resolution_is_vacuous(count, total):
    """True when a plausible coordinate list yielded no metadata at all.

    The sane-list guard above proves the INPUT was real; this proves the
    lookups happened. A total network or repository outage otherwise prints
    an orderly report of nothing checked and exits 0.
    """
    return total > 0 and count == 0


def check_coordinates_sane(coords):
    """None when the list looks like a real release classpath, else the reason."""
    if len(coords) < MIN_COORDINATES:
        return f"only {len(coords)} coordinates (expected >= {MIN_COORDINATES})"
    joined = "\n".join(coords)
    missing = [s for s in REQUIRED_SUBSTRINGS if s not in joined]
    if missing:
        return f"no coordinate matches {missing!r}"
    return None


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--coordinates")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv[1:])

    if a.self_test:
        return self_test()
    if not a.coordinates:
        ap.error("--coordinates is required unless --self-test")

    try:
        with open(a.coordinates, encoding="utf-8") as f:
            coords = [ln.strip() for ln in f if ln.strip()]
    except OSError as e:
        # The Gradle task that writes this file may not have run. An
        # unhandled traceback here exits 1, which a report-only caller
        # swallows - so name it as the abort it is.
        print(f"TRANSITIVE LICENCE CHECK ABORTED: cannot read {a.coordinates}: {e}")
        return 2

    vacuous = check_coordinates_sane(coords)
    if vacuous:
        # Always fatal, even report-only: this says the check looked at
        # nothing, which is the one outcome that must never read as a pass.
        print(f"TRANSITIVE LICENCE CHECK ABORTED: {vacuous}")
        return 2

    buckets = {"ok": [], "unrecognised": [], "undeclared": [], "unresolved": []}
    seen = {}
    for coord in coords:
        parts = coord.split(":")
        if len(parts) != 3:
            buckets["unresolved"].append((coord, "unparseable coordinate"))
            continue
        names = resolve_licences(*parts, a.cache, a.timeout)
        verdict = classify(names)
        buckets[verdict].append((coord, ", ".join(names) if names else "-"))
        for n in names or []:
            seen[n.strip()] = seen.get(n.strip(), 0) + 1

    print(f"transitive licence check: {len(coords)} artifacts on the release runtime classpath")
    for verdict in ("ok", "unrecognised", "undeclared", "unresolved"):
        print(f"  {verdict:<13} {len(buckets[verdict])}")
    print()
    print("licence strings seen:")
    for name, n in sorted(seen.items(), key=lambda kv: -kv[1]):
        mark = "ok " if name.lower() in ALLOWED else "!! "
        print(f"  {mark}{n:>4}x  {name}")

    problems = buckets["unrecognised"] + buckets["undeclared"] + buckets["unresolved"]
    if problems:
        print()
        print("needs a human:")
        for coord, detail in problems:
            print(f"  {coord}  ->  {detail}")

    # After the report, so a genuine outage is diagnosable from the log
    # rather than reduced to one line.
    if resolution_is_vacuous(resolved_count(buckets, coords), len(coords)):
        print()
        print(
            "TRANSITIVE LICENCE CHECK ABORTED: reached licence metadata for "
            f"none of the {len(coords)} coordinates",
        )
        return 2

    if a.strict and problems:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
