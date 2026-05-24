# AGENTS.md

Pointer doc for agent-style tools working in this repo. Public-safe.

## Quick start

```bash
docker build -t bike-radar-builder .

# Faster workflow: spin up a persistent build container once per session
# so the Gradle daemon (and Kotlin daemon) stays warm across invocations.
# Warm gradle runs drop from ~2 s to ~0.4 s.
scripts/dev up
scripts/dev gradle :app:testDebugUnitTest --console=plain   # unit tests
scripts/dev gradle :app:assembleDebug --console=plain       # full APK
scripts/dev gradle :app:verifyPaparazziDebug --console=plain
scripts/dev down                                            # when finished

# Or the one-shot pattern (no daemon, slower; safe to use without `dev up`):
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -w /workspace bike-radar-builder \
  gradle :app:testDebugUnitTest --console=plain --no-daemon

# `scripts/dev gradle ...` auto-falls back to the one-shot pattern when
# the persistent container is not up, so it is safe to use either way.

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Paparazzi note: `:app:verifyPaparazziDebug` is local-only because
Paparazzi 2.0.0-SNAPSHOT's layoutlib loader is unreliable on cold-cache
JVMs, so CI can't run it. The `*SnapshotTest` exclusion in
`app/build.gradle.kts` is lifted only when a `*Paparazzi*` task is the one
invoked, so plain `testDebugUnitTest` / CI still skips them but the gate
genuinely runs them. The loader can throw
`sessionParamsBuilder has not been initialized` on the first attempt in a
JVM; `scripts/dev gradle` auto-retries Paparazzi tasks once for this
(usually transparent). For one-shot/manual `gradle` runs, re-run with
`--rerun-tasks` - the warm retry passes.

Releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`,
add a top-level entry to `CHANGELOG.md` (Security / UX / Compatibility
headings - see existing entries for tone), then push a `v*` tag (e.g.
`v0.7.1-alpha`). The tag triggers `.github/workflows/release-apk.yml`,
which builds a release-signed APK and publishes a GitHub pre-release.
The workflow defaults `prerelease: true` until the app exits alpha.

**Build-dir permission gotcha:** if `:app:testDebugUnitTest` fails with
`Unable to delete directory .../test-results/...`, a previous container left
root-owned files. Clean with:

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace bike-radar-builder \
  rm -rf /workspace/app/build
