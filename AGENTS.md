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
mkdir -p "$HOME/.cache/bike-radar-gradle" "$HOME/.cache/bike-radar-m2"
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -v "$HOME/.cache/bike-radar-m2:/m2" \
  -e JAVA_TOOL_OPTIONS=-Dmaven.repo.local=/m2 \
  -w /workspace bike-radar-builder \
  ./gradlew :app:testDebugUnitTest --console=plain --no-daemon

# `scripts/dev gradle ...` auto-falls back to the one-shot pattern when
# the persistent container is not up, so it is safe to use either way.

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Every build runs the wrapper, and `gradle/wrapper/gradle-wrapper.properties`
is the only place a Gradle version is written down.** The container ships a
JDK and no Gradle, and no workflow passes `gradle-version:` to
`gradle/actions/*`, so those actions use the wrapper. Do not reintroduce a
pin to "be explicit": nothing checks a second copy agrees, and a from-source
build (the F-Droid path) could compile with a Gradle no gate had run.

The distribution is not baked into the image, so the first `./gradlew`
against a cold `~/.cache/bike-radar-gradle` downloads it and needs network.
The same goes for Robolectric's SDK jars, cached separately in
`~/.cache/bike-radar-m2`: the first unit-test run on a cold cache downloads
them.

Screenshot tests: `:app:verifyRoborazziDebug` renders the Compose and
Canvas goldens via Robolectric Native Graphics, so they run inside
`testDebugUnitTest` and in CI - no device, emulator, or layoutlib.
Regenerate goldens with `:app:recordRoborazziDebug` and commit the PNGs
under `app/src/test/snapshots/images/`.

**A golden cannot see a system-bar inset.** Robolectric renders no status or
navigation bar, so a screen missing `systemBarsPadding()` produces a byte-identical
golden to one that has it, and every gate stays green while the title sits under
the phone's clock. Every full-screen surface here carries
`Modifier.fillMaxSize().background(br.bg).systemBarsPadding()` on its outermost
container - a `Box` on most, a `Column` on `OnboardingScreen`,
`SoundDemoScreen`, `AlertVolumeScreen` and the riding-aid notice's gate form,
which pins a footer button below the scrolling text. `OnboardingScreen` is
what the `Onboarding*Step` files render inside, which is why they carry none
of their own. A new surface that does not is only catchable on a device.

**Before bumping the version, writing a CHANGELOG section or cutting a `v*`
tag, read [`RELEASING.md`](RELEASING.md).** It holds the heading order,
the single-line-bullet rule the release body depends on, the fastlane
changelogs, and the rule that a released section is never revised.

**The store and README screenshots are Roborazzi goldens, copied.** EVERY image
under `screenshots/` and `fastlane/.../phoneScreenshots/` is a byte copy of a
golden from `app/src/test/snapshots/images/`, which is why they carry the
fixture host `homeassistant.local:8123`, a masked token and no device names.
Most are whole screens at 1344x2991. The exception is the overlay strip alone
at 390x2991, `RadarOverlayViewTest.multipleVehicles`, the one published image
that is not a whole screen: it is the README hero and store slot 2 in both
locales. Overlay goldens render the landscape strip a mounted phone shows
(390x1344) unless the test asks for portrait, and only this one is
published. Store slot 1 is the home screen, so do not re-copy the strip
into it.

**Re-copy rather than re-capture.** No published image is a device capture: a
capture is 1344x2992, one pixel taller, and would carry the rider's real Home
Assistant host and device names into a public artefact.

`scripts/check-screenshot-freshness.py` reports any portrait image that is no
longer a copy of a current golden. It is a **non-blocking** `ci.yml` step,
because the goldens re-record on any UI change; read the log, not the exit
status. A non-zero "landscape skipped" count means a device capture has come
back, which is what its landscape branch is kept for. It cannot tell whether a
slot holds the RIGHT golden, only that it holds one; the README alt text says
which screen belongs where, so check re-copies by eye.

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
see [`ARCHITECTURE.md`](ARCHITECTURE.md), whose Key files table maps each part
to its file. The notes below are the working summary.

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
  Key files in ARCHITECTURE.md). The service stays the sole owner of `scope`
  and the warm `AlertBeeper`; `RadarLinkCoordinator` owns the
  radar-link/walk-away state (`_radarLinkState`) and the transitions that
  drive the dismount alarm + the dropped-radar cue. The radar controller
  reaches the state through a `RadarLinkStateGateway`, and the camera
  controller reads the radar off-time through an injected lambda for its
  shared backoff cap.
