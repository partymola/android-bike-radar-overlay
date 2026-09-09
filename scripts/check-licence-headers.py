#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 JJ del Rio
"""Every source file carries an SPDX identifier and a copyright line.

The two answer different questions. The identifier says what the terms are.
The copyright line says whose terms they are, and it is the half Apache-2.0
s.4(c) obliges a copier to keep, so on the six permissive contract files it is
the only thing that travels with a copy into someone else's tree.

The six are Apache-2.0 and everything else is GPL-3.0-or-later. This checks
that split as well, because a file that drifts to the wrong identifier is
invisible: it compiles, it ships, and only a reader of the header would know.

Scope is files where a header is information rather than noise: Kotlin, AIDL,
first-party Python and shell, and the SVG artwork master. Deliberately NOT the
Markdown, the YAML workflows, the Gradle scripts, the manifest and res/ XML,
the properties files, or the fastlane store listings, whose contents ship
verbatim to shoppers. Vendored files (the Gradle wrapper) keep their upstream
headers and are skipped.

STRICT by default, unlike `check-transitive-licences.py`: this reads the tree
and needs no network, so it cannot red because a CDN blinked. `--fix` inserts
what is missing. `--self-test` proves the check can fail, which a green run
against a conforming tree does not.

Three limits worth knowing before relying on it. `--fix` only ever INSERTS: it
never rewrites a line that is already there, so changing the holder or the year
in the grant would produce a finding per file that `--fix` cannot clear. The
year is read from the grant but not compared, because a notice carries the year
of that file's own first publication, so a file added in a later year will
legitimately differ from the grant. And `--fix` refuses a symlink and anything
that is not UTF-8, both of which the check still reports, so a finding can
outlive a `--fix` run and say so on the next one.

Two things it does NOT do, stated so nobody assumes otherwise. It reads the
year but never compares it, so only the notice's presence is checked. And
`--fix` writes its inserted lines with a bare newline, so repairing a CRLF file
leaves mixed endings; the file's own endings survive, the two added lines do
not match them. No file in scope uses CRLF today.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# The holder is not written here. `additional-permission.txt` is the operative
# legal document and already states it, so it is the single place that declares
# who the copyright belongs to, and this reads it from there. Hard-coding a
# second copy is how the two drift.
GRANT = "additional-permission.txt"
README = "README.md"
HOLDER_RE = re.compile(r"^Copyright \(C\) (\d{4}) (.+?)\.?$", re.M)

PERMISSIVE = "Apache-2.0"
COPYLEFT = "GPL-3.0-or-later"

# The cross-app contract. Kept as a literal list rather than derived from the
# directory, so adding a file to ipc/ does not silently make it permissive.
PERMISSIVE_FILES = {
    "app/src/main/aidl/es/jjrh/bikeradar/ipc/IRadarService.aidl",
    "app/src/main/aidl/es/jjrh/bikeradar/ipc/IRadarListener.aidl",
    "app/src/main/aidl/es/jjrh/bikeradar/ipc/RadarStateParcel.aidl",
    "app/src/main/java/es/jjrh/bikeradar/ipc/RadarContract.kt",
    "app/src/main/java/es/jjrh/bikeradar/ipc/RadarStateParcel.kt",
    "app/src/main/java/es/jjrh/bikeradar/ipc/RadarVehicleParcel.kt",
}

# Upstream files carrying their own headers. Excluded BY NAME rather than left
# to fall out of the suffix table: adding `.bat` for a Windows helper would
# otherwise make `--fix` stamp this repo's copyright onto Gradle Inc.'s file,
# which is the one error this whole check exists on the right side of.
VENDORED = {"gradlew", "gradlew.bat"}

SUFFIX_COMMENT = {".kt": "//", ".aidl": "//", ".py": "#", ".sh": "#", ".svg": None}
EXTRA_FILES = {"scripts/dev": "#"}

# Anti-vacuity. Narrowing SUFFIX_COMMENT shrinks the check in silence: the tree
# still passes and nobody is told it was barely looked at. Dropping `.aidl`
# alone drops the three contract files whose notice is the reason this exists.
# So a file per declared kind must actually have been examined, named here
# independently of the table above. Renaming one reds loudly, which is the
# point: a check that cannot say "I looked at almost nothing" is not a check.
# KEYED BY KIND, and the keys are asserted against the declared scope below.
# A bare set would not be enough: removing one entry is a single edit that
# looks like trimming a list, it reds nothing, and the matching SUFFIX_COMMENT
# edit can land months later also redding nothing, so the two never meet in one
# diff and no reviewer ever sees the pair.
ANCHORS = {
    ".kt": "app/src/main/java/es/jjrh/bikeradar/ipc/RadarContract.kt",
    ".aidl": "app/src/main/aidl/es/jjrh/bikeradar/ipc/IRadarService.aidl",
    ".py": "scripts/check-licence-headers.py",
    ".sh": "art/regen/render-densities.sh",
    ".svg": "art/br-mark.svg",
    "scripts/dev": "scripts/dev",
}

SPDX_RE = re.compile(r"SPDX-License-Identifier:\s*(\S+)")
COPYRIGHT_RE = re.compile(r"Copyright \(C\) (\d{4}) (.+?)\.?\s*(?:-->)?\s*$")

# How far into a file a header may sit. Generous enough for a shebang, an XML
# declaration and a blank line, tight enough that a copyright notice buried in
# prose two hundred lines down does not count as a header.
HEADER_LINES = 6


def tracked_files(root: Path) -> list[str]:
    # -z, because git C-quotes any path holding a non-ASCII or special
    # character. Without it such a path never resolves, `is_file()` is False,
    # and the file is skipped by both the check and `--fix` without a word,
    # which is a silent hole in a check whose whole job is completeness.
    out = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        capture_output=True, text=True, check=True,
    )
    return [p for p in out.stdout.split("\0") if p]


def canonical(root: Path) -> tuple[str, str]:
    """(year, holder), read from the grant rather than restated here."""
    m = HOLDER_RE.search((root / GRANT).read_text())
    if not m:
        sys.exit(f"{GRANT} states no copyright line, so there is nothing to check against")
    return m.group(1), m.group(2)


def in_scope(path: str) -> bool:
    # Only tracked files are considered, and the local-only tree is ignored, so
    # nothing under it can reach here.
    if path in VENDORED:
        return False
    if path in EXTRA_FILES:
        return True
    return Path(path).suffix in SUFFIX_COMMENT


def expected_identifier(path: str) -> str:
    return PERMISSIVE if path in PERMISSIVE_FILES else COPYLEFT


def comment_prefix(path: str) -> str | None:
    if path in EXTRA_FILES:
        return EXTRA_FILES[path]
    return SUFFIX_COMMENT[Path(path).suffix]


def inspect(text: str) -> tuple[str | None, str | None]:
    """Return (spdx identifier, copyright holder) from the file's head."""
    head = text.splitlines()[:HEADER_LINES]
    spdx = None
    holder = None
    for line in head:
        m = SPDX_RE.search(line)
        if m and spdx is None:
            spdx = m.group(1)
        m = COPYRIGHT_RE.search(line)
        if m and holder is None:
            holder = m.group(2)
    return spdx, holder