```

## Architecture

- Single foreground service (`BikeRadarService`) handles BLE scan, GATT,
  radar decode, overlay draw, HA MQTT push. No fragments, Compose-only UI.
- The app connects to two BLE device classes: the rear radar and the front
  camera/light. Each has its own AMV unlock UUID pair (see Gotchas).
- HA integration is optional; the overlay works standalone.
- Front-light mode is auto-set on every BLE connect: Day Flash before
  sunset, Night Flash after, using `SunsetCalculator` driven by
  `LocationCache` (one `getLastKnownLocation` read per ride via
  `ACCESS_COARSE_LOCATION`; falls back to London-hardcoded if the
  permission is denied). A one-shot dawn/dusk flip is scheduled for
  the rest of the session. Skipped when `cameraLightUserOverride` is
  set (manual side-button press during the session). See
  `BikeRadarService.kt` connect path.
- Capture log is always written to
  `/sdcard/Android/data/es.jjrh.bikeradar/files/bike-radar-capture-<stamp>.log`.

## Key files

| Path | Role |
|------|------|
| `app/src/main/java/es/jjrh/bikeradar/BikeRadarService.kt` | Unified foreground service |
| `app/src/main/java/es/jjrh/bikeradar/RadarV2Decoder.kt` | V2 target-struct decoder (stateful) |
| `app/src/main/java/es/jjrh/bikeradar/RadarUnlock.kt` | AMV 04 handshake; `DeviceVariant` selects rear-radar or front-camera UUID pair |
| `app/src/main/java/es/jjrh/bikeradar/RadarOverlayView.kt` | Canvas overlay |
| `app/src/main/java/es/jjrh/bikeradar/CameraLightController.kt` | Front camera/light mode-set writes and notify parser |
| `app/src/main/java/es/jjrh/bikeradar/LocationCache.kt` | One-fetch-per-ride GPS cache for SunsetCalculator |
| `app/src/test/java/es/jjrh/bikeradar/RadarV2DecoderTest.kt` | JVM unit tests |

## Protocol reference

Authoritative spec: https://github.com/partymola/bike-radar-docs/blob/main/PROTOCOL.md
(sibling repo). Clone it next to this repo for local reference; reference
decoders in both Python and Kotlin live there.

## Naming rules for contributors

- No "Varia", "Garmin", "RearVue", "Vue" in class, package, or file names.
- Vendor names ALLOWED in `bike-radar-docs/PROTOCOL.md`, KDoc block comments,
  prior-art credits, and device-name-matching heuristics (the radar
  advertises its local name as "RearVue8", so our matchers have to look
  for it literally).
- MQTT topic prefixes and HA entity IDs keep the legacy `varia_` prefix on
  purpose — renaming would break existing subscribers. See
  `HaClient.kt:22-23`.

## Testing

- All decoder logic is pure JVM; test with `:app:testDebugUnitTest`
  (Robolectric). CI runs this alongside `:app:lintDebug`,
  `:app:ktlintCheck`, and `:app:jacocoCoverageVerification` (see Static
  analysis & coverage below). Paparazzi
  screenshot tests are excluded from `testDebugUnitTest` because Paparazzi
  2.0.0-SNAPSHOT's layoutlib loader fails on cold-cache JVMs.
- Locally, run `:app:verifyPaparazziDebug` to compare against golden PNGs.
  This is the QC gate before any push that touches `app/src/main/**`.
- To regenerate goldens: `:app:recordPaparazziDebug --rerun-tasks`. Commit
  the updated PNGs under `app/src/test/snapshots/images/`.
- No Android instrumentation tests (`connectedDebugAndroidTest`) in this repo.
- Decoder tests build a 9-byte target struct via the `target()` helper;
  `templateLocked = true` by default so new tests appear in snapshots.

## Static analysis & coverage

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
    project LINE >= 0.45, BRANCH >= 0.90 on the safety-critical deciders.
    Raise the floors in `app/build.gradle.kts` as coverage grows.
- **detekt** is intentionally not wired: only its 2.0.0-alpha targets the
  pinned Kotlin 2.3, and an alpha doesn't belong in a public build. Revisit
  when a stable detekt supports the toolchain.

## Gotchas

- After `adb install -r` the radar GATT may be left half-open;
  `runRadarConnection`'s ABORT path closes and reconnects automatically
  (~1.5 s). If the reconnect doesn't happen, see live-testing recovery
  below.
- Never subscribe the CCCD of `6a4e3203` (V1 radar char). Subscribing locks
  the radar into V1-only mode and suppresses V2.
- AMV UUID pairs differ by device class: the rear radar uses RX=`6a4e2811`/
  TX=`6a4e2821`; the front camera/light uses RX=`6a4e2810`/TX=`6a4e2820`.
  Mixing the pairs causes silent handshake failure — the device accepts the
  writes but never responds correctly. `RadarUnlock.DeviceVariant` selects
  the right pair (`RADAR` or `FRONT_CAMERA`).
- Pairing: Android 16 / Pixel's programmatic `createBond()` is broken for
  LESC; the app never calls it. User must pair once via system Settings.
- LDI trust: `EBikeLink` advertises a connectable solicitation, so ANY
  passing BLE central can connect. Never treat an inbound connection as the
  bike on the raw connect - persist the bonded address and read state only
  after real LDI data arrives (the `Paired` transition), and conclude
  "firmware too old" only for a `BOND_BONDED` device that completes discovery
  without the LDI service. The decision is the pure `classifyMissingLdi`.
- The `<queries>` entry for `com.bosch.ebike.onebikeapp` is load-bearing:
  without it `getLaunchIntentForPackage` returns null on Android 11+ and
  "Open Flow" silently falls back to the Play Store.
- To test Onboarding without destroying your production install's pairing
  state, build the `onbtest` buildType (`gradle :app:assembleOnbtest`). It
  installs side-by-side under `es.jjrh.bikeradar.onbtest` with its own
  SharedPreferences and zeroed HA seed. Uninstall when done:
  `adb uninstall es.jjrh.bikeradar.onbtest`.
- Live-testing via ADB: `am stopservice .../BikeRadarService` BEFORE
  `adb install -r` lets `onDestroy` clear the radar GATT cleanly. Post-
  install, `am force-stop` + `monkey ... LAUNCHER 1` for a clean relaunch.
  If Bluedroid stays stuck, `svc bluetooth disable && svc bluetooth enable`
  resets it. Wait for `BikeRadar.Radar: handshake complete` + `first V2
  frame` in logcat before declaring the app ready to test.
- AlertDecider's stationary safety override has TWO disjunct gates: the
  proximity gate (`distance <= alertMaxM/3 AND closing >= 6 m/s`) and a
  TTC gate (`TTC <= 2s AND closing >= 6 m/s AND distance <= alertMaxM`).
  Boundary tests in `AlertDeciderTest.kt` pin the semantics. Don't reduce
  the override to a single gate without re-running the capture replay.
- `AlertBeeper` is service-scoped (allocated in `BikeRadarService.onCreate`,
  released in `onDestroy`). The first beep after every BLE reconnect lands
  on the same warm AudioTrack pool; do not allocate per-overlayJob.
- `AlertBeeper` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per cue with
  a re-arming abandon timer. The walk-away alarm path uses the stronger
  `_EXCLUSIVE` flavour and is separate from the close-pass path.
- When `audioManager.mode == MODE_IN_CALL` the close-pass beeper skips the
  audio path entirely (visual overlay still fires). Non-negotiable, no
  Settings toggle.
- `ACCESS_COARSE_LOCATION` is requested but not prompted in-app yet;
  existing installs upgrading from pre-v0.7.1-alpha must grant via Android
  Settings or the auto-mode silently falls back to London.

## Audio design

The alert-audio model - close-pass tier beeps, the urgent impact cue, the
all-clear chime, the radar critical-battery cue, and the inactivation states
(audio-focus ducking + `MODE_IN_CALL` suppression) - is an informal
implementation of the IEC 60601-1-8 alarm-system pattern: distinct alarm
*classes* (by timbre, not fine pitch), alarm parsimony, and "paused with
new-condition override". Design inspiration only; the app is not a medical
device and makes no compliance claim. The authoritative description of each
cue and its rationale lives in the `AlertBeeper.kt` / `AlertDecider.kt` KDoc,
which is kept current with the code - this note is the conceptual frame, not
a behaviour spec to keep in sync.

## Quality gates (pre-push, mandatory)

- `/qc` skill spawns a panel of read-only reviewers (legal,
  commit-message, diff hygiene; UX if UI changed; release-scope if
  version bumped) and writes `.git/qc-marker` for HEAD on clean PASS.
  The pre-push hook refuses to push without a valid marker.
- `/release-review` skill reviews the `v*` tag's CHANGELOG section for
  reader-perspective, leakage, truthfulness, migration-impact; writes
  `.git/release-review-marker` on PASS.
- Any amend, reset, or new commit invalidates both markers - re-run
  before re-pushing.

## Contributing

- GPL-3.0-or-later. Don't copy non-GPL-compatible code.
- Protocol corrections go to the `bike-radar-docs` repo, not this one.
- Decoder behaviour changes must add or update unit tests.
- Commit subjects use the conventional-commits prefixes already
  visible in `git log`: `feat:`, `fix:`, `ui:`, `test:`, `build:`,
  `ci:`, `docs(...):`, plus area-scoped ones like `ble:`, `ha:`,
  `protocol:`, `service:`, `release:`. Optional scope like
  `ui(onboarding):` or `feat(alerts):`.

## Local-only notes (not in this repo)

Working drafts and research live in gitignored directories
(`notes/`, `do-not-commit/`). Keep any local index under
`do-not-commit/INDEX.md` — the directory is gitignored so nothing
there reaches public history.

The sibling docs repo `../bike-radar-docs/` (public) is the canonical
protocol spec.
