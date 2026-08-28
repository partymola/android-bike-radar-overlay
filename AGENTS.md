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
scripts/dev gradle :app:verifyRoborazziDebug --console=plain
scripts/dev down                                            # when finished

# If `docker run` fails before Gradle starts, the docker bridge cannot create
# veth pairs on this host: export DEV_DOCKER_NETWORK=host.

# Or the one-shot pattern (no daemon, slower; safe to use without `dev up`):
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -w /workspace bike-radar-builder \
  ./gradlew :app:testDebugUnitTest --console=plain --no-daemon

# `scripts/dev gradle ...` auto-falls back to the one-shot pattern when
# the persistent container is not up, so it is safe to use either way.

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Every build runs the wrapper, and `gradle/wrapper/gradle-wrapper.properties`
is the only place a Gradle version is written down.** The container ships a
JDK and no Gradle, and no workflow passes `gradle-version:` to
`gradle/actions/*` - absent it, those actions use the wrapper. That is
deliberate: the version used to be repeated in the image tag and seven
workflow pins, a dependency bump moved the wrapper alone twice, and both times
a from-source build (the F-Droid path) would have compiled with a Gradle no
gate had run. Do not reintroduce a pin to "be explicit" - a second place to
write the version is the whole defect, and nothing checks the two agree.

One consequence worth knowing: the distribution is no longer baked into the
image, so the first `./gradlew` against a cold `~/.cache/bike-radar-gradle`
downloads it and needs network.

Screenshot tests: `:app:verifyRoborazziDebug` renders the Compose and
Canvas goldens via Robolectric Native Graphics, so they run inside
`testDebugUnitTest` and in CI - no device, emulator, or layoutlib.
Regenerate goldens with `:app:recordRoborazziDebug` and commit the PNGs
under `app/src/test/snapshots/images/`.

Releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`,
add a top-level entry to `CHANGELOG.md` (group changes under the
headings already in use - Breaking, Features, Fix, Security, UX,
Compatibility, Reliability, Stability, Power, Diagnostics, Internal -
matching the tone of existing entries). `Breaking` goes first in a
section and says what the rider has to change by hand. The section
covers everything since the last tag, not just what is unpushed - read
the range as `v<last>..HEAD`. Write each bullet on a SINGLE line, no
hard wrapping: the release workflow copies the section verbatim into the
GitHub release body, which renders every newline as a line break, so a
wrapped bullet shows mid-sentence breaks on the Releases page. Also add a
short per-version changelog at
`fastlane/metadata/android/{en-US,es-ES}/changelogs/<versionCode>.txt` (the
F-Droid / store "What's New"; keyed by `versionCode`, not the name) - a tight
benefit-framed summary, not the full CHANGELOG section. Then push
a `v*` tag (e.g.
`v0.7.1-alpha`). The tag triggers `.github/workflows/release-apk.yml`,
which builds a release-signed APK and publishes a GitHub pre-release.
The workflow defaults `prerelease: true` until the app exits alpha.

**The store and README screenshots are Roborazzi goldens, copied.** Every
PORTRAIT image under `screenshots/` and `fastlane/.../phoneScreenshots/` is a
byte copy of a golden from `app/src/test/snapshots/images/` at 1344x2991, which
is why they carry the fixture host `homeassistant.local:8123`, a masked token
and no device names. The landscape ones are genuine device captures of the ride
overlay, which no golden reproduces.

**Re-copy rather than re-capture.** A device capture is 1344x2992, one pixel
taller, and would carry the rider's real Home Assistant host and device names
into a public artefact.

`scripts/check-screenshot-freshness.py` reports any portrait image that is no
longer a copy of a current golden. It runs in `ci.yml` as a **non-blocking**
step: the goldens re-record on any UI change, so failing the build would turn
every UI pull request red until the copies were refreshed in the same commit,
and a check that fires on routine work gets bypassed. Read the log rather than
the exit status.

Two things it cannot do, both worth knowing before reading a pass as coverage.
It cannot tell whether a slot holds the RIGHT golden, only that it holds one -
the README alt text is the only statement of which screen belongs where. And
it skips the landscape images entirely, which means the only device-captured
images in the repo are precisely the ones it never inspects; those are the
class that could carry a real host or real device names, so check them by eye.

Why it exists: nothing else can see inside a PNG, and these had drifted far
enough to advertise a credential-encryption layer the app does not have, an
entity list it no longer renders, and a version from the alpha series.

**Build-dir permission gotcha:** if `:app:testDebugUnitTest` fails with
`Unable to delete directory .../test-results/...`, a previous container left
root-owned files. Clean with:

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace bike-radar-builder \
  rm -rf /workspace/app/build
```

## Architecture

For a narrative map of the whole system (service shell, coordinators, state
buses, the overlay/alert pipeline, the pure decider core, and the BLE lifecycle),
see [`ARCHITECTURE.md`](ARCHITECTURE.md). The notes below are the working
summary; the Key files table maps each part to its file.

