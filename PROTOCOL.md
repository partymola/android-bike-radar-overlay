# Radar protocol (app-local summary)

This file documents the subset of the Garmin Varia radar BLE protocol that
**this app** actually depends on. It is a reader's guide for anyone opening
the code and asking "why does it do that?" — not a full protocol
reference.

The authoritative, vendor-neutral spec (with reference decoders in Python
and Kotlin, sample captures, and open questions) lives in the sibling repo:

**<https://github.com/partymola/bike-radar-docs/blob/main/PROTOCOL.md>**

When the two docs disagree, `bike-radar-docs` is canonical and this file is
out of date. Treat `bike-radar-docs` as the source of truth.

## Scope

Covers only what the app wires into:

- Two supported devices: Garmin Varia **RearVue 820** (rear radar + camera)
  and Garmin Varia **Vue** (dashcam, battery only).
- Verified on Pixel 10 Pro XL, Android 16.
- The V2 target stream (`6a4e3204`), the AMV 04 unlock handshake that
  enables it, and the standard BLE battery service on both devices.

Not covered here (see the canonical spec): the V1 cleartext stream layout,
firmware/DIS dumps, settings service semantics, other Varia models
(RTL515/516, Vue 870), Garmin's full command vocabulary.

## UUIDs used

Garmin shorthand: write the first 4 hex digits; the rest of the UUID is
always `-667b-11e3-949a-0800200c9a66`.

| UUID | Role | Direction | CCCD? |
|------|------|-----------|-------|
| `0x180f` / `0x2a19` | Standard battery service / level | READ | no |
| `6a4e2800` | Config / handshake service | — | — |
| `6a4e2811` | Handshake RX | NOTIFY | **yes, pre-handshake** |
| `6a4e2821` | Handshake TX | WRITE-NO-RESP | — |
| `6a4e2f00` | Control / settings service | — | — |
| `6a4e2f11` | Settings ACK | INDICATE | **yes, pre-handshake** |
| `6a4e2f12` | Settings notify | NOTIFY | yes, post-handshake |
| `6a4e2f14` | Settings indicate | INDICATE | yes, post-handshake |
| `6a4e3200` | Radar service | — | — |
| `6a4e3203` | V1 cleartext stream | NOTIFY | **never — see below** |
| `6a4e3204` | V2 target stream | NOTIFY | yes, post-handshake only |

Scan filter: the advert carries the 16-bit Garmin company UUID `0xfe1f`.
The Vue does **not** advertise any `6a4e2xxx` service, so the scan filter
has to match on `0xfe1f` to catch both devices (see `BatteryScanReceiver`).

### Critical: never subscribe the V1 CCCD

Subscribing the CCCD for `6a4e3203` signals the radar that the client is a
legacy V1-only peer and permanently suppresses V2 frames for the session.
The radar broadcasts `3203` cleartext regardless of any CCCD state, but
the app doesn't currently decode V1 — it only routes `6a4e3204` to the
decoder. The rule exists so that subscribing V1 doesn't accidentally kill
the V2 stream we rely on. See `Uuids.kt` for the permanent warning.

## CCCD subscribe order

Order matters. `RadarUnlock.kt` performs this sequence on every connect:

**Pre-handshake** (required for the handshake itself to work):

1. `6a4e2811` NOTIFY — handshake reply channel
2. `6a4e2f11` INDICATE — without this, the WRITE_REQ to `2f11` during the
   handshake returns error `0xFD`

**Run the AMV 04 handshake** on `6a4e2821` (TX) / `6a4e2811` (RX). See
below.

**Post-handshake** (only after a valid handshake reply is received):

3. `6a4e3204` NOTIFY — V2 target stream (the main event)
4. `6a4e2f12` NOTIFY
5. `6a4e2f14` INDICATE

**Never:** `6a4e3203` CCCD. See warning above.

## AMV 04 handshake

The V2 stream is gated behind a scripted challenge-response exchange on
`2821` (TX) and `2811` (RX). Message prefixes include session-dynamic
values (`pfxEnum`, `pfxCmd`) derived during the exchange; replies are
awaited between writes. Full recipe is in `RadarUnlock.UNLOCK`.

### APK-reinstall self-heal

`adb install -r` SIGKILLs the process without running `onDestroy`, so
Bluedroid retains a half-open GATT reference across the reinstall. The
fresh process reconnects, discovers services fine, but the AMV 04
handshake times out with the log line `# script: ABORT: AMV 04 reply never
arrived`.

On every handshake ABORT, `RadarUnlock.forceReconnect()` closes and
reopens the GATT and restarts the handshake once. Recovery fingerprint in
the capture log:

```
# script: ABORT
# gatt reopened
```

Without this self-heal a single APK reinstall wedges the connection until
the user toggles Bluetooth.

## V2 target stream (`6a4e3204`)

Packet layout: `[2-byte LE header] + N * [9-byte target struct]`.

Header bits:

