# Changelog

## v0.7.1-alpha - 2026-05-19

### Fix

- **Front-light auto-mode now uses the rider's actual location for sunrise / sunset.** The v0.6.0 release notes said the day-to-night transition was computed from the rider's location; the code actually had London's coordinates hardcoded (51.5074, -0.1278), so a rider in Madrid, Berlin, or anywhere east or west of London got the front light switching at the wrong time. This release reads `ACCESS_COARSE_LOCATION` once per ride session (triggered by the first BLE handshake of either the radar or the front camera, gated by a 60-minute staleness cache so quick stop-and-go reconnects do not pile up reads), feeds the lat / lon to the sunrise / sunset calculation, and uses the device's current time zone rather than Europe/London for the date arithmetic. City-block accuracy is sufficient for this calculation: a 10 km position error shifts the computed sunrise by about one minute, so `COARSE` rather than `FINE` is the appropriate permission grade. If the permission is denied the auto-mode falls back to London-hardcoded behaviour and a log line records the fallback - same outcome as the prior release, but now visible rather than silent.
- **Close-pass beeps now request audio focus and survive phone calls.** The close-pass beeper previously made no audio-focus request, relying on Android's default media-ducking for `USAGE_ALARM`. On some Bluetooth routes the duck was missed, and during a phone call (which holds focus EXCLUSIVE) the beep could be silenced at the speaker with no in-app indication. This release explicitly requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for each cue, holds focus across back-to-back beeps in the same burst (re-arming the abandon timer per cue), and abandons focus after the cue plus a 50 ms safety margin so media restores cleanly. The walk-away alarm path is unchanged - it already used the stronger EXCLUSIVE flavour and is appropriate for that case.
- **First beep after a BLE reconnect lands instantly.** `AlertBeeper` was previously allocated inside the per-radar-connection overlay coroutine and was discarded on every disconnect, so the first beep after a reconnect paid AudioTrack mixer cold-start latency (a perceptible delay on the first cue, gone after warm-up). The beeper is now allocated once in the service `onCreate` and lives until `onDestroy`. Volume and panning prefs are re-read on each radar reconnect (unchanged cadence from prior releases), so the experimental Settings toggle takes effect on the next reconnect.
- **Beep cues no longer fire during phone calls.** When `AudioManager.getMode()` is `MODE_IN_CALL`, all cue variants (Beep / Clear / UrgentApproach) skip the audio path entirely. The visual overlay still fires; the call audio stays clean. Non-negotiable behaviour, no Settings toggle.

### Features

- **Directional alert audio works on phone speakers in landscape.** v0.7.0 gated panning on headphone-class routes only and kept phone speakers centred, on the assumption that mm-scale speaker separation gave no usable lateralisation. That holds in portrait, but in landscape the earpiece (top of phone) and bottom-main are ~6-7 inches apart - plenty of stereo width for a directional cue. The app now reads display rotation and pans on the built-in speaker too: ROTATION_90 (USB-right) uses the same gain pair as headphones; ROTATION_270 (USB-left, flipped landscape) swaps the pair to compensate for the HAL's fixed audio-L-to-earpiece mapping. Portrait (ROTATION_0 / ROTATION_180) falls through to mono. Unknown routes also fall through to mono. Pinned by an exhaustive route-x-rotation-x-invert test matrix in `AlertBeeperPanTest`.

### Internal

- AudioTrack state changes and the `requestAudioFocus` call now run on a dedicated single-thread executor inside `AlertBeeper` rather than on the main coroutine dispatcher. Eliminates a small (5-30 ms) jitter window where overlay re-layout or Compose recomposition could delay an alert onset. Test executor injection added to the constructor so the eight new Robolectric tests run inline.
- Release workflow now defaults every tag to pre-release until the app exits alpha; the prior dash-based detection (e.g. `v0.5.0-alpha` auto-flagged, `v0.7.0` not) is removed.

### Compatibility

- minSdk unchanged at 31; targetSdk unchanged at 36. No prefs migration; the new `ACCESS_COARSE_LOCATION` permission stays denied on existing installs until the rider grants it via Android Settings → Apps → BikeRadar → Permissions. The hoisted `AlertBeeper` changes the lifecycle of an internal object, no externally visible API surface.

## v0.7.0 - 2026-05-18

### Features