- Single foreground service (`BikeRadarService`), Compose-only UI, no
  fragments. The two BLE links now live in their own coordinators -
  `RadarLinkController` (rear radar) and `CameraLightLinkController` (front
  camera/light); the one-shot battery reads live in `BatteryReader`. What
  remains in the service is the coordinator hub - the BLE-scan dispatch
  (`scheduleRead` routes a sighting to the radar link / camera link / battery
  read) and the foreground-service lifecycle. Behaviour is split across
  single-responsibility coordinators injected at `onCreate` (overlay pipeline,
  radar link, camera-light link, radar-link/walk-away state machine, battery
  reader, HA publishing, notifications, capture log, known-device cache - see
  Key files). The service stays the sole owner of `scope` and the warm
  `AlertBeeper`; `RadarLinkCoordinator` owns the radar-link/walk-away state
  (`_radarLinkState`) and the transitions that drive the dismount alarm + the
  dropped-radar cue. The radar controller reaches the state through a
  `RadarLinkStateGateway`, and the camera controller reads the radar off-time
  through an injected lambda for its shared backoff cap.
- The app connects to two BLE device classes: the rear radar and the front
  camera/light. Each has its own AMV unlock UUID pair (see Gotchas).
- Radar selection is name-match by default; a rider with more than one radar
  bonded can pin this bike's (`Prefs.radarMac`), and a pinned-and-still-bonded
  MAC overrides the name-match in `scheduleRead` so the app never streams from
  the wrong rear unit. Pure decider in `RadarSelection.shouldLinkRadar`;
  managed in Settings -> Connections -> Radar.
- HA integration is optional; the overlay works standalone.
- Front-light mode is auto-set on every BLE connect: Day Flash before
  sunset, Night Flash after, using `SunsetCalculator` driven by
  `RideLocationResolver`: rider-entered manual coordinates if set, else
  `LocationCache`'s one `getLastKnownLocation` read per ride via
  `ACCESS_COARSE_LOCATION`, else a London fallback. A one-shot dawn/dusk
  flip is scheduled for
  the rest of the session. Skipped when `cameraLightUserOverride` is
  set (manual side-button press during the session). See
  `BikeRadarService.kt` connect path.
- Capture log is opt-in (off by default; `Prefs.captureLoggingEnabled`, toggled
  on the Debug screen). When enabled it is written to
  `/sdcard/Android/data/es.jjrh.bikeradar/files/captures/bike-radar-capture-<stamp>.log`
  (in the `captures/` subdir so the FileProvider share subtree is scoped to the
  logs, not the whole external-files root). Cap is `MAX_CAPTURE_LOGS = 50`.
  When the toggle is off, `openCaptureLog` no-ops and no file is created.
  `clog` lines mirror to logcat only in debug builds (`BuildConfig.DEBUG`);
  release builds keep BLE/movement payloads out of logcat.
  A fresh capture file is opened per radar connection (after handshake) and
  closed on disconnect, so a mid-ride radar drop splits one ride across
  multiple files and the dead-radar window between them is uncaptured.
  Every file's header carries a build stamp (`# app version=... code=...
  build=...`), plus `commit=` on non-release builds only - so don't infer a
  build from the APK's install time. It also carries
  `# clock unix_ms=.. mono_ms=..`, both clocks read at one instant: packet
  lines are prefixed with wall-clock ms while sensor series such as
  `# turn yaw ts_mono=` are elapsedRealtime, and this converts between them
  by subtraction rather than by correlation. The offset holds while the wall
  clock is not stepped - an NTP correction mid-ride moves the packet stamps
  and not the anchor, and nothing in the file records it.
  **A release-variant capture is NOT
  attributable to a tree**: two release APKs built from different code stamp
  identically. Why, and the `commit=unknown` fallback: `BuildStamp` KDoc.

## Key files

