# Quality gates: static analysis and coverage

What each CI gate checks and what must not be changed about it; `/qc` runs
the ktlint, lint, coverage and licence-header gates locally. Read this
before changing a workflow under `.github/`, `.editorconfig`, the coverage,
lint or R8 configuration in `app/build.gradle.kts` or
`app/proguard-rules.pro`, or a gate script under `scripts/`.

- **ktlint** (`:app:ktlintCheck`, runs in CI) enforces the `intellij_idea`
  code style set in `.editorconfig`. The codebase is fully formatted and the
  baseline (`app/config/ktlint/baseline.xml`) is empty, so all code must be
  clean; `:app:ktlintFormat` autofixes most issues. Regenerate the baseline
  (`:app:ktlintGenerateBaseline`) only after a deliberate style sweep, never
  to silence a fresh finding.
- **JaCoCo** runs via the on-the-fly agent on `:app:testDebugUnitTest`
  (`JacocoTaskExtension { isIncludeNoLocationClasses = true }`), exec at
  `build/jacoco/testDebugUnitTest.exec`. Do NOT switch to AGP's offline
  `enableUnitTestCoverage`: it cannot see classes loaded through
  Robolectric's sandbox classloader, so Robolectric-tested code silently
  reports 0%.
  - `:app:jacocoTestReport` writes a logic-scoped report (excludes Compose UI
    and framework services) at `app/build/reports/jacoco/jacocoTestReport/`.
  - `:app:jacocoCoverageVerification` (runs in CI and `/qc`) is the ratchet:
    project floors LINE >= 0.80, INSTRUCTION >= 0.78, BRANCH >= 0.68 on the
    whole testable layer, plus a tighter BRANCH >= 0.93 on every `*Decider` /
    `*Deriver` (matched by wildcard) plus `RadarV2Decoder`. Raise the floors in
    `app/build.gradle.kts` as coverage grows.
  - **Diff-coverage gate** (`scripts/diff-coverage-gate.py`, CI and a
    mandatory pre-push `/qc` gate - never leave it to CI alone): the changed
    executable production lines in a PR or push must be >= 85% covered, so an
    untested feature cannot hide behind the project average; a contributor PR
    is the case it most guards. It reads `jacocoDiffReport`, which keeps
    Compose UI in scope and depends on `verifyRoborazziDebug` rather than
    `testDebugUnitTest`, because Roborazzi only composes when its task
    property is set. Diffs under 10 executable changed lines are exempt, and
    an unreachable base ref skips rather than fails.
  - **The script READS `jacocoDiffReport.xml` and does not build it, so run
    `:app:jacocoDiffReport` immediately before it, every time.** CI builds it
    in the same Gradle invocation as the tests; by hand, a stale report is
    indistinguishable from a real measurement.
- **Release DEX keep gate** (`scripts/check-release-dex-keeps.py`, run by
  `:app:verifyReleaseDexKeeps`): one of the two checks on the shrunk release
  APK riders install; the other is `boot-smoke`, below. The gates above run
  the debug variant, which R8 never touches. This one fails if any enum
  constant in its table is absent from the shipped DEX under its exact name.
  The `release-shrink` CI job runs it on every push to `main` and every PR
  targeting `main`, so it reds before a tag exists; `release-apk.yml` names
  the task too, because tag pushes do not trigger `ci.yml`.
  - **The table is the set whose NAME crosses a process boundary**: six enums
    persisted by name and read back with `valueOf()`, plus `VehicleSize`,
    `ClosePassDetector.Side` and `ClosePassDetector.Severity`, published into
    Home Assistant payloads and the close-pass event JSON. Write it by hand
    from the enum declarations, never from a DEX, `usage.txt` or `seeds.txt`,
    which would agree with the artifact by construction. Nothing forces a NEW
    name-crossing enum or constant into it: add it by hand with the enum.
    Deriving the table from the Kotlin SOURCE would close that gap and is not
    the same mistake.
  - A failure means the R8 config change needs a keep rule, not that the gate
    needs an edit. Both failure branches were measured once against real R8
    output, and nothing re-runs them: removing `-dontobfuscate` dropped all
    nine descriptors, and removing `-dontoptimize` dropped seven light-mode
    constants. Which R8 pass does the second, and whether `valueOf` would still
    resolve those names, is not established: the gate pins the contract, not
    a reproduction of rider-visible harm. `--self-test` covers the parser and
    runs first.
  - The gate pins names, not order. The two ordering hazards it cannot see,
    `CameraLightMode`'s `ordinal + 1` wire value and `RadarLightMode`'s order,
    are in AGENTS.md's Gotchas.
  - **Deliberately not wired to `assembleRelease`**, so a packager's
    from-source build (the F-Droid path) needs neither `python3` nor
    `dexdump`. The cost: an APK built outside `ci.yml` and `release-apk.yml` is
    not gate-checked. Publish through the workflow.
  - **Do not add `testReleaseUnitTest` and call the release covered**: it
    runs against release-variant classes before R8.
  - **`boot-smoke`** is the other check: on every push to `main` it installs
    the shrunk release APK on an API 34 emulator and fails unless the process
    is alive at launch and ten seconds later, with no fatal exception in
    logcat. The emulator has no BLE and no overlay permission, so the link,
    overlay and alert paths run on no release build in CI, and the
    release-SIGNED artifact itself is never booted. Nothing requires a ride
    test of a minified build before a tag either. Read a green pair as "the
    names survived and it starts", never as evidence the release works on a
    ride.
  - **`release-shrink` also keeps its APK as a run artifact, on pushes
    only**, for hardware reports. It is DEBUG-SIGNED with a keypair minted per
    runner, so two runs' artifacts will not install over each other
    (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling loses pairing and
    settings), and neither upgrades a store install; back-and-forth testing
    wants a local build. Not on pull requests, which would publish a binary
    built from unreviewed fork code. Artifacts expire after 90 days and need
    a login; use a tagged release for anything longer-lived.
