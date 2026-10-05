# Architecture

A map of how Bike Radar is put together, for contributors and reviewers. The
day-to-day build and test commands live in `AGENTS.md` and the CI gates in
`QUALITY_GATES.md`; this file covers the structure those commands operate on.

## The big picture

Bike Radar is a single Android **foreground service** with a **Compose-only** UI
(no fragments, no `Activity` beyond the settings/onboarding host). The service
runs for the length of a ride: it talks Bluetooth LE to the rider's rear radar
(and optionally a front camera/light and a Bosch eBike), decodes the radar's
vehicle stream, decides what the rider needs to know, and drives two outputs - a
thin on-screen overlay drawn on top of whatever app is in front, and audio cues.

Everything is designed so the parts that make safety decisions are **pure,
testable functions** with no Android or Bluetooth dependencies, exercised by the
JVM unit suite and a replay of recorded rides. The service is deliberately thin
glue around them.

```
 BLE devices state buses consumers
 ----------- ----------- ---------
 rear radar ──RadarLinkController──▶ RadarStateBus ──┐
 front cam ──CameraLightLinkController ├─▶ OverlayPipeline ─▶ RadarOverlayView (overlay)
 eBike ──EBikeStatusReader───▶ EBikeStateBus │ └▶ AlertBeeper (audio)
 batteries ──BatteryReader──────▶ BatteryStateBus ──┘
                                     ClosePassStateBus ◀── close-pass detection
                                     HaHealthBus ──▶ HaPublisher (optional MQTT)
```

## Composition: service shell + coordinators

`BikeRadarService` is the shell. It owns the coroutine `scope` and the warm
`AlertBeeper`, handles the foreground-service lifecycle, and dispatches each BLE
sighting (`scheduleRead` routes a sighting to the radar link, the camera link, or
a battery read). Everything else is a **single-responsibility coordinator**
injected at `onCreate`:

- `RadarLinkController` - the rear-radar BLE link: bond watch, reconnect loop,
  AMV enabling sequence (`EnablingSequence`), decode (`RadarV2Decoder`) into
  `RadarStateBus`, and the radar tail-light auto-mode.
- `RadarLinkCoordinator` - owns the rear-radar **link state** (`RadarLinkState`)
  and the walk-away / radar-drop safety state machine that watches it. Holds the
  single state flow so multi-field transitions are atomic against readers, and
  drives the dismount alarm and the dropped-radar cue. The controller reaches
  this state through a `RadarLinkStateGateway`.
- `CameraLightLinkController` - the front camera/light BLE link (optional
  accessory): reconnect, AMV handshake for the front variant, mode-state loop,
  and time-of-day light auto-mode.
- `BatteryReader` - one-shot GATT battery reads for radar/dashcam into
  `BatteryStateBus`.
- `EBikeStatusReader` + `EBikeSnapshotCoordinator` - a **read-only** GATT client
  that subscribes to the eBike's proprietary status stream (see below) and the
  cache/derivation on top of it (odometer baseline, ride-edge, climb detection).
- `OverlayPipeline` - the per-frame overlay/alert loop (see below).
- `HaPublisher`, `ServiceNotifications`, `CaptureLogManager`,
  `TurnSensorController`, `RideCheckpointCoordinator`, `KnownDevices` - HA
  publishing, notification channels, the opt-in capture log, the turn sensor, the
  crash-safe ride checkpoint, and the name↔MAC cache.

## State buses

The BLE producers and the overlay/HA consumers are decoupled by process-wide
singleton `StateFlow`s rather than IBinder plumbing: `RadarStateBus`,
`BatteryStateBus`, `ClosePassStateBus`, `EBikeStateBus`, `HaHealthBus`. A
producer publishes; any number of consumers collect. This keeps the BLE code
unaware of the overlay and vice versa, and makes each side independently
testable.

## The overlay/alert pipeline

`OverlayPipeline` owns the per-frame loop that consumes `RadarStateBus` +
`BatteryStateBus` + a tick flow and drives:

- the on-screen `RadarOverlayView` (a plain `Canvas` `View` added to a
  `TYPE_APPLICATION_OVERLAY` window) - attach/detach, per-vehicle state, and
  battery-low / dashcam-status badging;
- the `AlertBeeper` audio cues (tiered proximity beeps, the urgent
  imminent-impact cue, the all-clear chime); the radar drop/reconnect cues
  come from `RadarLinkCoordinator.evaluateRadarDrop` instead;
- close-pass detection (a state machine that emits an event, publishes it to HA
  when configured, and updates the ride tally).

## Deciders and derivers: the pure core

The actual judgement lives in small pure classes - `*Decider` / `*Deriver` - 
that take plain data and return a decision, with no Android or BLE dependency:

- `AlertDecider` - the close-pass beep tiers and the imminent-impact override.
- `BornCloseGate` - the ghost-beep filter (suppresses turn-sweep clutter born
  close, admits real closers).
- `TurnStateDecider`, `RadarDropDecider`, `WalkAwayDecider`, `ForgotToLockDecider`,
  `RideSummaryNotificationDecider`, the light/override deciders, and others.
