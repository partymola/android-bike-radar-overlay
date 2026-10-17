# Releasing

Read this before bumping the version, writing a CHANGELOG section, or
cutting a `v*` tag.

Releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`,
add a top-level entry to `CHANGELOG.md` (group changes under the
headings Breaking, Features, Fixes, Security, Improvements, Battery,
Bug reports, Upgrading, For developers). Headings are words a rider
uses, never jargon such as UX, Diagnostics or Internal. Sections released
before 1.10.0 used older headings and stay as published. `Breaking` says
what the rider has to change by hand. Order the headings by how many
riders they reach: `Breaking` leads when it reaches everyone, and
`Features` leads when the breakage only reaches a subset, as in 1.5.0,
where it lands on Home Assistant users alone. The section covers
everything since the last tag, not just what is unpushed - read the range
as `v<last>..HEAD`.

Each bullet is one short, plain statement, in words a rider uses rather
than an app engineer's ("in the background", "the service", "a missing
permission" without naming it). A fix names the bad behaviour it ends, so
a rider can tell whether it ever touched them. Where a detail is needed,
give it one or two nested bullets, never a paragraph. Say "after
installing this version", never "after the update". Write each bullet on
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