- **The riding-aid notice is in front of EVERY other destination, and that
  ordering is the feature.** `startDestination` (`ui/SafetyNoticeGate.kt`)
  answers "safety-notice" whenever `Prefs.safetyNoticeAcknowledged` is false,
  before it looks at `firstRunComplete` at all. That is what lets ONE flag,
  defaulting false, serve a fresh install and an install upgrading from a
  version that never wrote the key, without either seeing the screen twice.
  Making it an onboarding step instead would skip every existing rider
  silently, since they are already past onboarding, and no manual test on a
  set-up phone would show it. `SafetyNoticeGateTest` pins all four
  combinations plus the fact that `MainActivity` routes through the gate
  rather than re-deriving the branch; `SafetyNoticeAcknowledgeTest` drives the
  real button in the real activity as an upgrading rider.
  It gates ACTIVITIES, never the service: the overlay and the foreground
  notification still reach an unacknowledged rider, which is what
  `BootReceiver` produces on `MY_PACKAGE_REPLACED` and what
  `MainActivitySmokeTest.theServiceStillStartsWhileTheNoticeIsUp` pins.
  `RadarConsentActivity` is the second reader of the flag and the one that
  matters most, being exported: it shows the notice in front of its question,
  and only after the decider's refusals, so a ride in progress or an unknown
  caller is still answered with no screen at all.
  In `NoScreenBeforeTheNoticeTest` the tests with a caller the decider accepts
  are the ones where that gate does work; the two refusal tests use a caller or
  a moment the decider rejects on its own, so they cannot see it.
- The app connects to two BLE device classes: the rear radar and the front
  camera/light. Each has its own AMV UUID pair (see Gotchas).
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
  (the `captures/` subdir scopes the FileProvider share to the logs, not the
  whole external-files root). Cap is `MAX_CAPTURE_LOGS = 50`; with the toggle
  off no file is created.
  `clog` lines mirror to logcat only in debug builds (`BuildConfig.DEBUG`);
  release builds keep BLE/movement payloads out of logcat. Every other sink
  carrying raw device bytes is guarded the same way, because the handshake
  replies include the device-ID frame. `SettingsPrivacyLogcatGuardTest` names
  and pins those sites; no runtime test can, since `BuildConfig.DEBUG` is true
  under the test variant. The boundary is RAW BYTES: a decoded value the app
  already publishes to Home Assistant is not a payload, though some are
  guarded and pinned anyway because they sit beside one. Device names and
  connection state still reach release logcat, deliberately, because the link
  journal records them and the Privacy screen discloses it.
  By default a file opens per radar connection after the handshake, so a
  mid-ride radar drop splits one ride across files with the gap between them
  unrecorded, and an ordinary capture carries neither the DIS serial nor the
  device-ID frame. A radar that never completes the handshake produces no
  capture, unless it takes the legacy-stream fallback, which opens one after
  the abort. **Record connection setup** (`Prefs.setupTranscriptEnabled`)
  opens the file before the GATT connect and keeps one file across the whole
  reconnect loop, successful rides included. It is the tool for
  unsupported-hardware reports, and carries the serial and device-ID frame
  when the handshake gets that far; its subtitle and the issue template tell
  reporters to turn it off when done. The open file is listed on the Debug
  screen marked as recording: its row withholds delete, and Delete all and
  prune skip it (`deletableCaptureLogs`, `CaptureLogManager.prune`), because
  unlinking it under the live writer loses the session silently. Turning the
  transcript toggle off closes the file at the end of the next attempt that
  gets a GATT connection (a whole ride, if that attempt is one), or when the
  service stops or Bluetooth drops. Turning the capture-log master switch off
  closes it at the next attempt (`CaptureLogManager.open`).
  Separately, each link stores its discovered-service table and abort token
  (`Prefs.radarLinkProbe`, `Prefs.cameraLinkProbe`; one slot per link,
  deliberately), printed in the diagnostic bundle. The exits before service
  discovery record an outcome with no table, so a bundle never reports the
  previous attempt's stopping point as this one's. Within a process each
  distinct answer keeps its first-seen `since=` stamp (`LinkProbeRecorder`);
  the slot holds one line, so after a restart only the stored answer keeps
  its age and any other answer restamps.
  Every file's header carries a build stamp, with `commit=` on non-release
  builds only, and a `# clock unix_ms=.. mono_ms=..` anchor that lets packet
  stamps be converted to the elapsedRealtime sensor series by subtraction
  (the NTP caveat is in the `CaptureLogManager` KDoc). **A release-variant
  capture is NOT attributable to a tree**: two release APKs built from
  different code stamp identically. Why, and the `commit=unknown` fallback:
  `BuildStamp` KDoc.

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