- `RadarV2Decoder` - the vehicle-target stream decoder.

These carry the tightest test coverage in the project (a branch-coverage ratchet
on every `*Decider`/`*Deriver` plus the decoder), are replayed against a corpus
of recorded rides, and are where any behaviour change must add or update a test.

## BLE connection lifecycle

Two device classes share the AMV enabling sequence (`EnablingSequence`, with a
`DeviceVariant` selecting the rear-radar or front-camera UUID pair): the rear
radar and the front camera/light. `RadarLinkController` runs the rear link - 
bond watch → connect → handshake → subscribe to the V2 measurement stream →
decode into `RadarStateBus` - with a reconnect loop and an APK-reinstall
self-heal path (close + reopen the GATT). `BluetoothStateMonitor` tears the
links down if the adapter dies mid-ride and re-registers them when it returns.

The eBike link is strictly read-only: `EBikeStatusReader` connects out to the
bonded eBike and subscribes to the proprietary status-notify characteristic that
Bosch Flow already streams to every subscriber - it never writes the bike's
command channel and only works while Flow holds the link.

## Optional accessories

The rider may have only a radar: the front camera/light and the eBike are
optional. Every feature that consumes their state has a graceful no-accessory
path (fall back to the radar's own speed, or skip the feature), and the
no-camera / no-eBike paths carry their own tests. A missing eBike status service produces a
"no eBike status on this bike" path, never a crash.

## Home Assistant is optional

The overlay and audio work standalone. When configured, `HaPublisher` publishes
battery, ride-edge, and ride-summary data to the rider's own Home Assistant over
MQTT, and nowhere else. See `SettingsPrivacy` for the full disclosure of what is
sent.

## Accessibility scope

Bike Radar is a riding aid built around sight and sound: audio cues are the
primary channel (they work with the phone in a pocket or mounted in sunlight),
and the overlay is deliberately non-interactive - its window passes every touch
through so the app underneath stays usable, which also means screen readers
cannot land on it. Screen-reader support for the in-ride overlay is therefore
out of scope by design; the in-app screens (settings, onboarding) follow
platform conventions, keep icon-only controls labelled, and aim for WCAG AA
text contrast.

## Where to look

The table below maps each responsibility above to its file. The BLE wire
protocol is documented in the sibling `bike-radar-docs` repository.

## Key files

| Path | Role |
|------|------|
| `app/src/main/java/es/jjrh/bikeradar/BikeRadarService.kt` | Foreground-service shell + sighting dispatch + battery reads; coordinators injected at onCreate |
| `app/src/main/java/es/jjrh/bikeradar/RadarLinkCoordinator.kt` | Owns `_radarLinkState` + the walk-away/radar-drop transitions (markConnected/markDisconnected/tick/evaluate*); the `RadarLinkStateGateway` impl |
| `app/src/main/java/es/jjrh/bikeradar/RadarLinkController.kt` | Rear-radar BLE link: bond watch, reconnect loop, AMV handshake, decode->RadarStateBus, radar tail-light auto-mode (reaches the link state via `RadarLinkStateGateway`) |
| `app/src/main/java/es/jjrh/bikeradar/CameraLightLinkController.kt` | Front camera/light BLE link: reconnect loop, AMV (FRONT_CAMERA) handshake, mode-state loop, time-of-day light auto-mode (optional accessory; reads the radar off-time via an injected lambda) |
| `app/src/main/java/es/jjrh/bikeradar/BatteryReader.kt` | One-shot GATT battery reads (0x2A19) for radar/dashcam -> BatteryStateBus + HA; the in-flight cooldown. `scheduleRead` (in the service) owns the throttle and calls it |
| `app/src/main/java/es/jjrh/bikeradar/CaptureLogManager.kt` | Per-ride capture-log lifecycle (open/close/gzip/prune); opt-in |
| `app/src/main/java/es/jjrh/bikeradar/LinkProbe.kt` | Pure formatter and parser for the stored connection probe (discovered GATT table + abort token) the diagnostic bundle prints |
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
| `app/src/main/java/es/jjrh/bikeradar/RadarLinkStatus.kt` | Pure "is the app working the radar link right now", fed by the service-published link state; one input to `deviceLinkState` rather than a status of its own |
| `app/src/main/java/es/jjrh/bikeradar/ui/SafetyNoticeGate.kt` | Pure `startDestination` - where a rider belongs on launch. The notice outranks both other destinations; see AGENTS.md's Architecture note on why that ordering is the feature |
| `app/src/main/java/es/jjrh/bikeradar/ui/SafetyNotice.kt` | The riding-aid notice. ONE composable with three routes: the launch gate, the consent screen another app opens, and Settings -> About, where the same button closes the screen instead of storing the flag. Do not add a variant for any of them |
| `app/src/main/java/es/jjrh/bikeradar/ui/SystemRowVisibility.kt` | Pure `deviceLinkState` classifier - the ONE answer to "is this device delivering", read by the home card, both Settings surfaces and each device screen |
| `app/src/main/java/es/jjrh/bikeradar/ui/DeviceStatusLabels.kt` | The ONE word per state per device, in both languages. Gender is why radar / camera / eBike each get their own mapping; English collapses all three, so nothing in the en strings shows a mismatch |
| `app/src/main/java/es/jjrh/bikeradar/PermissionsSummaryDeriver.kt` | Pure permissions-row summary (all-granted / partial / action-needed) |
| `app/src/main/java/es/jjrh/bikeradar/BatteryChipLevel.kt` | Pure battery derivations: `batteryIsLow` (shared by the chip and the overlay marker), the chip's colour band, and `lowBatterySlugs` |
| `app/src/main/java/es/jjrh/bikeradar/RadarV2Decoder.kt` | V2 target-struct decoder (stateful) |
| `app/src/main/java/es/jjrh/bikeradar/EnablingSequence.kt` | AMV 04 handshake; `DeviceVariant` selects rear-radar or front-camera UUID pair |
| `app/src/main/java/es/jjrh/bikeradar/RadarOverlayView.kt` | Canvas overlay |
| `app/src/main/java/es/jjrh/bikeradar/AlertCue.kt` | Pure mapping from an `AlertDecider` event to the cue it sounds; the ride, the debug overlay and the sound demo all go through it |
| `app/src/main/java/es/jjrh/bikeradar/CuePlayer.kt` | The play-a-cue interface `AlertBeeper` implements, and `playCue`, the one place an `AlertCue` becomes a sound |
| `app/src/main/java/es/jjrh/bikeradar/SoundDemo.kt` | The example ride's scripted scene, run through a fresh `AlertDecider` at default settings so it cannot teach a sound the ride would not make |
| `app/src/main/java/es/jjrh/bikeradar/ui/SoundDemoStep.kt` | The example ride screen (onboarding and Settings), its screen-scoped beeper, and the volume-key routing both sound screens share |
| `app/src/main/java/es/jjrh/bikeradar/ui/AlertVolumeStep.kt` | The alert volume screen (onboarding and Settings) and the one Alert volume slider |
| `app/src/main/java/es/jjrh/bikeradar/ui/SettingsAlertSounds.kt` | Settings -> Alerts -> Alert sounds: each cue with its meaning, playable while the radar is off |
| `app/src/main/java/es/jjrh/bikeradar/ui/StatusClock.kt` | The one 5 s clock every status screen ticks from; reads on resume before its first wait |
| `app/src/main/aidl/es/jjrh/bikeradar/ipc/IRadarService.aidl` | The cross-app interface itself, and the only file a consumer compiles against; its KDoc is the consumer-facing documentation |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarContract.kt` | Cross-app wire contract: version, capability bits, size codes, light-mode values, bind strings, and the consent screen's action, extras and result codes. Permissive, and references nothing in the app |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarStateProjection.kt` | The projection from `RadarState`/`Vehicle` onto that wire; the half a consumer cannot use, which is why it is not in the contract |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarStateParcel.kt` | The only `Parcelable` on that contract; version leads, targets marshalled inline |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarVehicleParcel.kt` | One target as carried over the contract; a plain data class, not a `Parcelable` |
| `app/src/main/java/es/jjrh/bikeradar/access/RadarAccess.kt` | Who may read the stream and who may act on the hardware. The consent screen's WIRE is not here; it is `RadarContract.Consent`, so a consumer can copy it |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarIpcService.kt` | The exported bound service. A shell: binder lifetime, the frame feed, and re-checking grants when the store changes |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarIpcBinder.kt` | The contract implemented, and where every grant check lives. Listener registry, one live registration per package, revocation |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarOverlayGate.kt` | Which apps are asking for our overlay to be hidden. Held per package so a crashed consumer cannot leave the rider without it |
| `app/src/main/java/es/jjrh/bikeradar/ipc/RadarControlBridge.kt` | How the service reaches the live radar link for a tail-light write; install on connect, reset on teardown |
| `app/src/main/java/es/jjrh/bikeradar/CameraLightController.kt` | Front camera/light mode-set writes and notify parser |
| `app/src/main/java/es/jjrh/bikeradar/LocationCache.kt` | One-fetch-per-ride GPS cache for SunsetCalculator |
| `app/src/main/java/es/jjrh/bikeradar/RideLocationResolver.kt` | Pure location resolver for the light auto-modes (manual coordinates -> GPS -> London) + the coordinate input sanitize/parse/validate/format helpers |
| `app/src/main/java/es/jjrh/bikeradar/ScanGate.kt` | Pure accept/reject gate for an active BLE scan result (name-match AND bonded), used by the service's device discovery |
| `app/src/main/java/es/jjrh/bikeradar/EBikeStatusReader.kt` | Read-only GATT client subscribing to Bosch Flow's proprietary status stream |
| `app/src/main/java/es/jjrh/bikeradar/EBikeSnapshotCoordinator.kt` | Owns the eBike snapshot cache + derived state (odometer baseline, ride-edge + climb detection); fed by the status reader's callback |
| `app/src/main/java/es/jjrh/bikeradar/EBikeStatusDecoder.kt` | TLV decoder for the proprietary status stream (add new object IDs here) |
| `app/src/test/java/es/jjrh/bikeradar/RadarV2DecoderTest.kt` | JVM unit tests |
