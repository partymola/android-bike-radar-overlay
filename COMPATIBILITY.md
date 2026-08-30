# Device compatibility

*Not affiliated with or endorsed by Garmin, Bosch, or any vendor named below;
device names are used descriptively to state compatibility.*

The app implements the Garmin Varia V2 (bonded) BLE radar protocol, documented
in the companion [`bike-radar-docs`](https://github.com/partymola/bike-radar-docs)
repository, and falls back to the older V1 cleartext stream on a radar that
exposes no V2 characteristic at all. Compatibility follows from those two
protocols, so the matrix below is organised by what each device actually
speaks, not by marketing family.

Status legend: **Tested** = the author rides with it daily. **Expected** = same
protocol family and the app's device detection already matches it, but nobody
has confirmed it yet - a report either way is one of the most valuable
contributions you can make. **Limited** = the app can read the device's V1
stream, which carries range and nothing else: proximity beeps and the all-clear
work, the urgent warning, speed colours and close-pass logging cannot. **No** =
the device exposes neither protocol over Bluetooth.

## Rear radar

| Device | Status | Why |
|--------|--------|-----|
| Garmin Varia RearVue 820 | ✅ Tested | Primary development device |
| Garmin Varia RTL515 / RTL516 | ⚠️ Expected | Same BLE radar service family; name detection matches |
| Garmin Varia RVR315 | ⚠️ Expected | Same family (radar-only, no light) |
| Garmin Varia RCT715 | ⚠️ Expected | Same family (radar side only; the app never touches camera footage) |
| Garmin Varia eRTL615 | ⚠️ Expected | Same family (eBike-powered variant) |
| Garmin Varia RTL510 and older | ⚠️ Limited at best | Pre-BLE-V2 era. If the unit exposes the V1 stream over Bluetooth the app reads it, range only; an ANT+-only unit cannot connect. Unconfirmed either way |
| Non-Garmin radars (Bryton, Magene, Wahoo, Trek, Magicshine, Lezyne, CYCPLUS, Coospo, iGPSPORT, ...) | ❌ No | See below |

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