**Before adding or editing any user-facing string, in either locale, read
[`WRITING_COPY.md`](WRITING_COPY.md)**; the `/qc` copy reviewer enforces it.
It holds the Spain-Spanish rules (never "rodar"; the percent sign takes no
space), the Privacy screen exception, and the fact that
`scripts/privacy-disclosure-check.sh` reads the English strings only, so when
a disclosure changes both locales are read side by side.

## Testing

- Tests hard-code literal expected values. Never assert a production constant
  against itself (`assertEquals(SOME_CONSTANT, actual)` stays green when the
  constant is wrong - a *tautological test*; see "DAMP vs DRY" and "don't share
  constants between test and production"). Mutation testing is the detector: if
  unsure a test pins a value, mutate the constant to a degenerate value and
  confirm a test goes red.
- **A consequence: never conclude "nothing pins this" from grepping for the
  constant.** The tests that pin one hardest deliberately do not name it, and
  the hit the grep does return can be the test that cannot fail. Grep
  `RADAR_DROP_ACTIVITY_FRESH_MS` and the one test naming it is
  `noRadarDropCueForRadarOnlyDismount`, which derives its stale instant from
  the constant and so stays green whatever the value becomes. The test holding
  that window apart from the rider's configurable one is
  `theSpeedGateKeepsItsOwnWindowWhateverTheRiderChooses`, on a literal
  `9_999L`. Read the test file, or search for the behaviour, not the symbol.
- All decoder logic is pure JVM; test with `:app:testDebugUnitTest`
  (Robolectric). CI runs this alongside `:app:lintDebug`,
  `:app:ktlintCheck`, `:app:verifyRoborazziDebug`, and
  `:app:jacocoCoverageVerification` (see
  [`QUALITY_GATES.md`](QUALITY_GATES.md)).
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

CI runs ktlint, lint, JaCoCo (a project ratchet plus a diff-coverage gate),
a release DEX keep gate, `boot-smoke`, licence-header and transitive-licence
checks, and CodeQL; `/qc` runs the ktlint, lint, coverage and licence-header
gates locally. **Before changing a workflow under `.github/`,
`.editorconfig`, the coverage, lint or R8 configuration, `proguard-rules.pro`,
or a gate script, read [`QUALITY_GATES.md`](QUALITY_GATES.md)**: what each
gate checks and what must not be changed about it. Four that bite in
ordinary work:

- ktlint's baseline is empty and all code must be clean; `:app:ktlintFormat`
  fixes most findings. Never regenerate the baseline to silence one.
- Run `:app:jacocoDiffReport` immediately before
  `scripts/diff-coverage-gate.py`: the script reads that report and never
  builds it, and a stale one looks like a real measurement.
- A new enum whose constant NAMES are persisted or published, or a new
  constant in an enum already listed (the light modes, `AttentionKind` and
  others), goes into the DEX keep table in
  `scripts/check-release-dex-keeps.py` by hand; nothing forces it in.
- Every new Kotlin, AIDL, first-party Python or shell file carries an SPDX
  identifier and a copyright line (`GPL-3.0-or-later`; `Apache-2.0` on the six
  contract files). CI blocks on it; check with
  `python3 scripts/check-licence-headers.py`, and `--fix` inserts what is
  missing.

## Gotchas

- `CameraLightMode`'s BLE wire value is `ordinal + 1`, so reordering its
  constants breaks the device protocol. The release DEX gate cannot see it,
  since it reads names, not order; `CameraLightModeWireFormatTest` pins the
  bytes.
- **`RadarLightMode`'s ORDER is not a wire format, and must not become one.**
  The wire values are `RadarContract.LIGHT_MODE_*`, and `RadarIpcBinder` maps
  them to the enum case by case, so the two move independently. Do NOT
  refactor that `when` toward `RadarLightMode.entries.getOrNull(mode)`: an
  ordinal makes reordering the constants change what every already-installed
  consumer sets on a rider's tail light, with no compile error, no failing
  test, the DEX gate blind to it because it reads names, and the consumer a
  different APK.
  `RadarIpcBinderTest.theWireValueOfEveryLightModeIsFixed` maps each
  constant to a literal int and
  `anOutOfRangeLightModeIsRefusedRatherThanCoerced` pins the boundary;
  `RadarContractTest.everyLightModeWireValueIsFixedAndDistinct` pins the
  constants themselves. Do not restate any of them as
  `RadarLightMode.X.ordinal`, which is what would make them agree with the
  code by construction and stop failing. Changing a `LIGHT_MODE_*` value is a
  `RadarContract.VERSION` bump; a new mode needs a value and a `when` branch,
  and until it has both it is simply unsettable over the contract.
- After `adb install -r` the radar GATT may be left half-open;
  `runRadarConnection`'s ABORT path closes and reconnects automatically
  (~1.5 s). If the reconnect doesn't happen, see live-testing recovery
  below.
- Never subscribe the CCCD of `6a4e3203` (V1 radar char) on a radar that has
  `6a4e3204`. Writing that CCCD before the enabling sequence (fw 6.70) drops
  the radar into V1: the handshake succeeds, V1 heartbeats arrive on
  `6a4e3203`, and `6a4e3204` never emits. Later connections that never touch
  the CCCD get no V2 either, until the radar is power-cycled. See
  `Uuids.RADAR_V1`.
  - **The one sanctioned subscribe is the legacy-stream fallback**, and its
    gate is that whole exception. `RadarLinkController.legacyStreamChar`
    returns the characteristic ONLY when the radar service carries no
    `6a4e3204`, read off the discovered GATT table rather than the stored
    probe. A radar with V2 therefore cannot reach the subscribe, so the pin
    cannot be applied to a radar it would cost anything. `RadarLinkControllerHarnessTest`
    pins both directions: falls back when the characteristic is absent, never
    falls back when it is present however the handshake ends.
  - **Do not relax that gate to a retry count.** A count fires on a healthy
    radar after a transient handshake failure, which is precisely when the pin
    is expensive - it would cost the rider V2 until they power-cycle. The
    absence of `6a4e3204` is a fact about the hardware; a retry count is a
    guess about the moment.
  - The stream itself carries range only. `RadarV1Decoder` writes a zero
    closing speed and sets `lateralUnknown` on every track, and both are
    fail-closed sentinels rather than measurements: the urgent cue and
    close-pass detection must stay shut on it, while the distance-scored
    awareness tiers and the all-clear still work. `RadarV1SafetyTest` is the
    argument, and it holds close-pass shut even if a rider speed later arrives
    from another source. Read it before widening this path.
- AMV UUID pairs differ by device class: the rear radar uses RX=`6a4e2811`/
  TX=`6a4e2821`; the front camera/light uses RX=`6a4e2810`/TX=`6a4e2820`.
  Mixing the pairs causes silent handshake failure — the device accepts the
  writes but never responds correctly. `DeviceVariant` selects the right
  pair (`RADAR` or `FRONT_CAMERA`).
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
- **Settings cannot ask Android about an app the rider granted.** There is no
  `<queries>` entry for consumer apps, so on Android 11+ the package manager
  sees one only after it has bound or opened the consent screen, and, measured
  on an emulator, loses it again after a reboot. The gate and the consent
  decider run while that app is calling, so their lookups work; a lookup from
  Settings answers as if the app were not installed. That is why the outcome
  of each key check that could read the app's keys is RECORDED as
  `RadarGrant.refused` at that moment rather than derived by Settings
  (`StoredRadarAccessGateTest`, `RadarConsentDeciderTest`,
  `SettingsRadarAccessRefusedTest`).
  The unit suite drives both through fake identities, so it cannot see this;
  check a change here on a device or emulator.
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
  The sound demo, the volume step and the Alert sounds page build a second,
  screen-scoped beeper (`newDemoBeeper`) on the same alarm stream. It joins both halves of
  the walk-away interlock through the shared Prefs slots and never runs the
  crash repair, since the ride's beeper may hold a lift from that slot
  (`WalkAwayAlarmBeeperInterlockTest.aSoundDemoLiftIsInterlockedWithTheWalkAwayAlarmToo`,
  `DemoCuePlayerTest`). Each beeper takes the rider's level for a new lift
  from that slot (`sharedFloorBaseline`) before the stream, so a lift that
  starts while the other beeper's saved level is in the slot saves that
  level, not the raised stream (`TwoBeepersShareOneFloorTest`); do not
  collapse that seam into `loadAlarmFloor`, which would make the demo
  repair from a screen. The debug overlay's preview beeper
  (`DebugOverlayService`) has none of this wiring; any beeper a rider
  reaches outside the debug tools needs all of it.
- `AlertBeeper` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per cue with
  a re-arming abandon timer. The walk-away alarm path uses the stronger
  `_EXCLUSIVE` flavour and is separate from the close-pass path.
- While a call is active (`audioManager.mode` is `MODE_IN_CALL` for telephony
  or `MODE_IN_COMMUNICATION` for VoIP) the close-pass beeper skips the audio
  path entirely (visual overlay still fires). Non-negotiable, no Settings
  toggle. The overlay is also shown over a granted app's hold for the length
  of the call, since it is then the only warning the app can give, and the
  hold applies again after (`aCallPutsAHeldOverlayBackAndTheHoldResumesAfter`).
  The ride notification drops its "Overlay hidden" line for the call, and
  `ServiceNotifications.launchReposts` reposts it whenever the holders or the
  call state change (`aCallStartingOrEndingRepostsTheLine`).
  `AlertBeeper.isCallMode` is the one definition of "a call"
  (`AlertBeeperCallModeTest`); `suppressForCall` reads it.
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
*classes* (by timbre, not fine pitch), alarm parsimony, and an imminent-impact
cue that cuts through the managed beep channel and its own episode pacing when
a new condition appears. No in-ride cue overrides a rider's pause (`AUDIO_DESIGN.md`,
"When the audio steps back"). Design inspiration only; the app is not a medical
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
- **Two files carry the cross-app licensing, and they cover different halves.**
  Change either only on purpose.
  - **Six files are Apache-2.0**: the three `.aidl`, plus `RadarContract.kt`,
    `RadarStateParcel.kt` and `RadarVehicleParcel.kt`. Together they are a
    complete client contract; the `.aidl` alone are not, since
    `RadarStateParcel.aidl` is a forward declaration and the constants are not
    in them. Licence text is `LICENSES/Apache-2.0.txt`. Everything implementing
    them stays GPL-3.0-or-later, and Apache-2.0 combines into a GPL-3.0 whole,
    so the app's own licence is untouched. Do NOT normalise those headers, and
    keep those files free of any reference to the app behind them, or the
    permission means nothing. `InterfaceIsPermissiveImplementationIsNotTest`
    pins the list and `ContractIsSelfContainedTest` pins the self-containment.
    A new `.aidl` fails until someone puts it on a side; a new `.kt` lands in
    the copyleft bucket and passes quietly, so decide deliberately.
    **The list exists in three places and all three must move together**: that
    test, `PERMISSIVE_FILES` in `scripts/check-licence-headers.py`, and the
    README section the same check pins. Updating only the test reds CI worded as
    an SPDX mismatch, which is loud but names the wrong cause.
  - **`additional-permission.txt` covers writing a consumer** rather than
    copying our files: a section 7 grant that an app communicating solely
    through the interface is not, by virtue of that communication, a work based
    on this one. That qualifier is load-bearing and must survive any rewrite;
    without it the grant reaches an app that also embeds our GPL code. Do not
    argue in public docs that two apps over Binder are separate programs. The
    grant settles the question rather than taking a position on it.
  - **A permissive file's imports stay platform-only** (`android.`, `java.`,
    `kotlin.`). Nothing enforces that, deliberately: every other check on this
    surface exists because its failure is SILENT, and this one's is not. A
    third-party import fails at build time on the copier's side, naming what
    they lack; an `androidx` one usually just resolves, because virtually every
    Android project already carries androidx. Neither is a licence problem
    while every artifact on the release runtime classpath is Apache-2.0, which
    `scripts/check-transitive-licences.py` reports on rather than gates. An
    import here necessarily lands on that classpath. Do not read the absence of
    a test as the absence of the rule.
  - **A NEW FIELD ON `RadarStateParcel` IS A DISCLOSURE CHANGE.** Whatever
    crosses that wire reaches any app the rider has granted, and
    `settings_privacy_to_apps_body` is where they read what that is, in BOTH
    locales. `HaClientDataDisclosureTest` forces this for the MQTT side; there
    is no equivalent for the cross-app side, so a field added here ships
    undisclosed with every gate green. Update the disclosure in the same commit.
- **A comment keeps the fact a maintainer could undo, and the test that pins
  it. Everything else goes.** Cut the argument for the fact, the second example,
  the consequence restated, and any sentence whose job is to show that the
  reasoning happened. This is a public repo, so volume is exposure as well as
  noise; the exception is the files a consumer copies, where the KDoc is the API
  documentation they read.
  **Cutting has its own failure mode, and it is worse than verbosity: keeping a
  claim while deleting what enforces it.** Before deleting a sentence, ask
  whether anything else in the repo still says it. Two here are load-bearing
  and easy to mistake for padding: `RadarControlBridge`'s "no two connection
  attempts overlap", whose three enforcing properties live in
  `RadarLinkController`, and `onConsumerDied`'s lock-ordering argument, which
  rests on AOSP behaviour no test here drives. An external assumption about
  framework behaviour, and any invariant no test drives, both stay whatever
  the length.
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