| Path | Role |
|------|------|
| `app/src/main/java/es/jjrh/bikeradar/BikeRadarService.kt` | Foreground-service shell + sighting dispatch + battery reads; coordinators injected at onCreate |
| `app/src/main/java/es/jjrh/bikeradar/RadarLinkCoordinator.kt` | Owns `_radarLinkState` + the walk-away/radar-drop transitions (markConnected/markDisconnected/tick/evaluate*); the `RadarLinkStateGateway` impl |
| `app/src/main/java/es/jjrh/bikeradar/RadarLinkController.kt` | Rear-radar BLE link: bond watch, reconnect loop, AMV handshake, decode->RadarStateBus, radar tail-light auto-mode (reaches the link state via `RadarLinkStateGateway`) |
| `app/src/main/java/es/jjrh/bikeradar/CameraLightLinkController.kt` | Front camera/light BLE link: reconnect loop, AMV (FRONT_CAMERA) handshake, mode-state loop, time-of-day light auto-mode (optional accessory; reads the radar off-time via an injected lambda) |
| `app/src/main/java/es/jjrh/bikeradar/BatteryReader.kt` | One-shot GATT battery reads (0x2A19) for radar/dashcam -> BatteryStateBus + HA; the in-flight cooldown. `scheduleRead` (in the service) owns the throttle and calls it |
| `app/src/main/java/es/jjrh/bikeradar/CaptureLogManager.kt` | Per-ride capture-log lifecycle (open/close/gzip/prune); opt-in |
| `app/src/main/java/es/jjrh/bikeradar/BuildStamp.kt` | Pure formatter for the capture header's build-provenance line, plus the BuildConfig binding; release builds carry no commit |
| `app/src/main/java/es/jjrh/bikeradar/RideSummaryNotificationDecider.kt` | Pure decider for the post-ride summary notification (ride end = sustained radar-off; new-ride stats reset on long-gap reconnect) |
| `app/src/main/java/es/jjrh/bikeradar/CrashLogger.kt` | Process-wide uncaught-exception recorder (reports to `crashes/`, capture-log emergency flush hook); surfaced on the Debug screen with the unclean-restart counter |
| `app/src/main/java/es/jjrh/bikeradar/BluetoothStateMonitor.kt` | Adapter on/off watch: tears the links down when Bluetooth dies mid-ride, re-registers the scan + kickstarts them when it returns |
| `app/src/main/java/es/jjrh/bikeradar/RideCheckpoint.kt` | Crash-safe single-slot ride checkpoint (pure write-gate decider + store); flushed into ride history at the next start after a process death |
| `app/src/main/java/es/jjrh/bikeradar/TurnSensorController.kt` | Gyroscope yaw-rate feed for `TurnStateDecider` (gravity-projected, mount-orientation independent); drives the turn-aware alert hold and writes the `# turn yaw` capture trace |
| `app/src/main/java/es/jjrh/bikeradar/HaPublisher.kt` | HA MQTT publishing (battery, ride-edge, ride-summary); rebuilds HaClient per call |
| `app/src/main/java/es/jjrh/bikeradar/ServiceNotifications.kt` | Notification channels + the persistent foreground notification |
| `app/src/main/java/es/jjrh/bikeradar/KnownDevices.kt` | name<->MAC SharedPreferences cache, shared by the HA + battery paths |
| `app/src/main/java/es/jjrh/bikeradar/HaStatusDeriver.kt` | Pure four-state Home Assistant status; every HA surface reads it rather than re-deriving one |
| `app/src/main/java/es/jjrh/bikeradar/PermissionsSummaryDeriver.kt` | Pure permissions-row summary (all-granted / partial / action-needed) |
| `app/src/main/java/es/jjrh/bikeradar/BatteryChipLevel.kt` | Pure battery derivations: `batteryIsLow` (shared by the chip and the overlay marker), the chip's colour band, and `lowBatterySlugs` |
| `app/src/main/java/es/jjrh/bikeradar/RadarV2Decoder.kt` | V2 target-struct decoder (stateful) |
| `app/src/main/java/es/jjrh/bikeradar/RadarUnlock.kt` | AMV 04 handshake; `DeviceVariant` selects rear-radar or front-camera UUID pair |
| `app/src/main/java/es/jjrh/bikeradar/RadarOverlayView.kt` | Canvas overlay |
| `app/src/main/java/es/jjrh/bikeradar/CameraLightController.kt` | Front camera/light mode-set writes and notify parser |
| `app/src/main/java/es/jjrh/bikeradar/LocationCache.kt` | One-fetch-per-ride GPS cache for SunsetCalculator |
| `app/src/main/java/es/jjrh/bikeradar/RideLocationResolver.kt` | Pure location resolver for the light auto-modes (manual coordinates -> GPS -> London) + the coordinate input sanitize/parse/validate/format helpers |
| `app/src/main/java/es/jjrh/bikeradar/ScanGate.kt` | Pure accept/reject gate for an active BLE scan result (name-match AND bonded), used by the service's device discovery |
| `app/src/main/java/es/jjrh/bikeradar/EBikeStatusReader.kt` | Read-only GATT client subscribing to Bosch Flow's proprietary status stream |
| `app/src/main/java/es/jjrh/bikeradar/EBikeSnapshotCoordinator.kt` | Owns the eBike snapshot cache + derived state (odometer baseline, ride-edge + climb detection); fed by the status reader's callback |
| `app/src/main/java/es/jjrh/bikeradar/EBikeStatusDecoder.kt` | TLV decoder for the proprietary status stream (add new object IDs here) |
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
- MQTT topics and HA entity IDs are namespaced under `HaClient.NS`
  (`bikeradar`). It was a vendor name until the same namespace also carried
  the front camera and the ride statistics. Renaming it again breaks every
  rider's automations, so if it ever changes, add the old value to
  `cleanupStaleDiscoveryTopics` to retire the entities it created.
