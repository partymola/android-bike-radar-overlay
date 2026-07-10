# Architecture

A map of how Bike Radar is put together, for contributors and reviewers. The
day-to-day build/test/quality commands live in `AGENTS.md`; this file covers the
structure those commands operate on.

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
  AMV unlock handshake (`RadarUnlock`), decode (`RadarV2Decoder`) into
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
  imminent-impact cue, the all-clear chime, radar drop/reconnect cues);
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

Two device classes share the AMV unlock handshake (`RadarUnlock`, with a
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
path (fall back to a GPS-derived signal, or skip the feature), and the
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

The `Key files` table in `AGENTS.md` maps each responsibility above to its file.
The BLE wire protocol is documented in the sibling `bike-radar-docs` repository.
