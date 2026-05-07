# Changelog

## v0.6.0 — 2026-05-07

### Features

- **Front-camera light auto-mode.** The app now drives a paired front-camera light: chosen mode follows sunrise / sunset times computed from the rider's location, with manual-override detection so a rider's button press doesn't fight the app's mode choice. New Settings screen exposes day-mode / night-mode pickers and the auto-mode toggle. Mode state publishes to Home Assistant as `sensor.varia_<slug>_front_mode`. Verified on firmware 5.80.

### UX

- **Overlay dimmer slider.** A new Settings → Radar & alerts → Overlay section gathers Visual distance and a 4-stop "Off / Light / Medium / Strong" overlay dimmer. Visual distance moved out of the Alerts section since it controls the overlay's render cutoff, not audio alerts. The dimmer multiplies the View's alpha on top of the existing per-paint alphas: "Off" (the default) preserves the prior look; lower stops dim the overlay further so an underlying map or navigation app shows through.
- **Configurable reconnect-backoff long-offline tier.** When the radar has been out of range past a user-set threshold (default 30 min), the reconnect interval relaxes to a user-set cap (default 30 s) so the BLE stack idles overnight instead of hammering GATT opens at the steady-state 8 s ceiling. New "Connection" section in Settings exposes both knobs.

### Reliability

- **Front-camera handshake more robust against spurious notifies.** The sub-mode-toggle reply matcher now requires the full `41 4d 56 18` AMV-signature-plus-opcode at bytes 8-11 (previously only byte 10 + byte 11), so a stray notification with coincidental bytes at those offsets can no longer be mistaken for the genuine reply.
- **Front-camera handshake adapted to firmware 5.80.** The 13-byte cmd-16 reply on this firmware carries no `pfxCmd` byte; the device-ID push frame the rear radar emits is absent on the camera. The handshake now terminates cleanly after the `0x18` sub-mode toggle's third reply rather than waiting indefinitely.

### Diagnostics

- **Phone-battery trace logged into per-ride capture log.** Battery level (percent), temperature (decicelsius), and charging state are sampled on every level change and on a 60 s heartbeat. Read from the cached sticky broadcast — no continuous receiver, no extra wake-ups. Cross-references with radar / dashcam / handshake events in the same log for post-ride battery analysis. `dumpsys batterystats` remains authoritative for per-uid mAh attribution.

### Internal

- **Paparazzi golden coverage expanded from 4 to 13 UI surfaces / 46 snapshot variants.** Onboarding (permissions, pairing, HA), main screen chrome, dashcam picker, debug screen, all settings sub-screens. Each surface gains a stateless `internal` leaf (`SettingsRadarContent`, `MainScreenContent`, `DashcamPickerContent`, etc.) called from the snapshot test with stub state; production bodies forward to the leaves with no behaviour change.
- New JVM unit tests: `BleHandshakeReplyMatchTest`, `ReconnectBackoffTest`, `PhoneBatteryLogTest`, `OverlayDimLabelTest`, plus camera-light encoder and notify-parser tests.
- `/qc` skill updated: the UX reviewer now visually inspects every added or modified Paparazzi PNG in the diff before passing.

### Compatibility

- No migration. SharedPreferences, paired devices, HA credentials, and overlay positioning unchanged. New prefs (`overlay_opacity`, `radar_long_offline_threshold_min`, `radar_long_offline_cap_sec`, `auto_light_mode_enabled`, `camera_light_day_mode`, `camera_light_night_mode`) default to values that preserve prior behaviour.

## v0.5.1-alpha — 2026-05-04

### UX

- **Onboarding now asks whether you use Home Assistant before showing fields.** The HA step opens with a "Do you use Home Assistant?" chooser. Yes reveals the URL/token fields with a "Using HA" pill above them; No shows a Skipped card with a one-tap revert; the chooser stays put until you pick. Existing installs with saved credentials skip the chooser and land directly on the fields.
- **Onboarding polish across permissions, pairing, and dashcam picker.** Swipe-back returns to the previous onboarding step instead of exiting. Step 1 drops a duplicate granted-permission chip and re-labels the overlay badge from Optional to Recommended. Step 3's dashcam picker opens directly from the unanswered card and writes ownership on save, replacing a confusing two-tap flow; bonded radars hide the Open Bluetooth settings button; unbonded radars get a return-from-Settings cue.

### Reliability

- The forgotten-dashcam alert (the alarm that fires when the radar shuts off but the dashcam is still broadcasting from the bike) is now reliably loud. The alert plays on the system alarm channel, so a phone whose alarm volume is set low for sleep no longer produces a near-silent tone. While the tone plays, the app forces the alarm volume to its maximum and restores the prior level when the alert ends. If the OS denies the volume change (e.g. under Do Not Disturb), the tone still plays at the current level.

### Compatibility

- No migration needed. SharedPreferences, paired devices, HA credentials, and overlay positioning are unchanged. A new `ha_intent` preference defaults to "unset"; existing installs with saved HA credentials are treated as opted-in and skip the new chooser. The forgotten-dashcam alert temporarily forces the system alarm volume to maximum while the tone plays and restores the prior level when it ends.

### Internal

- `HaStepSnapshotTest` excluded from `:app:testDebugUnitTest` alongside `RadarOverlayViewTest`, restoring the green build on cold-cache JVMs (CI included). Both Paparazzi tests run via `:app:verifyPaparazziDebug` locally; the exclusion can drop when Paparazzi alpha05 ships.
- Paparazzi screenshot goldens added for the four HA onboarding step branches (unset chooser, Yes empty fields, Yes prefilled, No skipped).

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

- JVM test suite expanded to 288 Robolectric tests. New coverage: service and activity boot smoke; all 13 Compose screens composed synchronously; gate-matrix tests for `BootReceiver`, `InternalControlReceiver`, and `RemoteControlReceiver`; `HaClient` short-circuit guards; pipeline replay through decoder → detector → accumulator; `HaCredentials` round-trip.
- `HaCredentials` crypto extracted to a `Cryptor` interface (`AndroidKeyStoreCryptor` in production, `InMemoryCryptor` in tests). Removes the requirement for a real AndroidKeyStore in JVM tests.
- 11 Paparazzi screenshot tests for `RadarOverlayView`. Goldens cover: empty, single vehicle, close-approach danger border, multiple vehicles, mixed sizes, alongside-stationary hollow outline, battery-low badge, dashcam-missing/dropped icons, scenario replay label, and alert threshold line. No device required. Run locally with `:app:verifyPaparazziDebug` before pushing UI changes; CI runs the Robolectric suite only (Paparazzi's pre-release layoutlib loader is too unreliable on cold-cache JVMs).
- Build upgraded to Java 21 (required for Paparazzi 2.x layoutlib rendering engine).
- `targetSdk` bumped from 35 to 36 (Android 16). Robolectric pinned to SDK 35 via `app/src/test/resources/robolectric.properties` because Robolectric 4.14's SDK 36 runtime ships incomplete shadows.

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
