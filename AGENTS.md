# AGENTS.md

Pointer doc for agent-style tools working in this repo. Public-safe.

## Quick start

```bash
docker build -t bike-radar-builder .
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -w /workspace bike-radar-builder \
  gradle :app:testDebugUnitTest --console=plain --no-daemon   # unit tests (what CI runs)

# Screenshot regression check (local only — Paparazzi SNAPSHOT loader is
# unreliable on cold-cache JVMs, so CI can't run it):
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -w /workspace bike-radar-builder \
  gradle :app:verifyPaparazziDebug --console=plain --no-daemon

# ...or assembleDebug for a full APK:
gradle assembleDebug --console=plain --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`,
add a top-level entry to `CHANGELOG.md` (Security / UX / Compatibility
headings — see existing entries for tone), then push a `v*` tag (e.g.
`v0.4.0-alpha`). The tag triggers `.github/workflows/release-apk.yml`,
which builds a release-signed APK and publishes a GitHub Release. Tags
containing `-` (alpha/rc/...) are marked pre-release automatically.

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
- HA integration is optional; the overlay works standalone.
- Capture log is always written to
  `/sdcard/Android/data/es.jjrh.bikeradar/files/bike-radar-capture-<stamp>.log`.

## Key files

| Path | Role |
|------|------|
| `app/src/main/java/es/jjrh/bikeradar/BikeRadarService.kt` | Unified foreground service |
| `app/src/main/java/es/jjrh/bikeradar/RadarV2Decoder.kt` | V2 target-struct decoder (stateful) |
| `app/src/main/java/es/jjrh/bikeradar/RadarUnlock.kt` | Scripted AMV 04 handshake to enable V2 |
| `app/src/main/java/es/jjrh/bikeradar/RadarOverlayView.kt` | Canvas overlay |
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

- All decoder logic is pure JVM; test with `:app:testDebugUnitTest`. This is
  what CI runs (Robolectric only). Paparazzi screenshot tests are excluded
  from this task because Paparazzi 2.0.0-SNAPSHOT's layoutlib loader fails
  on cold-cache JVMs.
- Locally, run `:app:verifyPaparazziDebug` to compare against golden PNGs.
  This is the QC gate before any push that touches `app/src/main/**`.
- To regenerate goldens: `:app:recordPaparazziDebug --rerun-tasks`. Commit
  the updated PNGs under `app/src/test/snapshots/images/`.
- No Android instrumentation tests (`connectedDebugAndroidTest`) in this repo.
- Decoder tests build a 9-byte target struct via the `target()` helper;
  `templateLocked = true` by default so new tests appear in snapshots.

## Gotchas

- After `adb install -r` the radar GATT may be left half-open;
  `runRadarConnection`'s ABORT path closes and reconnects automatically
  (~1.5 s). If the reconnect doesn't happen, see live-testing recovery
  below.
- Never subscribe the CCCD of `6a4e3203` (V1 radar char). Subscribing locks
  the radar into V1-only mode and suppresses V2.
- Pairing: Android 16 / Pixel's programmatic `createBond()` is broken for
  LESC; the app never calls it. User must pair once via system Settings.
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

## Contributing

- GPL-3.0-or-later. Don't copy non-GPL-compatible code.
- Protocol corrections go to the `bike-radar-docs` repo, not this one.
- Decoder behaviour changes must add or update unit tests.
- Commit subjects use the conventional-commits prefixes already
  visible in `git log`: `ui:`, `test:`, `build:`, `ci:`, `docs(...):`,
  `fix:`, with optional scope like `ui(onboarding):`.

## Local-only notes (not in this repo)

Working drafts and research live in gitignored directories
(`notes/`, `do-not-commit/`). Keep any local index under
`do-not-commit/INDEX.md` — the directory is gitignored so nothing
there reaches public history.

The sibling docs repo `../bike-radar-docs/` (public) is the canonical
protocol spec.
