---
name: Hardware compatibility report
about: Tell us how the app behaves with your radar, camera/light or eBike
title: 'Hardware: '
labels: hardware
assignees: ''
---

<!-- Works or doesn't - both reports are valuable. The compatibility
     matrix is built from these. -->

## Hardware

- Device type: rear radar / front camera-light / eBike
- Brand and model:
- Firmware version (from the vendor app, if available):
- Bluetooth name as shown in Android's Bluetooth settings:

## What works

- [ ] Pairs in Android Bluetooth settings
- [ ] Appears under Settings -> Radar (or the dashcam picker)
- [ ] Connects (home screen shows Live)
- [ ] Vehicles appear on the overlay
- [ ] Alerts sound
- [ ] Battery level shows
- [ ] Light mode switching works (if it has a light)

## What doesn't

Describe anything that misbehaves, and roughly when (on connect, mid-ride,
after a drop...).

## Environment

- App version (Settings -> About):
- Android version and phone model:

## Capture log (optional, the most useful thing you can attach)

A short capture of the device connecting is usually enough to add
support or diagnose a decode problem. Long-press the "Bike Radar"
wordmark on the home screen three times to unlock Developer options,
then Settings -> Debug -> enable **Write capture logs**, power-cycle the
device so it reconnects, wait a minute, and Share the newest log from
the same screen. Capture logs record exact packet timing (so they
reveal when you rode) but no location or account data.
