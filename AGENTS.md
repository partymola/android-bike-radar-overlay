# AGENTS.md

Pointer doc for agent-style tools working in this repo. Public-safe.

## Quick start

```bash
docker build -t bike-radar-builder .
docker run --rm -v "$PWD:/workspace" -u "$(id -u):$(id -g)" \
  -v "$HOME/.cache/bike-radar-gradle:/gradle-cache" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -w /workspace bike-radar-builder \
  gradle :app:testDebugUnitTest --console=plain --no-daemon      # unit tests

# ...or assembleDebug for a full APK:
gradle assembleDebug --console=plain --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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
- Decoders are stateful per-track and pure JVM (no Android imports) â unit
  testable with plain JUnit.
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
  purpose â renaming would break existing subscribers. See
  `HaClient.kt:22-23`.

## Testing

- All decoder logic is pure JVM; test with `:app:testDebugUnitTest`.
- No Android instrumentation tests (`connectedDebugAndroidTest`) in this repo.
- Decoder tests build a 9-byte target struct via the `target()` helper;
  `templateLocked = true` by default so new tests appear in snapshots.

## Gotchas

- APK reinstall (`adb install -r`) kills the app process without running
  `onDestroy`, so Bluedroid retains a half-open GATT. The next handshake
  ABORT triggers a GATT `disconnect()` + `close()` in `runRadarConnection`,
  and the outer reconnect loop picks up a fresh connection via the
  quick-reconnect path (bypasses the normal backoff; ~1.5 s recovery).
- Never subscribe the CCCD of `6a4e3203` (V1 radar char). Subscribing locks
  the radar into V1-only mode and suppresses V2.
- Pairing: Android 16 / Pixel's programmatic `createBond()` is broken for
  LESC; the app never calls it. User must pair once via system Settings.

## Contributing

- GPL-3.0-or-later. Don't copy non-GPL-compatible code.
- Protocol corrections go to the `bike-radar-docs` repo, not this one.
- Decoder behaviour changes must add or update unit tests.

## Local-only notes (not in this repo)

Working drafts and research live in gitignored directories
(`notes/`, `do-not-commit/`). Keep any local index under
`do-not-commit/INDEX.md` — the directory is gitignored so nothing
there reaches public history.

The sibling docs repo `../bike-radar-docs/` (public) is the canonical
protocol spec.