def scope_findings(root: Path) -> list[tuple[str, str]]:
    """Refuse to report a clean tree when the check barely looked at it."""
    tracked = set(tracked_files(root))
    examined = {p for p in tracked if in_scope(p)}
    out = []

    # The anchors pin one file per declared KIND; they say nothing about the
    # EXTENT of a kind, and every anchor sits outside the subtrees anyone would
    # plausibly exclude. So a single clause in `in_scope` - "headers on tests
    # are noise" is the edit a maintainer would actually make - drops 232 of
    # 410 files with every gate still green. Stating the scope a second time
    # from the data alone, and requiring the two to agree, closes that: the
    # predicate and this set have to be narrowed together or they diverge.
    declared_scope = {
        p for p in tracked
        if p not in VENDORED and (p in EXTRA_FILES or Path(p).suffix in SUFFIX_COMMENT)
    }
    for missed in sorted(declared_scope - examined):
        out.append((missed, "declared in scope but not examined"))
    for extra in sorted(examined - declared_scope):
        out.append((extra, "examined but outside the declared scope"))
    # The anchors guard the scope, so the anchors are tied to the scope: one
    # per declared kind, and each anchor really of that kind. Every way of
    # narrowing the check by a SINGLE edit now reds on that edit, whether it
    # touches SUFFIX_COMMENT, EXTRA_FILES or the anchors themselves.
    #
    # The regress stops here deliberately. Dropping a kind AND its anchor
    # together still passes, and no further level would change that: any guard
    # can be removed by removing the guard. What that costs is two deliberate
    # edits in one diff, which is what review is for.
    declared = set(SUFFIX_COMMENT) | set(EXTRA_FILES)
    if set(ANCHORS) != declared:
        out.append((
            "ANCHORS",
            f"covers {sorted(ANCHORS)}, but the declared scope is {sorted(declared)}",
        ))
    for kind, anchor in sorted(ANCHORS.items()):
        wanted = kind if kind in EXTRA_FILES else None
        if wanted is not None and anchor != wanted:
            out.append(("ANCHORS", f"the {kind} anchor is {anchor}, not {kind}"))
        elif wanted is None and Path(anchor).suffix != kind:
            out.append(("ANCHORS", f"the {kind} anchor is {anchor}, not a {kind} file"))
        if anchor not in tracked:
            out.append(("ANCHORS", f"names {anchor}, which git does not track"))
        elif anchor not in examined:
            out.append((anchor, "tracked but not examined, so the check has been narrowed"))
    # NOT redundant, whatever it looks like beside the two checks above. Those
    # are keyed by declared KIND, so a contract file with an undeclared suffix
    # has no anchor and the scope comparison agrees with itself in silence.
    # This loop is keyed to the six paths themselves, so it is the only thing
    # that speaks when PERMISSIVE_FILES grows past the kinds the table names -
    # which is the scenario AGENTS.md tells maintainers to expect, and where
    # the failure is a file this check claims to protect never being read.
    for permissive in sorted(PERMISSIVE_FILES & tracked):
        if permissive not in examined:
            out.append((permissive, "a permissive contract file is outside the check's scope"))
    return out


