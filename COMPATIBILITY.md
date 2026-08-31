# Device compatibility

*Not affiliated with or endorsed by Garmin, Bosch, or any vendor named below;
device names are used descriptively to state compatibility.*

The app implements the Garmin Varia V2 (bonded) BLE radar protocol, documented
in the companion [`bike-radar-docs`](https://github.com/partymola/bike-radar-docs)
repository, and falls back to the older V1 cleartext stream on a radar that
exposes no V2 characteristic at all. Compatibility follows from those two
protocols, so the matrix below is organised by what each device actually
speaks, not by marketing family.

In practice that splits the Varia range in two: the RearVue 820 speaks V2 and
gets every feature, and everything before it is expected to speak V1 and get
the range-only subset.

Status legend. **Tested** = the author rides with it daily. **Range only** =
the app is expected to read the device's V1 stream, which carries range and
nothing else. You get the approach beeps and the all-clear, but no urgent
warning, no speed colours and no close-pass logging. **No** = the device
exposes neither protocol over Bluetooth.

Everything below the 820 is unconfirmed on real hardware. A report either way
is one of the most valuable contributions you can make.

## Rear radar

| Device | Status | Why |
|--------|--------|-----|
| Garmin Varia RearVue 820 | ✅ Tested | Primary development device, and the only one confirmed to speak V2 |
| Garmin Varia RTL515 / RTL516 | ⚠️ Range only | Pre-820 generation, so no V2 stream expected. One filed report of a bonded RTL515 that never connected on 1.3.0; the V1 fallback in 1.4.0 is aimed at exactly this |
| Garmin Varia RVR315 | ⚠️ Range only | Pre-820 generation (radar-only, no light) |
| Garmin Varia RCT715 / RCT716 | ⚠️ Range only | Pre-820 generation (radar side only; the app never touches camera footage). RCT716 is the StVZO variant |
| Garmin Varia eRTL615 | ⚠️ Range only | Pre-820 generation (eBike-powered variant) |
| Garmin Varia RTL510 and older | ⚠️ Range only at best | Older still. If the unit exposes V1 over Bluetooth the app reads it; an ANT+-only unit cannot connect |
| Non-Garmin radars (Bryton, Magene, Wahoo, Trek, Magicshine, Lezyne, CYCPLUS, Coospo, iGPSPORT, ...) | ❌ No | See below |

**Why the 820 is the only one that gets everything.** V2 carries each vehicle's
lateral position, closing speed and size. Garmin announced vehicle size
detection and side-to-side movement as *new* on the RearVue 820. So the earlier
radars are not expected to send that data at all, whatever their firmware does.
Their published BLE payload is the older three-bytes-per-threat format, which is
V1.

This is a reasoned expectation, not a measurement. The only V2 device tested
here is the 820. A capture from any other model would settle it, and the Debug
screen records exactly what your radar offered.

**Why non-Garmin radars don't work:** there is no standard Bluetooth profile
for bike radars. The cross-vendor compatibility you see on bike computers is
the open **ANT+** Bike Radar profile - and phones don't have ANT+ radios. Over
Bluetooth every vendor speaks its own proprietary protocol (some, like the
Wahoo Trackr and Trek CarBack, send live radar data over ANT+ only). Supporting
another brand would mean independently working out that vendor's BLE protocol
with the physical device in hand.

**Radar not detected automatically?** If your Garmin radar is bonded but the
app doesn't recognise it by name, pick it manually under **Settings → Radar**
from your paired devices. If that makes it work - or fails in an interesting
way - please [open an issue](../../issues) with the device model; an optional
capture log (Debug screen) makes the report even more useful.

## Front camera / light

| Device | Status | Notes |
|--------|--------|-------|
| Garmin Varia Vue | ✅ Tested | Battery reporting + light mode control; footage stays on the camera |

## eBike (optional, read-only)

| System | Status | Notes |
|--------|--------|-------|
| Bosch Smart System | ✅ Tested | Passive subscription to the status stream while Bosch Flow holds the link; never writes to the bike |
| Bosch Classic / Active Line (pre-Smart System) | ❌ No | No BLE status stream |
| Shimano STEPS, Brose, Yamaha, Fazua, Giant, ... | ❌ No | Different protocols |

## Android

| Requirement | Value |
|-------------|-------|
| Minimum Android | 12 (API 31) |
| Target Android | 16 (API 36) |
| Tested on | Pixel 10 Pro XL, Android 16 |
| Needs | Bluetooth LE, overlay permission |

Reports from other phones and Android versions are welcome - especially
anything where the overlay, BLE reconnect, or audio behaves differently.
