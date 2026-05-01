# Changelog

## v0.4.0-alpha — 2026-05-01

### Security

- HA bearer token is no longer sent in cleartext to non-LAN hosts.
  HTTPS is required outside RFC1918, loopback, IPv6 unique-local,
  link-local, and `.local` / `.lan` hostnames.
- New **Clear HA configuration** button removes stored credentials.
- Pause and walk-away receiver actions moved off the exported
  broadcast surface; peer apps can no longer silently pause the
  overlay or dismiss the walk-away alarm.
- Build pipeline hardened: third-party GitHub Actions pinned to
  commit SHAs with weekly Dependabot upgrades, and CI write
  permissions narrowed to the release job.
- Dashcam MAC and display name redacted in the diagnostic bundle.
- Background BLE scans only trigger device reads on radars or
  dashcams already paired through Android Settings; stray peer apps
  cannot provoke unwanted GATT activity against unrelated devices.
- One-time share warning before sending a capture log: the log
  includes per-packet timestamps that can reconstruct ride timing.
- Radar decoder hardened against malformed input via fuzz tests
  (60 000 random byte arrays plus edge-shape and reset-mid-stream
  variants).

### UX

- Distinct **Bluetooth is off** state on the app's main screen
  with a "Turn on Bluetooth" prompt, separate from **Radar not
  paired**.
- Settings button moved to the bottom of the main screen.
- Two-column layout in landscape: status + close-passes on the
  left, system card + Settings on the right.
- Tighter copy on HA URL refusal and Bluetooth-state messages.

### Compatibility

- No migration steps. The HA close-pass MQTT payload still publishes
  `closing_speed_kmh` and `rider_speed_kmh`, the Settings slider
  stays in km/h, and stored preferences are unchanged. Internally
  the decoder switched from km/h to m/s; alert and close-pass
  thresholds were re-expressed at exact equivalents so alerting
  fires on the same radar readings as v0.3.0-alpha.

## v0.3.0-alpha — 2026-04-29

Earlier releases not retroactively documented.