- `BikeRadarService.slug()` strips `varia_` from a device's ADVERTISED name
  and is unrelated to that namespace. Leave it alone.

## Writing copy (UI strings)

User-facing text lives in `res/values/strings.xml` (en) + `values-es/`. When
adding or editing it, follow these principles - the `/qc` copy reviewer
enforces them, and CONTRIBUTING.md points contributors here:

- **Benefit, not mechanism.** Say what the rider gets, not how it works. "Set
  your lights by local sunset" beats "compute sunrise/sunset for the auto-mode
  state machine". Internals (MQTT discovery, BLE stack, GCM, file paths) are
  noise on most screens.
- **Short and scannable.** A phone screen is small and read mid-task. Prefer one
  line; use `\n• ` bullets for any list of three or more items rather than a
  dense paragraph (see the Privacy permissions/publish strings).
- **No jargon, acronyms, or filler nouns** the rider can't parse: drop
  "companion app", "telemetry", "bearer token", "phone home". Established
  product terms stay (Bluetooth, Home Assistant, Bosch Flow, MQTT, eBike).
- **es: Spain register** (tú), no LatAm vocab, and gender must match the
  on-screen referent: a shared string under both "Radar" (m) and "Cámara" (f)
  needs splitting (e.g. `_radar_not_seen` / `_cam_not_seen`).
- **es runs long - keep it tight.** Spanish averages ~15-30% longer than
  English, but the SHORT strings in the tightest spots expand worst - single
  labels and chips can grow 100-300% ("Dashcam" 7 chars -> "Cámara delantera"
  16). The layout-sensitive surfaces are labels, button text, screen/section
  titles, chips, and notification titles; size them to the longest es form,
  never the en width. Concrete levers (Spain UI convention, tú register):
  - **Infinitive for action labels** (buttons, menu items, chips): "Configurar",
    "Cancelar", "Seleccionar todo". **Imperative tú for prompts** that tell the
    rider to act: "Configúrala", "Elige", "Pulsa Aceptar". Both beat
    "Configurar la cámara delantera" - drop the object the screen already shows.
  - **Omit articles/possessives where Spanish allows** - "Crear carpeta" not
    "Crear una carpeta", "Modo de luz" not "Modo de la luz", "del eBike" not
    "de tu eBike". Don't stack "de la ... delantera" ("Luz de cámara").
  - **Drop a qualifier the screen already supplies** - "Cámara" not "Cámara
    delantera" on a chip / glyph legend / switch-row (the app has one camera).
  - **No gerund for titles/labels** - translate -ing as an infinitive or noun
    ("Configurar", "Búsqueda"); reserve the gerund for genuine progress
    ("Buscando…", "Imprimiendo…").
  - **Nominal style in short status strings** - drop ser/estar: "Disco lleno",
    "Cámara no disponible", not "El disco está lleno".
  - **Symbols, not abbreviations, for units** (no period, no plural, space):
    "30 s", "2 min", "10 km". **The percent sign is the exception and takes
    no space**: "12%", not "12 %". This overrides the RAE norm deliberately
    and `values-es` is consistent with it throughout, so a lone "12 %" is a
    regression rather than a correction. Ordinary abbreviations keep the dot
    and accent ("máx.", "mín.", "núm."). Reuse the Android-es words riders
    know ("Ajustes", "No molestar").
  - **Digits for numbers, even below 10**: "1 aviso", "3 coches".
  - **Sentence case** - capitalize only the first word ("Seguir mi luz", not
    "Seguir Mi Luz").
  A term that fits the en layout can overflow es - verify against the es
  Roborazzi golden (or on-device) for clipping/wrapping before committing.
- **The Privacy screen is the deliberate exception.** It is the "verify by
  reading the code" disclosure; it keeps full substance (and the literal tokens
  `scripts/privacy-disclosure-check.sh` pins: permission names, the backup
  disclosure + manifest/backup-rules pairing, HTTPS, the DataDisclosure
  keywords). Trim it to bullets, never gut it.
- **Review with screen context, not a flat string list.** Verbosity and
  gender-in-context bugs only show on the screen: use the English Roborazzi
  goldens or map each string to its Composable referent before judging it.

## Testing