def findings(root: Path, holder: str) -> list[tuple[str, str]]:
    out = []
    for path in tracked_files(root):
        if not in_scope(path):
            continue
        f = root / path
        if not f.is_file():
            continue
        spdx, found = inspect(f.read_text(errors="replace"))
        want = expected_identifier(path)
        if spdx is None:
            out.append((path, "no SPDX identifier"))
        elif spdx != want:
            out.append((path, f"SPDX says {spdx}, expected {want}"))
        if found is None:
            out.append((path, "no copyright line"))
        elif found != holder:
            out.append((path, f"copyright names {found!r}, expected {holder!r}"))
    return out


def doc_findings(root: Path, notice: str) -> list[tuple[str, str]]:
    """Facts that exist in more than one place have to agree.

    The holder lives in the grant and is restated in README; the six permissive
    paths live in PERMISSIVE_FILES and are listed in README again. Nothing
    connected them, so either copy could drift in silence.
    """
    out = []
    readme = (root / README).read_text(errors="replace")
    if notice not in readme:
        out.append((README, f"licence section does not carry {notice!r} from {GRANT}"))

    # Anchored to the paragraph that makes the permissive claim, not to README
    # as a whole: a basename mentioned in an architecture section elsewhere
    # would otherwise let the licence list be gutted with this still green.
    # Chosen by how many of the files it names, because more than one paragraph
    # mentions the licence and only one of them is the list.
    expected = {Path(p).name for p in PERMISSIVE_FILES}
    paragraphs = [p for p in readme.split("\n\n") if PERMISSIVE in p]
    para = max(paragraphs, key=lambda p: sum(n in p for n in expected), default=None)
    if para is None or not any(n in para for n in expected):
        out.append((README, f"has no paragraph claiming {PERMISSIVE} for the contract files"))
        return out

    # Set equality, not containment, because the direction that misleads a
    # copier is the one containment misses: a file promised as permissive in
    # README while its own header, correctly, says copyleft.
    claimed = {name for name in expected if name in para}
    if claimed != expected:
        out.append((README, f"names {sorted(claimed)} as {PERMISSIVE}, expected {sorted(expected)}"))
    stray = [
        name
        for name in re.findall(r"`([A-Za-z0-9_]+\.(?:kt|aidl))`", para)
        if name not in expected
    ]
    if stray:
        out.append((README, f"claims {PERMISSIVE} for {sorted(set(stray))}, which is not in the list"))
    return out


