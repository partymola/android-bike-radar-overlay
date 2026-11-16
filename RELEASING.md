# Releasing

Read this before bumping the version, writing a CHANGELOG section, or
cutting a `v*` tag.

Releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`,
add a top-level entry to `CHANGELOG.md`. Open it with two or three plain
sentences saying what the release is about and what, if anything, a
rider will notice. Then give each moment where a rider meets a change
its own `###` heading, in a rider's words, such as "While you ride",
"When your radar or dashcam connects" or "When you leave the bike".
Choose the moments from what the release holds and order them by how
many riders they reach. Under each moment, a bold theme line names what
got better ("Steadier beeps", "Better sound"), and the changes sit as
nested bullets under it. Anything a rider has to change by hand goes
under `What you need to do`, first when it reaches every rider. Three
headings keep fixed names and come last, in this order:
`If you report a bug`, `After installing this version` and
`For developers`; their bullets need no theme line. Headings and themes
are words a rider uses, never jargon such as UX, Diagnostics or
Internal. Sections released before 1.12.2 used other headings and stay
as published. The section covers everything since the last tag, not just
what is unpushed - read the range as `v<last>..HEAD`.

Each bullet is one short, plain statement, in words a rider uses rather
than an app engineer's ("in the background", "the service", "a missing
permission" without naming it). A change that reaches only riders with
particular kit says so in its first words. A fix for something a rider
reported, or that a replay of recorded rides showed, says what used to
happen. A fix for something nobody has met on a ride says only what the
app does: no account of what could have gone wrong, and no worst-case
figure. Keep one thing a rider would notice to one bullet, however many
changes it took. Say "after installing this version", never "after the
update". Write each bullet on
a SINGLE line, no hard wrapping: the release workflow copies the section
verbatim into the GitHub release body, which renders every newline as a
line break, so a wrapped bullet shows mid-sentence breaks on the Releases
page. Also add a
short per-version changelog at
`fastlane/metadata/android/{en-US,es-ES}/changelogs/<versionCode>.txt` (the
F-Droid / store "What's New"; keyed by `versionCode`, not the name) - a tight
benefit-framed summary, not the full CHANGELOG section. Then push
a `v*` tag (e.g.
`v1.3.0`). The tag triggers `.github/workflows/release-apk.yml`,
which builds a release-signed APK and publishes a GitHub release.
The workflow sets `prerelease: false`, because the app has been stable
since 1.0.0. Cutting a pre-release means flipping that for the tag.

**A released section is published history, and its wording is never
revised.** The tag was cut from it and the workflow has already copied it
verbatim into the GitHub release body, so editing it here changes the
repo's copy and not the one riders read. When a later measurement shows a
shipped claim was wrong, correct the source the claim came from (the
KDoc, the notes, the test) and state the corrected fact in the next
version's section. Never rewrite the old entry, and never annotate it.