- **Licence headers** (`scripts/check-licence-headers.py`; its docstring and
  header comments are the reference): every Kotlin, AIDL, first-party Python
  and shell file, plus the SVG master, carries an SPDX identifier AND a
  copyright line, `Apache-2.0` on the six cross-app contract files and
  `GPL-3.0-or-later` everywhere else. **Blocking in `ci.yml`**: it reads only
  the working tree, so it cannot red on a CDN blip.
  - The expected holder is read from `additional-permission.txt`, so the name
    is declared once, and the check asserts README carries the same notice and
    names the same six files.
  - `--self-test` runs first and is fatal, covering the check, the README seam
    and `--fix`.
  - **`ANCHORS` and `SUBTREE_ANCHORS` are keyed dicts and must stay keyed.**
    `ANCHORS` names a tracked file per declared kind; as a bare set, dropping
    one entry would disarm its kind silently. `SUBTREE_ANCHORS` names one per
    subtree, keyed so an anchor cannot be retargeted out of it, which makes a
    narrowed `tracked_files` red naming the subtree it lost. That narrowing
    cannot be closed from inside, only made audible (the comments above
    `SUBTREE_ANCHORS` say where it still passes). `scoped()` computes the scope
    once and `findings()` and `fix()` are handed it; do not re-derive it.
  - **The year is read but deliberately not compared**, because a notice
    carries its own file's first-publication year. Never compare it against
    the current year, which would red every January.
- **Transitive licence check** (`scripts/check-transitive-licences.py`, fed by
  `:app:writeReleaseRuntimeCoordinates`; the docstring is the reference,
  including why it fetches POMs rather than reading the Gradle cache or
  GitHub's SBOM): reports the licence of every artifact on the release runtime
  classpath. **Findings are report-only in `ci.yml`**, because it reaches the
  network; read the log, not the exit status. `--strict` makes findings fail;
  promoting to it means adding the flag AND deleting the whole `set +e`
  wrapper in `ci.yml`, as the docstring explains.
  - **Exit 2 is always fatal**: the check examined nothing. **Never put
    `continue-on-error` on the step**, which discards exit 2 exactly as it
    discards exit 1; the step swallows findings in its own script instead.
  - **The allow-list is exact spellings, not a regex on "apache"**, so
    "Apache License 2.0 with Commons Clause" reaches a human. Dropping one
    Apache spelling from it flips almost every artifact to `unrecognised`,
    which is the evidence it bites on the real classpath.
  - **It is hardening, not a compliance fix.** Measured Aug 2026: no artifact
    on the classpath distributes a `NOTICE` file, so Apache 2.0 s.4(d) has
    nothing to carry forward, and the APK embeds the full Apache 2.0 text for
    six AndroidX artifacts. The script reads POM licences, not NOTICE files,
    so nothing re-checks the NOTICE finding. The s.4(d) trigger is
    whether the upstream **Work** ships a NOTICE, so never argue it from our
    own APK's contents, which would make stripping NOTICE files read as a
    defence.
  - `coreLibraryDesugaring`'s payload is GPL-2.0-with-Classpath-Exception, a
    real legal question deliberately not pre-vetted. It is not enabled
    (`minSdk 31`), and `SettingsLicencesCoverageTest` watches for the
    declaration appearing.
- **detekt** is intentionally not wired: no stable release targets the
  pinned Kotlin 2.4 yet (only alpha builds do), and an alpha doesn't belong
  in a public build. Revisit when a stable detekt supports the toolchain.
- **CodeQL** (`.github/workflows/codeql.yml`, pushes to `main` and weekly):
  `actions` and `python` buildless, `java-kotlin` from a real
  `:app:assembleDebug`, because CodeQL extracts Kotlin only from an actual
  compile and the buildless mode reports a green scan of nothing. The build
  runs `--no-daemon` with the Kotlin compiler in-process, so the compile stays
  inside the traced process tree, and `--no-build-cache`, so a cached
  `compileDebugKotlin` cannot skip the compiler; a grep fails the step unless
  that task actually executed. The workflow comments say which of those is
  pinned. One workflow covers all three languages because advanced setup
  cannot coexist with GitHub's default setup.