def fix(root: Path, notice: str) -> list[str]:
    """Insert a missing SPDX or copyright line. Never rewrites an existing one."""
    changed = []
    for path in tracked_files(root):
        if not in_scope(path):
            continue
        f = root / path
        if not f.is_file():
            continue
        if f.is_symlink():
            # Writing through a symlink edits its target, which may sit outside
            # the repository entirely.
            continue
        prefix = comment_prefix(path)
        if prefix is None:
            # The SVG's header is emitted by art/regen/build-svg.py, so editing
            # the file here would be undone by the next regeneration.
            continue
        # Strict decoding on the write path, and skip loudly rather than raise.
        # `errors="replace"` here would rewrite an undecodable byte as U+FFFD
        # permanently, since the whole file is written back: a loud crash traded
        # for silent corruption. Raising instead would abort mid-run with
        # earlier files already rewritten. The check still reports the file.
        try:
            with open(f, encoding="utf-8", newline="") as handle:
                text = handle.read()
        except UnicodeDecodeError:
            print(f"  skipped {path}: not UTF-8")
            continue
        spdx, found = inspect(text)
        if spdx is not None and found is not None:
            continue
        lines = text.splitlines(keepends=True)
        at = 1 if lines and lines[0].startswith("#!") else 0
        new = []
        if spdx is None:
            new.append(f"{prefix} SPDX-License-Identifier: {expected_identifier(path)}\n")
        if found is None:
            # Directly under the identifier wherever one already exists, rather
            # than under the shebang: deriving the position from the shebang
            # alone puts the notice ABOVE an identifier that sits further down.
            if spdx is not None:
                at = next(
                    i for i, line in enumerate(lines[:HEADER_LINES]) if SPDX_RE.search(line)
                ) + 1
            if at >= HEADER_LINES:
                # The notice would land outside the window `inspect` reads, so
                # the finding would never clear and a duplicate would pile up on
                # every run. Refuse rather than converge on nothing.
                print(f"  skipped {path}: header sits too deep to insert under")
                continue
            new.append(f"{prefix} {notice}\n")
        # A file whose last line has no newline would otherwise have the
        # inserted line concatenated onto it. On a one-line file that welds the
        # shebang to the identifier, and the check then passes, because the
        # identifier is still found on the line: silent damage.
        if at > 0 and lines[at - 1] and not lines[at - 1].endswith("\n"):
            lines[at - 1] += "\n"
        lines[at:at] = new
        # newline="" on both sides, so a CRLF file keeps its endings instead of
        # being silently normalised by the round trip.
        with open(f, "w", encoding="utf-8", newline="") as handle:
            handle.write("".join(lines))
        changed.append(path)
    return changed