- Tests hard-code literal expected values. Never assert a production constant
  against itself (`assertEquals(SOME_CONSTANT, actual)` stays green when the
  constant is wrong - a *tautological test*; see "DAMP vs DRY" and "don't share
  constants between test and production"). Mutation testing is the detector: if
  unsure a test pins a value, mutate the constant to a degenerate value and
  confirm a test goes red.
- All decoder logic is pure JVM; test with `:app:testDebugUnitTest`
  (Robolectric). CI runs this alongside `:app:lintDebug`,
  `:app:ktlintCheck`, `:app:verifyRoborazziDebug`, and
  `:app:jacocoCoverageVerification` (see Static analysis & coverage below).
- Roborazzi screenshot tests render via Robolectric Native Graphics and run
  as part of `testDebugUnitTest`. `:app:verifyRoborazziDebug` compares
  against the golden PNGs; this gate runs in CI and before any push that
  touches `app/src/main/**`.
- To regenerate goldens: `:app:recordRoborazziDebug`. Commit the updated
  PNGs under `app/src/test/snapshots/images/`.
- Writing a new screenshot test: Compose screens use
  `captureRoboImage { MyComposable() }` (lambda form, no compose rule). A
  detached custom `View` can't use `View.captureRoboImage()` - it needs an
  Activity and fails with "View should have Activity"; instead measure + lay
  out the view, draw it to a `Bitmap`, and capture that (see
  `RadarOverlayViewTest`).
- **Corpus-replay gate** (`CorpusReplayGate`): replays a private ride-capture
  corpus through the real decoder + decider and compares per-capture alert
  tallies against a baseline stored alongside the corpus. Run before pushing
  any alert-behaviour change:
  `scripts/dev gradle :app:testDebugUnitTest --tests es.jjrh.bikeradar.CorpusReplayGate
  -Pbikeradar.corpusDir=/workspace/<your capture directory>`. Without the
  property the test assume-skips (CI and corpus-less checkouts are
  unaffected). On an intentional change, re-record with
  `-Pbikeradar.corpusRecord=true` and cite the failure diff as the
  before/after evidence in review. Add `--no-configuration-cache` to corpus
  runs: the property is captured into the configuration cache, so a cached
  entry can leak a previous run's corpus path into an invocation that omitted
  the flag.
  - **The path must be the one Gradle sees, not the one your shell sees.**
    Gradle runs inside the build container with the repo mounted at
    `/workspace`, so a host path resolves to nothing there. A missing
    directory is indistinguishable from an absent property: the test
    assume-skips and the build reports SUCCESS, so a corpus run that checked
    nothing looks exactly like one that passed.
  - **Confirm it ran rather than trusting the exit code.** Check
    `skipped="0"` in
    `app/build/test-results/testDebugUnitTest/TEST-es.jjrh.bikeradar.CorpusReplayGate.xml`,
    or compare the case time: a real replay takes seconds, a skip takes
    milliseconds.
  - A capture with no baseline entry is not compared, so dropping new rides
    into the corpus does not extend coverage until the baseline is
    re-recorded. Count the baseline entries against the corpus before reading
    a pass as "the new rides are clean".
- **Cue-ledger gate** (`CueLedgerReplayTest`): the in-repo, CI-run companion
  to the corpus gate. Replays the committed `replay-fixture.txt` through the
  real decoder -> decider -> cue and asserts the *ordered* cue ledger against
  a baked golden, so a change to the BEEP and CLEAR paths surfaces as a
  reviewable diff with no private corpus. Regenerate after an intentional
  change with `-Pbikeradar.cueLedgerRecord=true` and paste the printed ledger
  into the test's `DEFAULT_GOLDEN`; cite the diff in review.
  - **It reaches no urgent cue, so the whole imminent-impact and
    pass-prediction path is invisible to CI.** The fixture is 30 s of moving
    traffic with no stopped-rider-plus-fast-closer encounter, and the test
    pins that absence deliberately in
    `urgentLowSpeedToggleIsNoOpForThisFixture` and
    `passClearanceIsNoOpForThisFixture` - both assert the ledger is unchanged
    with the feature toggled, which is a statement about the fixture, not
    about the feature. Only the private `CorpusReplayGate` corpus can see a
    change there. Extending `replay-fixture.txt` with a real stationary
    off-axis window is what would close it; until then, never read a green CI
    as cover for an urgent-path change.
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
    project floors LINE >= 0.80, INSTRUCTION >= 0.78, BRANCH >= 0.68 on the
    whole testable layer, plus a tighter BRANCH >= 0.93 on every `*Decider` /
    `*Deriver` (matched by wildcard) plus `RadarV2Decoder`. Raise the floors in
    `app/build.gradle.kts` as coverage grows.
  - **Diff-coverage gate** (`scripts/diff-coverage-gate.py`, runs in CI and
    as a mandatory pre-push `/qc` gate - never leave it to CI alone): the
    changed executable production lines in a PR (or a push) must be >= 85%
    covered. The project ratchet above can't see a 200-line untested feature
    while the average holds; this gate does. It wraps `diff-cover` over
    `jacocoDiffReport`, which keeps Compose UI in scope - per-diff there is
    nothing to dilute, so a new inline `when` over app state in a Composable
    body is gated rather than exempt. That report depends on
    `verifyRoborazziDebug`, not `testDebugUnitTest`: Roborazzi only composes
    when its task property is set, so a bare unit-test run overwrites the
    exec data with one where no golden rendered and every
    snapshot-only Composable reads as uncovered. Diffs under 10 executable changed
    lines are exempt (one untested line shouldn't fail CI), and an
    unreachable base ref skips rather than fails. It fires on PRs and direct
    pushes to `main` alike; a contributor PR is the case it most guards.
- **Release DEX keep gate** (`scripts/check-release-dex-keeps.py`, run by
  `:app:verifyReleaseDexKeeps`): the only check that reads the artifact riders
  install. Every gate above runs the debug variant, which R8 never touches, so
  nothing else covers the minified APK. It unzips `classes*.dex`, runs
  `dexdump -f`, and fails if any enum constant in its table is absent under its
  exact name. The `release-shrink` CI job runs it on every push to `main` and
  every PR targeting `main`, so it can go red BEFORE a tag exists rather than
  stranding a public tag. Tag pushes do not trigger `ci.yml`; `release-apk.yml`
  names the task too, so they are covered there.
  - **Deliberately not wired to `assembleRelease`.** A from-source build by a
    packager - which is what the pending F-Droid submission would do - must not
    need `python3` and a matching build-tools `dexdump` just to produce the
    APK, and a finalizer would make both hard requirements of it. `ci.yml` and
    `release-apk.yml` name the task explicitly instead, so the requirement
    stays ours. It does `dependsOn("packageRelease")`, without which Gradle
    rejects the task graph for an implicit dependency on the APK directory.
  - **What that costs, stated rather than assumed:** any APK built outside
    those two workflows is not itself gate-checked - a packager's from-source
    build, or one built by hand and uploaded. Such a build carries the property
    only if it is byte-identical to one that was checked, which is what a
    reproducible-build verification would establish. Publish through the
    workflow.
  - **What the gate does NOT replace.** It reads names out of the DEX; it never
    executes the APK. `boot-smoke` installs the DEBUG APK, which R8 never
    processes, so nothing in CI boots the shrunk artifact. Nothing requires a
    ride test of a minified build before a tag either, and this gate does not
    create one: it is narrower than the requirement it replaced and covers a
    different failure. That is a real reduction in assurance, not an even
    trade. Do not read a green `verifyReleaseDexKeeps` as evidence the release
    runs.
  - **The table is the set whose NAME crosses a process boundary**, and that
    is the whole scope: six enums persisted by name and read back with
    `valueOf()`, plus `VehicleSize`, `ClosePassDetector.Side` and
    `ClosePassDetector.Severity`, published by name into Home Assistant
    payloads and the close-pass event JSON. Renaming one silently resets a
    saved setting or stops a rider's automation firing, with no compile error
    and no failing test. String literals (`org.json` field names, the prefs
    key constants) are deliberately NOT covered: no R8 configuration rewrites
    them, so checking them would add assertions that cannot fail. Same for
    manifest components, kept by the AAPT rules whatever
    `proguard-rules.pro` says.
  - **Write the expected table by hand from the enum declarations.** Deriving
    it from a DEX, `usage.txt` or `seeds.txt` makes it agree with the artifact
    it checks by construction.
  - **Nothing forces a NEW name-crossing enum into the table.** The check is
    only that the table is a subset of what shipped, so a seventh
    `CameraLightMode` constant, or a new enum persisted by name, ships ungated
    and silent. Add it by hand when you add the enum. Deriving the table from
    the Kotlin SOURCE would close this and is not the same mistake as deriving
    it from the artifact under test.
  - Read a failure as the R8 config change needing a keep rule, not the gate
    needing an edit.
  - **Both failure branches are pinned against real R8 output, not just the
    parser.** Removing `-dontobfuscate` renames the classes, so all nine
    descriptors leave the DEX at once (the whole-class branch). Removing
    `-dontoptimize` leaves the classes in place but takes the static fields for
    `CameraLightMode.HIGH/MEDIUM/NIGHT_FLASH/OFF` and
    `RadarLightMode.SOLID/PELOTON/OFF` out of the shipped DEX (the constant
    branch). That is the measurement; which R8 pass does it, and whether
    `valueOf` would still resolve those names from `$VALUES`, is not
    established - read `usage.txt` from a mutation build if you need to know.
    The gate pins the contract, not a reproduction of rider-visible harm.
    Re-run either mutation to re-confirm it.
  - `--self-test` covers the parser separately, and the Gradle task runs it
    BEFORE the APK check. A regression in section tracking returns a superset
    of the static fields, which would make the real check pass unconditionally.
  - **Do not add `testReleaseUnitTest` and call the release covered** - it
    runs against release-variant classes *before* R8, so it reports green on
    exactly the risk it appears to address.
  - The gate pins enum constant NAMES, not their order. `CameraLightMode`'s BLE
    wire value is `ordinal + 1`, so reordering its constants breaks the device
    protocol and passes this gate green. Different hazard, different guard: R8
    does not reorder, a maintainer does.
- **Transitive licence check** (`scripts/check-transitive-licences.py`, fed by
  `:app:writeReleaseRuntimeCoordinates`): reports the licence of every artifact
  on the release runtime classpath, resolved from each artifact's own POM.
  **Findings are report-only in `ci.yml`** - read the log, not the exit status.
  `--strict` makes findings fail; promote once it has been quiet for a while.
  - **It is hardening, not a compliance fix.** Measured Aug 2026: no artifact
    on the release runtime classpath distributes a `NOTICE` file, so Apache 2.0
    s.4(d) has nothing to carry forward, and the APK embeds the full Apache 2.0
    text for six AndroidX artifacts. Note the trigger for s.4(d) is whether the
    upstream **Work** ships a NOTICE, not whether our APK does - never argue it
    from our own APK's contents, which would make stripping NOTICE files read
    as a defence. Nothing re-checks this: the script reads POM `<licenses>`,
    not NOTICE files, so a future bump can invalidate it silently. This watches
    for a bump introducing an incompatible licence unnoticed - otherwise
    invisible, because a bump is not read as a licensing change.
  - **Two cheaper-looking routes were measured and are dead**, so do not
    rebuild either. The Gradle module cache holds no `.pom` for most of what
    ships (core-ktx, material3, navigation-compose have none), so a cache
    reader silently skips the majority and reports clean. GitHub's
    dependency-graph SBOM enumerates the shipped set correctly but resolves a
    licence for 13 of 149 artifacts and **none of the 123 AndroidX ones**.
  - **The upstream POMs do carry it**, which is why a direct fetch works where
    those fail - and why this needs no Gradle resolution API and therefore **no
    configuration-cache exemption**, which was the cost that made this a
    decision rather than a chore.
  - Findings are report-only for a different reason than the screenshot check
    above: this one reaches the network, and a gate that reds on a CDN blip
    gets bypassed.
  - **The allow-list is exact spellings, not a regex on "apache".** A substring
    match would absorb "Apache License 2.0 with Commons Clause", which is not a
    free licence. An unfamiliar spelling should reach a human.
  - **Anti-vacuity is the one thing that is always fatal**, findings-report-only
    or not: exit 2 means the check examined nothing, and the workflow step
    swallows every other code but re-raises that one. It fires on an unreadable
    coordinate list, a list that does not look like this app's classpath, and a
    run where no coordinate resolved at all. **`continue-on-error` on the step
    would defeat all of it**, because it discards exit 2 exactly as it discards
    exit 1, so the step swallows findings explicitly in its own script instead.
    Do not "simplify" that back.
  - `--self-test` pins that the classifier can REJECT, that a vacuous run
    aborts, and that an all-unrecognised or all-undeclared run does not. That
    last property is the one the abort exists to avoid having, and it is pinned
    against real bucket shapes through `resolved_count`, because the defect it
    guards was never in the predicate but in which quantity the caller fed it.
    CI runs the self-test before the real check. Proven against real data too:
    dropping one Apache spelling from the allow-list flips almost every
    artifact to `unrecognised`.
  - Current state: every artifact on the classpath resolves to Apache-2.0, in
    four spellings. The count is not written here - the check prints it.
  - `coreLibraryDesugaring`'s payload is GPL-2.0-with-Classpath-Exception and
    is a real legal question, deliberately not pre-vetted. It is not enabled
    (`minSdk 31`), and `SettingsLicencesCoverageTest` already watches for the
    declaration appearing.
- **detekt** is intentionally not wired: no stable release targets the
  pinned Kotlin 2.4 yet (only alpha builds do), and an alpha doesn't belong
  in a public build. Revisit when a stable detekt supports the toolchain.
- **CodeQL** runs from `.github/workflows/codeql.yml` on pushes to `main` and
  weekly, over three languages: `actions` and `python` buildlessly, and
  `java-kotlin` from a real `:app:assembleDebug`. The build is not optional
  there - CodeQL extracts Kotlin only from an actual compile, so the buildless
  mode skips every `.kt` file and reports a green scan of nothing. The build
  step disables the Gradle *and* Kotlin compiler daemons for the same reason:
  a compile outside the traced process tree extracts nothing. It also passes
  `--no-build-cache`, because a cache entry restored from an earlier run
  satisfies `compileDebugKotlin` without running the compiler at all, and a
  grep over the build log fails the step unless that task appears as
  executed rather than FROM-CACHE or UP-TO-DATE. Advanced setup
  cannot coexist with GitHub's default setup, which is why one workflow covers
  all three languages rather than only the one that needs a build.

## Gotchas

- After `adb install -r` the radar GATT may be left half-open;
  `runRadarConnection`'s ABORT path closes and reconnects automatically
  (~1.5 s). If the reconnect doesn't happen, see live-testing recovery
  below.
- Never subscribe the CCCD of `6a4e3203` (V1 radar char). Written before the
  unlock (fw 6.70), the radar unlocks into V1: handshake succeeds, V1 heartbeats
  arrive on `6a4e3203`, `6a4e3204` never emits - and later connections that never
  touch the CCCD get no V2 either, until the radar is power-cycled. See
  `Uuids.RADAR_V1`.
- AMV UUID pairs differ by device class: the rear radar uses RX=`6a4e2811`/
  TX=`6a4e2821`; the front camera/light uses RX=`6a4e2810`/TX=`6a4e2820`.
  Mixing the pairs causes silent handshake failure — the device accepts the
  writes but never responds correctly. `RadarUnlock.DeviceVariant` selects
  the right pair (`RADAR` or `FRONT_CAMERA`).
- Pairing: Android 16 / Pixel's programmatic `createBond()` is broken for
  LESC; the app never calls it. User must pair once via system Settings.
- eBike data is READ-ONLY: `EBikeStatusReader` is a GATT client that connects
  out to the bonded eBike and subscribes to the proprietary status-notify char
  Bosch Flow already streams (it fans out to every subscriber). It works only
  while Flow holds the link, and never writes the bike's command channel.
  `findBondedEBikeMac` picks the eBike from the bonded-device list by name.
- The `<queries>` entry for `com.bosch.ebike.onebikeapp` is load-bearing:
  without it `getLaunchIntentForPackage` returns null on Android 11+ and
  "Open Flow" silently falls back to the Play Store.
- To test Onboarding without destroying your production install's pairing
  state, build the `onbtest` buildType
  (`scripts/dev gradle :app:assembleOnbtest`). It installs side-by-side under
  `es.jjrh.bikeradar.onbtest` with its own SharedPreferences and zeroed HA
  seed. Uninstall when done: `adb uninstall es.jjrh.bikeradar.onbtest`.
- Live-testing via ADB: `am stopservice .../BikeRadarService` BEFORE
  `adb install -r` lets `onDestroy` clear the radar GATT cleanly. Post-
  install, `am force-stop` + `monkey ... LAUNCHER 1` for a clean relaunch.
  If Bluedroid stays stuck, `svc bluetooth disable && svc bluetooth enable`
  resets it. Wait for `BikeRadar.Radar: handshake complete` + `first V2
  frame` in logcat before declaring the app ready to test.
- AlertDecider's imminent-impact override has TWO disjunct gates: the
  proximity gate (`distance <= alertMaxM/3 AND closing >= 6 m/s`) and a
  TTC gate (`TTC <= 3s AND closing >= 6 m/s AND distance <= alertMaxM`).
  It arms when the rider is stationary, and - via the low-speed extension
  (`urgentLowSpeedEnabled`, default on) - while moving at <= 15 km/h with
  both gates' closing floor raised to 10 m/s on that moving path.
  Boundary tests in `AlertDeciderTest.kt` pin the semantics. Don't reduce
  the override to a single gate or loosen the moving floor without
  re-running the capture replay.
- `AlertBeeper` is service-scoped (allocated in `BikeRadarService.onCreate`,
  released in `onDestroy`). The first beep after every BLE reconnect lands
  on the same warm AudioTrack pool; do not allocate per-overlayJob.
- `AlertBeeper` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per cue with
  a re-arming abandon timer. The walk-away alarm path uses the stronger
  `_EXCLUSIVE` flavour and is separate from the close-pass path.
- While a call is active (`audioManager.mode` is `MODE_IN_CALL` for telephony
  or `MODE_IN_COMMUNICATION` for VoIP) the close-pass beeper skips the audio
  path entirely (visual overlay still fires). Non-negotiable, no Settings
  toggle.
- `ACCESS_COARSE_LOCATION` is optional and IS prompted in-app: in onboarding,
  in Settings -> Permissions, and via a contextual card in Settings -> Light
  auto-mode (shown whenever either light's auto-mode is on - granted or not,
  because the card also carries the manual-coordinate override). All three
  surfaces offer manual coordinate entry as an alternative to the grant,
  sharing one integrated "grant or enter coordinates" component. A screen that
  builds the alternative itself does so ONLY via `locationAlternativeFor()` in
  `ManualLocation.kt`, which binds it to the shared `ManualLocationState`; a
  screen that delegates to a stateless leaf passes that state's summary and
  callbacks down, and the leaf calls `locationAlternative()`. Never hand-build a
  `PermissionAlternative` on a production screen: the card then promises
  coordinate entry the screen does not wire up, and nothing compiled catches it.
  **The Roborazzi goldens cannot pin this** - the permissions ones render
  `SettingsPermissionsContent`, which has no production caller, and the lights
  ones inject their own alternative into the `locationCard` slot. The three
  `*CoordinatesTest` classes compose the real screens and are what fails; each
  pins that save reaches `Prefs` and the card, that clear empties both, and that
  the dialog opens and closes. If location is neither granted nor set, the
  day/night auto-mode falls back to London times.

## Audio design

The alert-audio model - close-pass tier beeps, the urgent impact cue, the
all-clear chime, the radar drop/reconnect cues, and the inactivation states
(audio-focus ducking + in-call suppression) - is an informal
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

## Sibling repository

The sibling docs repo `../bike-radar-docs/` (public) is the canonical
protocol spec.