| Bit | Meaning |
|-----|---------|
| `0x0001` | Status/ack frame, no target payload. Skip. |
| `0x0004` | Device-status frame, no targets. Skip. |
| anything else | Decode N target structs from body. |

### 9-byte target struct

| Byte | Type | Field | Notes |
|------|------|-------|-------|
| 0 | u8 | `targetId` | Radar-assigned track ID, stable across frames |
| 1 | u8 | `targetClass` | See `CLASS_*` constants in `RadarV2Decoder.kt` |
| 2 | u8 | `rangeY` low | Longitudinal distance within current 25.6 m zone, x0.1 m (0..25.5 m) |
| 3 | bits 0..2 | `rangeY` zone | 3-bit counter; zone = `((b3 & 7) + 1) & 7` |
| 3 | bits 3..7 | unknown | Change per packet; not correlated with range, class, x, or speed. Not decoded. |
| 4 | i8 | `rangeX` | Lateral offset, x0.1 m, -12.8..+12.7 m (positive = right) |
| 5 | u8 | length | Class template, x0.25 m. **Not** a real measurement |
| 6 | u8 | width | Class template, x0.25 m. **Not** a real measurement |
| 7 | i8 | `speedY` | Approach velocity, x0.5 m/s, negative = closing |
| 8 | u8 | `0x80` | Constant sentinel across all observed samples |

**True `rangeY`** = `zone * 25.6 m + (b2 * 0.1 m)`, giving 0..179.2 m in
25.6 m zones. This covers the 820's full ~175 m sensor range.

The old app pre-2026-04-21 read only `b2` and capped at 25.5 m, which made
cars beyond 25 m silently vanish from the overlay. See
[`RadarV2DecoderTest.kt`](app/src/test/java/es/jjrh/bikeradar/RadarV2DecoderTest.kt)
for the zone-wrap test cases.

### Speed derivation

The app ignores `byte[7]` and computes speed from frame-to-frame deltas of
`rangeY`. Reasoning: byte[7] is quantised at x0.5 m/s and we can get a
smoother estimate from `SPEED_DT_MIN_MS`/`SPEED_DT_MAX_MS`-windowed deltas.
See `RadarV2Decoder.kt` for the windowing logic.

## V1 stream (`6a4e3203`) — received but not decoded

The radar broadcasts V1 cleartext frames in parallel regardless of V2
state. The app currently does not decode them; only `6a4e3204` is routed
to the decoder. V1 frames are still observable in the capture log under
the `3203` tag — useful for offline analysis but not used live.

If a V2 handshake fails, the app today has no V1 fallback and the overlay
will stay empty. See `bike-radar-docs/PROTOCOL.md` for the full V1 layout
if you want to add one.

## Battery read (both devices)

Standard BLE Battery Service (`0x180f` / `0x2a19`).

Two read paths:

1. **Direct read** — opened on-demand from advert matches
   (`BatteryScanReceiver`). Used for both the Vue and the RearVue when no
   GATT is active. Throttled to one read per device per 5 min once an HA
   publish has succeeded; on publish failure we fall back to the 30 s
   attempt-cooldown so HA can recover without waiting a full throttle
   window.
2. **Piggyback** — while the radar GATT is up for V2, the app issues a
   periodic battery read through the existing connection every
   `BATTERY_PIGGYBACK_MS` (3 min). Avoids opening a second GATT just for
   battery when one is already live.

The rear radar also NOTIFIES `0x2a19` at ~5 s intervals once the CCCD is
enabled during the handshake. The app currently ignores those notifies;
see the v0.2 backlog in the triage notes.

## Capture log format

Every session writes a capture log to
`/sdcard/Android/data/es.jjrh.bikeradar/files/bike-radar-capture-<stamp>.log`.
Format:

```
<unix_ms> <char_tail_4hex> <hex_bytes_no_spaces>
# free-form comment lines start with '#'
```

The 4-hex char tag is `uuid[4..8]` — the meaningful nibbles, not the
shared `...9a66` suffix that all Garmin custom characteristics end in.

Retention: the service keeps up to `MAX_CAPTURE_LOGS` real captures on
disk. Files smaller than `MIN_USEFUL_LOG_BYTES` (sessions where the radar
never got past "connect attempt") are pruned as no-signal noise.

## See also

- **<https://github.com/partymola/bike-radar-docs/blob/main/PROTOCOL.md>** — full
  vendor-neutral spec with reference decoders and open questions.
- `app/src/main/java/es/jjrh/bikeradar/Uuids.kt` — the UUID constants.
- `app/src/main/java/es/jjrh/bikeradar/RadarUnlock.kt` — handshake recipe.
- `app/src/main/java/es/jjrh/bikeradar/RadarV2Decoder.kt` — V2 decoder.
- `app/src/main/java/es/jjrh/bikeradar/BikeRadarService.kt` — connection,
  capture log, battery scheduling.
- `app/src/test/java/es/jjrh/bikeradar/RadarV2DecoderTest.kt` — decoder
  unit tests, including the zone-wrap cases.