def self_test(notice: str, holder: str) -> int:
    """Prove the check can fail. A green run on a clean tree proves nothing."""
    good = f"// SPDX-License-Identifier: GPL-3.0-or-later\n// {notice}\npackage x\n"
    cases = [
        ("missing copyright", "// SPDX-License-Identifier: GPL-3.0-or-later\npackage x\n"),
        ("missing spdx", f"// {notice}\npackage x\n"),
        ("wrong holder", "// SPDX-License-Identifier: GPL-3.0-or-later\n"
                         "// Copyright (C) 2026 Someone Else\npackage x\n"),
        ("wrong identifier", f"// SPDX-License-Identifier: MIT\n// {notice}\npackage x\n"),
        ("header too deep", "package x\n\n\n\n\n\n\n"
                            f"// SPDX-License-Identifier: GPL-3.0-or-later\n// {notice}\n"),
    ]
    failures = 0
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        subprocess.run(["git", "-C", d, "init", "-q"], check=True)
        for label, body in cases:
            (root / "Probe.kt").write_text(body)
            subprocess.run(["git", "-C", d, "add", "Probe.kt"], check=True)
            if not findings(root, holder):
                print(f"SELF-TEST FAILED: '{label}' was not caught")
                failures += 1
            else:
                print(f"  caught  {label}")
        # And the conforming case must pass, or the check is simply always red.
        (root / "Probe.kt").write_text(good)
        subprocess.run(["git", "-C", d, "add", "Probe.kt"], check=True)
        if findings(root, holder):
            print("SELF-TEST FAILED: a conforming file was reported")
            failures += 1
        else:
            print("  passes  a conforming file")

        # --fix is the destructive path and needs its own cases, because the
        # check cannot see the two shapes a naive splice damages: a file whose
        # last line has no newline, where the inserted line welds onto it, and
        # an identifier below line 1, where a position derived from the shebang
        # puts the notice above it.
        (root / GRANT).write_text(f"{notice}.\n")
        # Each case names the property that must hold AFTER the repair, because
        # the check cannot see either kind of damage: a welded shebang still
        # contains the identifier, and a notice above the identifier is still
        # inside the window `inspect` reads.
        def intact_shebang(lines: list[str]) -> bool:
            return lines[0].rstrip("\n") == "#!/bin/sh"

        def notice_below_identifier(lines: list[str]) -> bool:
            # Defaults rather than bare next(): a mutant that removes either
            # line would otherwise raise out of the self-test, and a crash
            # reads as a survivor under output-based scoring.
            spdx = next((i for i, ln in enumerate(lines) if SPDX_RE.search(ln)), None)
            note = next((i for i, ln in enumerate(lines) if COPYRIGHT_RE.search(ln)), None)
            return spdx is not None and note is not None and spdx < note

        repairs = [
            ("no trailing newline", "#!/bin/sh", "sh", intact_shebang),
            ("identifier below the shebang", "#!/bin/sh\nset -eu\n"
             "# SPDX-License-Identifier: GPL-3.0-or-later\n", "sh", notice_below_identifier),
            ("empty file", "", "sh", lambda lines: True),
        ]
        for label, body, ext, holds in repairs:
            probe = root / f"probe.{ext}"
            probe.write_text(body)
            subprocess.run(["git", "-C", d, "add", probe.name], check=True)
            fix(root, notice)
            after = probe.read_text().splitlines()
            problems = [w for p, w in findings(root, holder) if p == probe.name]
            if problems or not holds(after):
                print(f"SELF-TEST FAILED: --fix left '{label}' broken: {problems or after!r}")
                failures += 1
            else:
                print(f"  repairs {label}")
            probe.unlink()
            subprocess.run(["git", "-C", d, "rm", "-q", "--cached", probe.name], check=True)

        # --fix must converge. An identifier on the last line `inspect` reads
        # would otherwise put the notice outside that window, so the finding
        # never clears and a duplicate accumulates on every run.
        deep = "#a\n#b\n#c\n#d\n#e\n# SPDX-License-Identifier: GPL-3.0-or-later\nprint(1)\n"
        probe = root / "deep.py"
        probe.write_text(deep)
        subprocess.run(["git", "-C", d, "add", probe.name], check=True)
        fix(root, notice)
        fix(root, notice)
        if probe.read_text().count(notice) > 1:
            print("SELF-TEST FAILED: --fix accumulated duplicate notices")
            failures += 1
        else:
            print("  converges on a header too deep to insert under")
        probe.unlink()
        subprocess.run(["git", "-C", d, "rm", "-q", "--cached", probe.name], check=True)

        # doc_findings has its own vacuity problem: it is called only from
        # main(), so a regression to `return []` leaves both the self-test and
        # a conforming tree green.
        # The fixture has to resemble the real README in the one dimension the
        # anchoring depends on: SEVERAL paragraphs mention the licence and only
        # one of them is the list. A single-paragraph fixture passes whatever
        # the anchor picks, which is how a wrong anchor shipped once already.
        readme = root / README
        names = " ".join(f"`{Path(p).name}`" for p in PERMISSIVE_FILES)
        one = sorted(PERMISSIVE_FILES)[0]
        lead = f"{notice}. Six cross-app files are {PERMISSIVE} instead, listed below."
        trail = f"Architecture: `{Path(one).name}` is the entry point, {PERMISSIVE} as above."
        conforming = f"# Probe\n\n{lead}\n\nSix files are {PERMISSIVE}: {names}.\n\n{trail}\n"
        readme.write_text(conforming)
        subprocess.run(["git", "-C", d, "add", README], check=True)
        doc_cases = [
            ("README missing the notice",
             f"# Probe\n\nSix files are {PERMISSIVE}: {names}.\n\n{trail}\n"),
            ("README dropping a permissive file",
             f"# Probe\n\n{lead}\n\nSix files are {PERMISSIVE}: "
             + " ".join(f"`{Path(p).name}`" for p in sorted(PERMISSIVE_FILES)[1:])
             + f".\n\n{trail}\n"),
            ("README claiming a file that is not permissive",
             f"# Probe\n\n{lead}\n\nSix files are {PERMISSIVE}: {names} `Stray.kt`.\n\n{trail}\n"),
        ]
        # scope_findings is anchored to real repository paths, so its semantics
        # cannot be exercised here. Its LIVENESS can: a synthetic repo tracks
        # none of the anchors, so it must report. A regression to `return []`
        # would otherwise leave the tree and this self-test both green, which
        # is the vacuity the function exists to prevent.
        if not scope_findings(root):
            print("SELF-TEST FAILED: scope_findings reported nothing on a tree with no anchors")
            failures += 1
        else:
            print("  live    scope_findings on a tree holding no anchors")

        if doc_findings(root, notice):
            print("SELF-TEST FAILED: a conforming README was reported")
            failures += 1
        else:
            print("  passes  a conforming README")
        for label, body in doc_cases:
            readme.write_text(body)
            if not doc_findings(root, notice):
                print(f"SELF-TEST FAILED: '{label}' was not caught")
                failures += 1
            else:
                print(f"  caught  {label}")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--fix", action="store_true", help="insert what is missing")
    ap.add_argument("--self-test", action="store_true", help="prove the check can fail")
    ap.add_argument("--root", default=".", help="repository root")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve()
    year, holder = canonical(root)
    notice = f"Copyright (C) {year} {holder}"

    if args.self_test:
        return self_test(notice, holder)

    if args.fix:
        changed = fix(root, notice)
        print(f"{len(changed)} files updated")
        for p in changed[:20]:
            print(f"  {p}")
        if len(changed) > 20:
            print(f"  ... and {len(changed) - 20} more")

    problems = scope_findings(root) + findings(root, holder) + doc_findings(root, notice)
    if problems:
        # Capped like the --fix list above: a real narrowing of the scope emits
        # one line per file, which is hundreds, and the count plus a sample is
        # what a reader acts on.
        print(f"\n{len(problems)} problems:")
        for path, why in problems[:20]:
            print(f"  {path}: {why}")
        if len(problems) > 20:
            print(f"  ... and {len(problems) - 20} more")
        return 1
    print("every file in scope carries an identifier and a copyright line")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
