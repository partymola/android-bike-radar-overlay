---
name: Bug report
about: Report a problem with the app
title: ''
labels: bug
assignees: ''
---

## What happened

A clear description of the bug.

## What you expected

What you expected to happen instead.

## Steps to reproduce

1.
2.
3.

## Environment

- App version (Settings -> About, or the release tag):
- Android version and phone model:
- Radar / eBike / front-camera hardware and firmware version (if relevant):
- Home Assistant integration in use? yes / no

## Diagnostics (optional, very helpful)

Long-press the "Bike Radar" wordmark on the home screen three times to
unlock Developer options, then open Settings -> Debug:

- **Copy diagnostic bundle** puts app settings, recent crash reports, the
  connection journal and what your radar's last connection attempt found on
  your clipboard - paste it here. It contains device names and ride times,
  but no location or account data. A run of four or more digits joined to a
  device name, often its serial number, is replaced with #. Read the bundle
  before you paste it.
- For decoding or alert problems, enable **Write capture logs**,
  reproduce on a ride, then Share the log from the same screen (stored
  gzipped under `Android/data/es.jjrh.bikeradar/files/captures/`).
  Capture logs record exact packet timing, so anyone holding one can
  work out when you rode - share only if you're comfortable with that.
