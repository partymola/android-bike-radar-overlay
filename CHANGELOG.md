# Changelog

## v0.5.0-alpha — 2026-05-03

### Features

- **Live close-pass count on the home screen.** The stats card now shows the actual count of close-pass events for the current ride instead of a permanent zero. Resets when the service starts.
- **Per-ride summary published to Home Assistant.** Ten new MQTT-discovery sensors per radar device — HA derives the entity IDs from the display names: `overtakes`, `close_passes`, `grazing_passes`, `hgv_close_passes`, `peak_closing_speed`, `closing_speed_p90`, `tightest_clearance`, `distance_ridden`, `time_with_traffic`, `close_pass_conversion_rate` (each prefixed `sensor.varia_<slug>_`). A `tightest_pass` JSON attribute carries the worst-pass-of-the-ride record (timestamp, side, vehicle size, clearance, closing speed). Auto-discovers under the same HA device card as the existing battery and close-pass entities; vanilla HA + MQTT integration is enough, no YAML required. Published every 60 s when state changes; sensors flip to `unavailable` ten minutes past the last update. Measurement-class fields (peak closing, p90, tightest clearance) report `unknown` until the first relevant observation lands, instead of a misleading 0.

### Reliability

- Handshake aborts early when the GATT service list is incomplete instead of silently completing without unlocking the V2 frame stream.
- Radar state bus is cleared in the `connectAndRun` finally block, eliminating a window where stale vehicle data persisted through reconnect backoff.
- Close-pass event and `/last` retained topic publish concurrently rather than back-to-back, halving HA round-trip on every event.
- `BatteryStateBus` updates use atomic `MutableStateFlow.update`; concurrent battery readings can no longer drop each other.
- `walkAwaySnoozeJob` switched to `AtomicReference` so cancel-then-replace is atomic across notification action handlers and GATT-callback threads.
- Overlay teardown reorders `removeView` before nulling the service-level refs; `addView` failures now log to logcat as well as the capture log.
- DebugScreen pauses radar-state log collection when off-screen.
- BLE dashcam reader tightens GATT close idempotency; dashcam ticker idles after 15 minutes of radar disconnect.

### Internal

- JVM test suite expanded to 288 tests (Robolectric). New coverage: service and activity boot smoke; all 13 Compose screens composed synchronously; gate-matrix tests for `BootReceiver`, `InternalControlReceiver`, and `RemoteControlReceiver`; `HaClient` short-circuit guards; pipeline replay through decoder → detector → accumulator; `HaCredentials` round-trip.
- `HaCredentials` crypto extracted to a `Cryptor` interface (`AndroidKeyStoreCryptor` in production, `InMemoryCryptor` in tests). Removes the requirement for a real AndroidKeyStore in JVM tests.

### Compatibility

- No migration. SharedPreferences, paired devices, HA credentials, overlay positioning, and the existing battery + close-pass MQTT topics are unchanged. The new ride-summary entities appear automatically in HA on first publish.

## v0.4.1-alpha — 2026-05-02

### Stability

- The radar reconnects reliably after `adb install -r` (or any process replacement) without needing a Bluetooth power-cycle. The previous build left a stack-side GATT intention orphaned across process death and triple-opened on every fresh service start.

### Power

- Overlay redraws use a single shared `RectF` instead of allocating four per frame.
- Status dots no longer pulse on screens where state is already conveyed by colour or label.
- Active BLE kickstart scan only runs when the device cache is empty.
- `CONNECTION_PRIORITY_LOW_POWER` requested once the V2 stream is established.
- HA close-pass publishes hop to `Dispatchers.IO` immediately.
- MainScreen recomposition cadence halved (5 s wall-clock tick).
- HA HTTP timeouts trimmed to 3 s.

### Internal

- UI files renamed: the `*Next` suffix is gone; six primitives that collide with `androidx.compose.material3` (Card, Chip, Slider, Toggle, OutlinedButton, SegmentedControl) take the existing `Br` prefix.
- New `tools/bike-radar-test.sh` helper for live-testing via ADB.

### Compatibility

- No migration. SharedPreferences, paired devices, HA credentials, and overlay positioning unchanged.

## v0.4.0-alpha — 2026-05-01

### Security

- HA bearer token is no longer sent in cleartext to non-LAN hosts. HTTPS is required outside RFC1918, loopback, IPv6 unique-local, link-local, and `.local` / `.lan` hostnames.
- New **Clear HA configuration** button removes stored credentials.
- Pause and walk-away receiver actions moved off the exported broadcast surface; peer apps can no longer silently pause the overlay or dismiss the walk-away alarm.
- Build pipeline hardened: third-party GitHub Actions pinned to commit SHAs with weekly Dependabot upgrades, and CI write permissions narrowed to the release job.
- Dashcam MAC and display name redacted in the diagnostic bundle.
- Background BLE scans only trigger device reads on radars or dashcams already paired through Android Settings; stray peer apps cannot provoke unwanted GATT activity against unrelated devices.
- One-time share warning before sending a capture log: the log includes per-packet timestamps that can reconstruct ride timing.
- Radar decoder hardened against malformed input via fuzz tests (60 000 random byte arrays plus edge-shape and reset-mid-stream variants).

### UX

- Distinct **Bluetooth is off** state on the app's main screen with a "Turn on Bluetooth" prompt, separate from **Radar not paired**.
- Settings button moved to the bottom of the main screen.
- Two-column layout in landscape: status + close-passes on the left, system card + Settings on the right.
- Tighter copy on HA URL refusal and Bluetooth-state messages.

### Compatibility

- No migration steps. The HA close-pass MQTT payload still publishes `closing_speed_kmh` and `rider_speed_kmh`, the Settings slider stays in km/h, and stored preferences are unchanged. Internally the decoder switched from km/h to m/s; alert and close-pass thresholds were re-expressed at exact equivalents so alerting fires on the same radar readings as v0.3.0-alpha.

## v0.3.0-alpha — 2026-04-29

### UX

- Walk-away alarm is audible and bypasses Do Not Disturb, with a Settings toggle under Dashcam.
- Walk-away alert auto-dismisses when the dashcam comes back online.
- Removed the V1 UI surface; only V2 radars are supported.
- Cleaner empty state on the close-pass card.

### Stability

- Periodic screenshots no longer die on screen rotation.
- Walk-away alarm no longer blocked by a mis-counted radar idle time.
- Dashcam glyph refresh runs independently of radar state.
- Home Assistant HTTP connections kept alive across publishes.
- Overlay redraws skip identical states to reduce CPU.

## Earlier releases

v0.2.0-alpha and earlier are not retroactively documented in this file; see the release notes on GitHub.