- **Experimental directional alert audio.** Saves the rider a head-check on every alert: a beep arriving in the left ear means the threat is on the left. Pans Beep and UrgentApproach cues to the threat's lateral side via stereo gain, gated on a headphone-class output route (BT, BLE, wired, USB, hearing aid); phone speakers stay centred because mm-scale separation gives no usable lateralisation. A sub-row inverts L/R as a safety valve for mislabelled buds. Default off, behind a Settings -> Experimental toggle.
- **Earlier urgent warning via TTC.** The proximity-only gate fired at sub-1.2 s time-to-collision - well below the 2.8-4 s reaction window automotive forward-collision-warning systems use - leaving a stopped rider no time to react when a vehicle is closing fast at a junction. Added a second fire condition: TTC <= 2 s AND closing >= 6 m/s AND distance <= alert max. At 6 m/s closing this catches the same threats the proximity gate caught at 6 m, but earlier in the encounter (12 m / TTC 2 s). The 6 m/s closing floor mirrors the proximity gate's quantum-strict bound and filters slow-queue traffic merging into a stopped rider.

### UX

- **Closest-only beep model.** Beep cues describe the closest threat only. Same-tier new entries and lower-tier overtake re-acks are silent; per-track hysteresis stops intra-tier distance flap from re-firing. Cuts urban-traffic cacophony substantially.
- **Urgent fires within the reaction window.** The 2 s stationary-suppress dwell previously silenced the urgent tone during a rider's reaction window when decelerating into a junction with a closing vehicle (TTC at the imminent gate is sub-2 s). The override now uses a 500 ms mini-dwell - long enough to absorb radar speed noise, short enough that urgent fires within TTC. The 2 s dwell still gates ordinary-Beep suppression so rolling stops at junctions don't silence routine alerts.
- **Urgent repeats while threat held.** Removed the per-track latch that was silencing sustained held-imminent conditions. The override now fires every cooldown while the gate holds.

### Reliability

- **Walk-away alarm state machine.** Replaced the one-way `dashcamSeenSinceRadarOff` latch with a discrete IDLE / ARMED / BLANK intent-state machine. ARMED on radar disconnect, BLANK once the dashcam has been silent past the freshness window. BLANK only re-arms via radar power-on, not via dashcam advert - fixes a camera-on-before-radar moment firing the bike-ring alarm minutes after the previous ride.
- **Close-pass HA event entity.** Removed a `value_template` from the retained MQTT discovery payload that rendered every event to a bare string, causing HA's MQTT-event integration to drop the event with "No valid JSON event payload detected". The published event JSON already carries a top-level `event_type` matching the discovery's `event_types`, so no template is needed. The new APK overwrites the broken retained discovery on first publish.
- **Close-pass logging for slow and large vehicles.** Junction close-passes (rider braking with a vehicle still closing in the flank) and trucks during the first ~10 s of approach now log. Three connected changes: low-confidence radar returns (what trucks read as on initial acquisition) now map to CAR not BIKE; dropped the 4.25 m/s rider-speed floor in ClosePassDetector; dropped the unused BIKE vehicle size.

### Diagnostics

- **Decoder emits speedMs at native 0.5 m/s quantum.** The V2 decoder previously rounded byte[7] to Int, throwing away the radar's half-LSB. `Vehicle.speedMs` is now Float end-to-end so future trajectory-derived features (deceleration, dwell-time gates) get the full quantum. Safety-critical gates fire byte-for-byte the same at the integer thresholds; the only behavioural tightening is at the 1.0 m/s parked-vehicle cutoff, where a target at real 1.5 m/s closing is no longer flagged as parked.
- **Capture-log instrumentation.** WalkAway logs IDLE / ARMED / BLANK transitions; the alert path logs every non-None event alongside the same-frame closest in-front in-range vehicle. Lets one capture log follow the decision path of any WalkAway or AlertDecider issue without a separate replay. `closing_mps` field now emits Float.

### Internal

- CI bumps: `android-actions/setup-android` v4.0.1 (Node 24), `reactivecircus/android-emulator-runner` v2.37.0. Snapshot-test exclusion pattern broadened so newly-added `*SnapshotTest` classes are auto-excluded from CI without ad-hoc maintenance.

### Compatibility

- minSdk unchanged at 31; targetSdk unchanged at 36. No prefs migration; existing HA credentials and prefs carry over from v0.6.0 unchanged. The two new experimental toggles (directional alert audio + invert L/R) default off, so a rider upgrading sees the same audio behaviour until they opt in.

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
