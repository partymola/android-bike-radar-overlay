# Changelog

## v0.11.0-alpha - 2026-06-13

### Features

- **A dead-radar warning on the overlay.** When the rear radar drops
  mid-ride, the overlay strip goes blank - which reads like clear road. After
  about 10 seconds down, an amber "Rear radar disconnected" warning now marks
  the rear blind and clears the instant the radar returns. It's the only
  dead-radar
  signal a radar-only rider gets, and it's bounded so it can't sit on screen
  forever: a 30-second cap, with an opt-in "keep it on until the radar
  reconnects" toggle in Settings -> Alerts, and it stays hidden on a bench
  test where no traffic was ever seen. With a Bosch eBike it instead reads
  "Rear radar disconnected but bike unlocked" while the bike is unlocked,
  doubling as a forgot-to-lock hint.
- **A quiet post-ride summary, no Home Assistant needed.** When the radar
  has been silent for three minutes, the app posts a quiet notification
  recapping the ride - overtakes, close passes, distance, alerts per km, and
  the tightest pass - and a paired watch shows it too. This used to exist
  only as a Home Assistant sensor. A bench connect that wasn't really a ride
  stays silent.
- **Close passes are now counted on the phone, with a ride history - no
  Home Assistant needed.** The home-screen count, and the close-pass
  detection behind it, used to switch on only when Home Assistant was
  configured - so a rider without HA saw a permanent zero. Detection now
  runs whenever you turn on close-pass counting in Settings -> Alerts, and
  tapping the close-pass card opens a new Ride history screen with a per-ride
  log: distance, overtakes, close passes, and how close and how fast the
  closest passes came. It all stays on the phone - no location, no route -
  and Home Assistant, if you use it, is just an extra place the same data is
  sent.
- **Urgent warning now fires when you're moving slowly, not only when
  stopped.** The imminent-impact tone used to arm only once you were
  essentially stopped, so a vehicle bearing down as you slowed into a
  junction got ordinary beeps until standstill. It now also arms at or below
  15 km/h, with a higher closing-speed bar on the moving path so a normal
  overtake at walking pace doesn't trip it. Validated against 105 recorded
  rides. On by default, in Settings -> Alerts.
- **Forgot-to-lock wrist reminder (Bosch eBike).** Walk away from the bike
  while it's still unlocked and out of radar range, and your watch buzzes
  once - the walk-away alarm stays silent when the bike is unlocked, so this
  covers that gap. eBike only, on by default, toggle in Settings -> eBike.
  The app can't lock the bike for you, so it's a reminder only.
- **Pin a radar the app doesn't recognise by name.** Settings -> Radar now
  lists any paired Bluetooth device under "My radar isn't listed", so a rear
  unit whose name the app's matcher doesn't recognise can still be selected
  and tracked.

### Fix

- **Home Assistant picks up new credentials without an app restart.** Saving
  a new HA URL or token mid-session used to leave close-pass and
  front-light-mode publishes failing silently until you restarted the app.
  They now switch to the new credentials immediately.

### Diagnostics

- **Crashes are now visible without a computer.** If the app crashes it
  flushes the last of the capture log to disk first, keeps a running count of
  unclean restarts so a silent crash-or-kill loop becomes noticeable, and
  lists crash reports on the Debug screen with share and delete. The
  diagnostic bundle includes the newest report; the Privacy screen discloses
  that crash reports are stored on the phone.
- **An always-on connection log for both Bluetooth links.** The app now
  keeps a small record of when the radar and front camera connect, drop, or
  fail to connect - on the Debug screen and in the copy-diagnostic bundle.
  Unlike the opt-in capture log it is always on and survives across rides,
  so a "why didn't it reconnect yesterday?" question has an answer. Device
  names and times only; no ride data, no location.

### Compatibility

- minSdk unchanged at 31; targetSdk unchanged at 36. No change to the BLE
  protocol.
- **Home Assistant ride-summary sensor is now per-ride.** It reports the
  current ride's numbers and resets on a new ride, instead of a running total
  since the service started. Existing subscribers keep working; the values
  just reset per ride. No topic or schema change.
- Two alert behaviours default on: the urgent cue now also fires below
  15 km/h (turn off in Settings -> Alerts), and the overlay shows a
  dead-radar warning when the rear radar drops. Riders without
  Home Assistant will also now see real close-pass counts where the card
  previously stayed at zero - turn close-pass counting on in Settings ->
  Alerts. No migration needed.

### Internal

- The branch-coverage gate now holds every safety decider to its floor by
  wildcard, instead of a hand-maintained list that silently exempted deciders
  added after it was written. The radar/camera name-matching heuristic is
  centralised in one place; it was duplicated across several screens that
  could drift apart.
- Continued splitting the foreground service into single-responsibility,
  unit-tested coordinators (the rear-radar and front-camera links, one-shot
  battery reads, the walk-away alarm, notifications, the shared reconnect
  backoff), with no behaviour change. A guarded service-shutdown path and a
  corpus-replay test gate for alert-behaviour changes were added.
- A hardware-compatibility issue template and a code of conduct were added,
  fastlane store metadata (en + es) and an Obtainium install section landed,
  and the Privacy screen now discloses the on-phone ride history and
  connection log. Plus the usual test and documentation refreshes.

## v0.10.0-alpha - 2026-06-07

### Features

- **Bike Radar ya está disponible en español.** Está totalmente traducido:
  si tu teléfono está en español, se muestra en español automáticamente.
  Para usar solo esta app en español con el teléfono en otro idioma, usa los
  ajustes de idioma por aplicación de Android (13 o posterior).

  The app is now fully translated to Spanish: a Spanish-language phone shows
  it in Spanish automatically, or use Android 13+'s per-app language setting
  to run just this app in Spanish. The text lives entirely in Android string
  resources, so adding another language is a `values-<code>/strings.xml`
  translation with no code changes - see `CONTRIBUTING.md`.

### UX

- **Tighter wording across the app.** A copy pass trimmed Settings and
  status text to say what you get rather than how it works.

### Diagnostics

- **Uncaught crashes are recorded to app-private storage.** If the app ever
  crashes, the stack trace is written to a local file so a bug report can
  say what happened instead of losing it. The handler installs once per
  process and chains the platform default, so nothing else is suppressed.

### Reliability

- **The dashcam battery probe backs off when the camera is off.** It no
  longer retries on a fixed cadence against a dashcam that isn't
  broadcasting, which was waking the Bluetooth stack needlessly and
  contending with the radar link.

### Compatibility

- minSdk unchanged at 31; targetSdk unchanged at 36.
- No change to the Home Assistant payloads or the BLE protocol.

### Internal

- Continued splitting the foreground service into focused, unit-tested
  coordinators (capture-log lifecycle, Home Assistant publishing,
  notification channels, the known-device cache), with no change to
  behaviour. Plus the usual test-coverage, CI, and documentation refreshes.

## v0.9.0-alpha - 2026-06-02

### Features

- **The rear radar's tail light now follows sunset like the front light does.** On every connect the radar tail light switches to your configured day mode before sunset and your configured night mode after, using your local sunrise/sunset, with a one-shot switch scheduled for dusk or dawn during the ride. Set both modes in the consolidated Settings -> Light auto-mode screen; it's independent of the front-light auto-mode and off unless you turn it on.
- **More of your eBike's data is read from Bosch Flow.** On top of the speed, cadence, rider power, battery and odometer added last release, the app now also decodes motor power, assist mode, and wheel circumference, plus the bike's lock and wheel-at-rest state. Still strictly read-only; nothing is written to the bike. Needs a Bosch Smart System eBike with Flow running; without one, nothing changes.
- **Your radar is now pinned to this bike by address.** If you have more than one Varia bonded - a second bike, a spare unit - the app could previously stream from whichever one answered first. It now remembers this bike's radar by its Bluetooth address and won't connect to a different one, even if both are in range. A new Settings -> Connections -> Radar screen shows the linked radar, its battery, and a "Pair a different radar" action when you do want to switch.
- **Close-pass beeps escalate immediately when a pass gets more dangerous.** The beep cooldown that prevents cacophony used to hold even when a vehicle moved into a closer, more urgent tier. Now a tier increase beeps straight away, so a fast-closing overtake isn't muted by a cooldown started for a gentler one.
- **A single beep when the rear radar reconnects after dropping.** After the app warns you the radar link died mid-ride, silence is ambiguous - back, or still gone? One short acknowledgement beep on reconnect restores silence's meaning. It only fires if a drop was actually announced first.
- **Alarm-rate metrics in the ride-end Home Assistant summary.** If you publish to Home Assistant, the end-of-ride summary now includes alerts-per-km and alerts-per-hour-of-ride (close-pass alarm cues only), so you can chart how noisy your alerts are over time. Publish-only, post-ride; no in-ride change.

### UX

- **Settings reorganised around how often you touch things.** The front and rear light controls are merged into one Light auto-mode screen, and the Settings home is grouped into Ride / Connections / System with a Quick Status card showing radar and camera battery at a glance. Most-changed settings sit at the top; one-time setup at the bottom.
- **Manage your capture logs from the Debug screen.** Each saved log now has a delete button, and there's a "Delete all" with a confirmation, so you can clear the ride-tracking-grade logs off your phone without reinstalling. The currently-recording log is always kept.

### Security

- **Capture logging is now off by default.** The per-ride log (radar packets, BLE traffic, eBike telemetry with exact timing) is no longer written unless you opt in on the Debug screen. Existing behaviour for people who want it is one toggle away; everyone else stops generating ride-tracking-grade files silently.
- **Your location no longer reaches the system log.** The front-light auto-mode used to write your coarse GPS fix (to ~1 km) into Android's logcat, which other apps with the right permission could read. It now logs only whether a fix was used, never the coordinates.
- **Capture-log storage hardened.** Log retention and the file-share scope were tightened so the share sheet exposes only the logs, not the rest of the app's files.

### Fix

- **Close-pass beeps survive a momentary radar blip.** A single dropped radar frame could reset a tracked vehicle and silence its beep. A stable track now keeps going across one missing frame.
- **The close-pass counter can't lose a count.** Concurrent updates to the overtake tally are now atomic, so a close pass is never dropped from the count or the Home Assistant summary.

### Reliability

- **Reconnect attempts no longer thunder together.** The radar, camera, and eBike reconnect loops now jitter their backoff so they don't retry in lockstep and overload the phone's Bluetooth stack after a multi-device dropout.
- **The screenshot capture no longer crashes the service on an early exit.** A foreground-service start-contract edge that could kill the app mid-capture is fixed.
- **Saved capture logs take far less space.** A ride's capture log is now gzip-compressed when it's finalised (`.log.gz`), and older uncompressed logs are migrated opportunistically, so the on-phone footprint is much smaller. Only relevant if you've turned capture logging on.

### Diagnostics

- **Near-miss logging for the radar-drop and eBike-freshness gates.** When the radar drops but a cue is held back (or the eBike snapshot has gone stale), the app records why to the capture log, so the freshness thresholds can be tuned from real rides.
- **Debug toggle to log unmapped eBike status fields.** Helps pin the remaining Bosch Flow status object IDs from a real ride; off by default, dev-only.

### Compatibility

- minSdk unchanged at 31; targetSdk unchanged at 36.
- The two new ride-summary metrics (alerts-per-km, alerts-per-hour-of-ride) are additive fields on the existing Home Assistant ride-summary payload and appear as two auto-discovered entities; existing subscribers keep working unchanged.

### Internal

- Extracted the overlay pipeline and the radar-link state cluster out of the foreground service, and routed the front-camera light auto-mode through the same shared, unit-tested decider as the radar light. Plus the usual test, screenshot, and doc refreshes.

## v0.8.0-alpha - 2026-05-26

### Features

- **Live eBike data on the home screen.** If you have a Bosch Smart System eBike paired and the Bosch Flow app running on the same phone, the app now reads your bike's speed, cadence, rider power, battery charge, and odometer while you ride. A status row shows Live (green) or Waiting for Flow (amber). A new Settings -> eBike screen has the on/off toggle, the connection state, and a one-tap Open Bosch Flow shortcut. Read-only: nothing is written to the bike. Default on if you said you own a Bosch eBike during onboarding; without the eBike the rest of the app works the same as before.
- **Dedicated eBike step in onboarding.** Pairing your Bosch eBike now has its own onboarding card and a top-level Settings -> eBike screen, instead of being buried in the Experimental panel. The card shows Live / Waiting for Flow / Bosch Flow not installed at a glance, with an Install / Open / "Receiving data" button that adapts to what's needed next.
- **Close-pass beeps keep firing when you're grinding up a hill.** If you have a Bosch eBike paired: rider power at 250 W or more sustained for 30 seconds flips you into a "climbing" state, which keeps close-pass beeps firing even when you're moving slowly. Before, the stationary-rider gate could silence alerts at 5 km/h on a climb, exactly when you're most exposed to overtaking traffic. Without an eBike the existing behaviour is unchanged.
- **Beep cooldown adapts to how fast you're going.** Below 15 km/h the gap between beeps doubles so traffic hovering at a tier boundary doesn't cause a flapping-beep cacophony. Above 25 km/h the gap halves so you get re-warned within the shorter reaction window. Urgent-impact beeps for an imminent threat are unaffected and still fire at the base rate. Uses eBike wheel speed if you have one paired, otherwise the radar's own bike-speed reading.
- **In-ride beep when the radar's battery hits critical.** The rear radar is your only rear-traffic awareness, so a critical radar battery is the one battery state that earns an in-ride sound. Fires once when the radar drops below 10%, then no more than once every 2 minutes while it stays critical. Radar only (never the dashcam). A 520 Hz slow two-tone that sounds nothing like the threat beeps or the all-clear, so you won't confuse it. All other battery warnings still wait for the ride summary.
- **Low-battery heads-up at connect.** A single low-battery beep per device (radar and dashcam) at connect time if either is below the warning threshold, capped at once per 30 minutes. Gives you a chance to top up the charge before heading out instead of finding out mid-commute.
- **Directional alert audio is louder on the threat's side.** The experimental directional cue used to nudge the quiet side by about 3 dB - too subtle to tell apart on phone speakers six or seven inches apart. The pan is now full: the opposite channel goes silent. Still experimental, still default off, found under Settings -> Experimental.

### UX

- **Overlay battery and dashcam icons are bigger and easier to glance at.** The bottom-row camera icon and battery labels were 22dp and 11dp, too small to read at arm's length on a moving bike. Now 28dp and 14dp, with a slightly larger low-battery dot. Still at the bottom of the overlay so they can't compete with live threats for your attention.
- **Onboarding buttons are now easy to hit with gloves on.** Device-row actions, Test connection, Open Flow, Pick device, and the selected-device pill were between 36 and 40dp tall. All now meet Android's recommended 48dp minimum.
- **The overlay works with TalkBack.** Screen readers used to skip the radar overlay entirely. It now announces what's on screen: clear road, vehicle count and nearest distance, and active warnings. The wordmark in the corner no longer makes TalkBack falsely announce "double-tap to activate" for a non-existent action.
- **"Front light" renamed to "Dashcam light" in Settings.** This setting was always controlling the dashcam's built-in light, not your bike's primary front light. The new label matches what the app already calls the device and won't be mistaken for your real bike light. Settings rows, headers, and the light-fail notification all updated; the behaviour is unchanged.
- **Privacy screen now lists every Home Assistant entity the app can publish to.** The in-app Privacy notice and the per-step onboarding summaries name everything the app can push to your HA instance: radar and dashcam batteries, dashcam-light mode, the close-pass event entity, and the end-of-ride summary (distance, close-pass count, closing speeds, lateral clearances). The Permissions screen now also explains why the app asks for coarse location, and warns that dashcam-picker screenshots include whatever else is on screen.
- **Experimental row subtitle now lists everything you've turned on.** It used to mention only the precog toggle, so turning on directional panning still showed "All off" - making it look like the setting hadn't taken effect. Both toggles are now summarised.
- **Licences screen cleanup.** Removed a Security Crypto entry that named a library the app doesn't actually use.

### Fix

- **Close-pass beeps no longer go silent after a radar reconnect.** Until this release, the first time the radar dropped and reconnected mid-ride, every close-pass beep stopped working until you restarted the app. Fixed.
- **No more "beep, clear, beep" flapping at the edge of the alert range.** A car sitting right at the edge of the close-pass distance could trigger a beep, fire a premature all-clear, then beep again when the radar briefly lost it. The all-clear now waits a full second of empty road before firing, and it's cancelled the moment a vehicle reappears.
- **Directional panning no longer crashes the app on Android 16.** On Android 16, the panning code was hitting an Android API change that crashed the foreground service mid-beep. The phone would then fail to restart the service and the app would be stuck until reboot. Fixed at the source so panning is safe on every supported Android version.
- **Bosch eBike pairing instructions and "Open Flow" both work properly now.** The in-app pairing guidance referenced an old Flow menu path; it now matches what your bike actually shows (Components -> Add new device -> Accessories). The phone now identifies itself by name during pairing so you can pick the right device, and "Open Flow" reliably opens Bosch Flow instead of falling back to the Play Store.
- **Walk-away alarm now uses your phone's alarm tone.** The bundled alarm sound had a licence that's incompatible with this app's GPL licence and couldn't ship. The alarm path now plays whatever you've set as your alarm tone, still at maximum alarm volume so it still cuts through. Only the sound itself changes.
- **End-of-ride summary sensors no longer break Home Assistant.** When a ride had no close-pass data, the summary entities were being rejected by HA instead of showing as Unknown. Existing entities recover on the first publish after upgrade.

### Security

- **Closed an adb-only debug entry point against peer apps.** A receiver used for scenario replay and synthetic-frame injection during development was exposed to other apps on the phone whenever you had developer mode unlocked. It's no longer reachable from other apps. Adb access from your computer is unaffected.
- **Home Assistant token is no longer baked into release APKs.** If you built a release APK yourself with a token configured in `local.properties`, that token was embedded in the APK as readable text. Release and onboarding-test APKs are now built without any embedded token; only debug builds keep the convenience seeding. If you installed a release APK from a previous tag and entered your HA token through the in-app settings screen, you're unaffected; rebuild and reinstall if you want to drop any embedded copy from your own build.

### Compatibility

- **minSdk unchanged at 31; targetSdk unchanged at 36.** No devices left behind.
- **The app now asks for coarse location through the standard Android prompt.** Previous releases declared the permission but never asked, so existing installs stayed denied and the dashcam light's sunrise/sunset auto-mode silently fell back to London times. Onboarding and Settings -> Permissions now prompt for it, and the Dashcam light screen shows an inline grant prompt if auto-mode is on but location is denied. Optional: the London fallback still works if you skip it.
- **eBike toggle setting carried over transparently.** The internal name for the eBike on/off toggle changed in this release; your existing setting is preserved automatically. No action needed and no settings reset.
- **New Home Assistant MQTT topic when the eBike feature is on.** Ride-start and ride-end events publish to `varia/ride/edge` (plus a retained `varia/ride/edge/last`). HA subscribers and dashboards will see new traffic; existing topics are unchanged. If the eBike feature is off, nothing is published to the new topic.
- **Release APK is dramatically smaller (~7 MB, down from tens of MB).** Release builds now strip dead code and unused icons. No action needed; install over the top and everything works the same.
- **Internal compatibility tweaks for older and newer Android versions.** A few internal calls (debug overlay foreground-service typing on Android 12-13, the radar Bluetooth setup write and the walk-away vibration call on Android 13 and up) were updated to match the right Android API for the version they run on. No rider-visible change.

### Diagnostics

- **Capture log records every cue that actually sounded.** A new line in the capture log marks each beep, all-clear, urgent-impact, and radar-critical cue at the point it was actually played - after the phone-call mute check - so a post-ride log shows what you actually heard, not just what the decider intended. Makes wrong-time-beep reports traceable from the log alone.

### Internal

- **Screenshot suite migrated from Paparazzi to Roborazzi.** Paparazzi 2.0.0-SNAPSHOT's layoutlib loader failed on cold-cache JVMs, so the snapshot tests had been excluded from `testDebugUnitTest` and never ran in CI; regressions only surfaced on a manual local record. Roborazzi 1.63.0 renders via Robolectric Native Graphics, which works cold-cache; the 71 goldens now verify inside `testDebugUnitTest` and in CI. Compose tests use the `captureRoboImage` lambda; the detached overlay view draws to a bitmap.
- **Coverage gated with a ratchet on JaCoCo's on-the-fly agent.** AGP's offline `enableUnitTestCoverage` cannot see classes loaded through Robolectric's sandbox classloader and silently reports 0%, so the project switched to the on-the-fly agent with `isIncludeNoLocationClasses = true`. `:app:jacocoCoverageVerification` now wires into CI and `/qc` with a project LINE floor of 0.55 and a 0.93 BRANCH floor on the safety-critical deciders. A `doFirst` guard fails the gate if the class tree or exec data is empty so a hollow pass cannot wave untested code through.
- **ktlint wired into CI with an empty baseline.** `:app:ktlintCheck` runs on every push under the `intellij_idea` style declared in `.editorconfig`; the codebase was reformatted in one sweep and the baseline kept empty so any new finding is a hard failure. `:app:ktlintFormat` autofixes most issues.
- **Test coverage expanded across the ride-critical surface.** New JVM unit tests cover the GATT op-queue and handshake, the battery-scan match-and-forward gate, the eBike disconnect and pairing edges, the connect-time day / night light decision, `LocationCache`'s permission gate, `AndroidKeyStoreCryptor`'s decrypt-guard contract, `Prefs` clamps and Flow emissions, the protocol decoders + UUID wire contract, `AlertDecider`'s reachable branches, event-to-cue mapping, the HA HTTP publish / ping / probe paths and credentials property setters, the state buses, and the dashcam-light Compose screen with auto-mode on.
- **Privacy disclosure fail-fast tests.** A `DataDisclosure` anchor in `HaClient` plus `scripts/privacy-disclosure-check.sh` (also wired into `/qc`) break the build if the privacy screen drifts from the actual MQTT egress or the manifest permission set.
- **Pure-function extractions to lift logic into the JVM tier.** The stereo interleave used by the panner, the eBike disconnect and pairing edges, and the eBike connection-trust decision moved out of service / coroutine context into pure functions with their own tests. Audio panning now mixes into pre-built stereo buckets and drops `setStereoVolume`. No behavioural change; coverage that previously needed instrumentation now runs as plain unit tests.
- **`scripts/dev` warm-daemon wrapper.** A persistent build container brought up via `scripts/dev up` keeps the Gradle and Kotlin daemons warm across invocations; a typical `gradle` call drops from ~2 s to ~0.4 s. Configuration cache, build cache, and parallel execution are enabled across the build. `scripts/dev gradle ...` transparently falls back to the one-shot Docker pattern when the container is not up.
- **Channel-neutral naming across the eBike code path.** Classes, prefs keys, capture-log markers, broadcast intent actions, and KDoc match the read-only GATT-client design. Internal-only rename; no externally visible behaviour change.
- **Overlay micro-perf cleanups.** The overlay collect loop no longer re-reads `SharedPreferences` ~6x a frame or samples the phone-battery sticky broadcast on every 2 s tick; a `PrefsSnapshot` kept fresh by a `prefs.flow` collector replaces both, and the battery is read at most once per 60 s. The `dp()` helper caches display density instead of resolving it per call. The capture log flushes on a 1 s timer rather than per line.
- **AGENTS.md refreshed for new contributors.** New sections document the Roborazzi screenshot-test patterns, the JaCoCo on-the-fly rationale and coverage floors, the eBike / Flow connection-trust gotchas, the `<queries>` manifest entry that keeps Open Flow working on Android 11+, the IEC 60601-1-8 conceptual framing for the alert-audio design, and the three-tier quality-gate flow (pre-commit hook -> `/qc` -> `/release-review`).
- **Repository hygiene additions.** GitHub issue and PR templates, a `SECURITY.md` policy, and the full GPL-3.0 license text shipped at the repo root. The provenance keyword-scan in CI was narrowed to phrases that would genuinely leak how the protocol was obtained.
- **README and screenshot grid refreshed for v0.8.0.** New Features section, eBike Settings shot added at slot 06, and the visibly-changed shots re-captured against the current build.

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

- minSdk unchanged at 31; targetSdk unchanged at 36. No prefs migration; the new `ACCESS_COARSE_LOCATION` permission stays denied on existing installs until the rider grants it via Android Settings -> Apps -> BikeRadar -> Permissions. The hoisted `AlertBeeper` changes the lifecycle of an internal object, no externally visible API surface.

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

## v0.6.0 - 2026-05-07

### Features

- **Front-camera light auto-mode.** The app now drives a paired front-camera light: chosen mode follows sunrise / sunset times computed from the rider's location, with manual-override detection so a rider's button press doesn't fight the app's mode choice. New Settings screen exposes day-mode / night-mode pickers and the auto-mode toggle. Mode state publishes to Home Assistant as `sensor.varia_<slug>_front_mode`. Verified on firmware 5.80.

### UX

- **Overlay dimmer slider.** A new Settings -> Radar & alerts -> Overlay section gathers Visual distance and a 4-stop "Off / Light / Medium / Strong" overlay dimmer. Visual distance moved out of the Alerts section since it controls the overlay's render cutoff, not audio alerts. The dimmer multiplies the View's alpha on top of the existing per-paint alphas: "Off" (the default) preserves the prior look; lower stops dim the overlay further so an underlying map or navigation app shows through.
- **Configurable reconnect-backoff long-offline tier.** When the radar has been out of range past a user-set threshold (default 30 min), the reconnect interval relaxes to a user-set cap (default 30 s) so the BLE stack idles overnight instead of hammering GATT opens at the steady-state 8 s ceiling. New "Connection" section in Settings exposes both knobs.

### Reliability

- **Front-camera handshake more robust against spurious notifies.** The sub-mode-toggle reply matcher now requires the full `41 4d 56 18` AMV-signature-plus-opcode at bytes 8-11 (previously only byte 10 + byte 11), so a stray notification with coincidental bytes at those offsets can no longer be mistaken for the genuine reply.
- **Front-camera handshake adapted to firmware 5.80.** The 13-byte cmd-16 reply on this firmware carries no `pfxCmd` byte; the device-ID push frame the rear radar emits is absent on the camera. The handshake now terminates cleanly after the `0x18` sub-mode toggle's third reply rather than waiting indefinitely.

### Diagnostics

- **Phone-battery trace logged into per-ride capture log.** Battery level (percent), temperature (decicelsius), and charging state are sampled on every level change and on a 60 s heartbeat. Read from the cached sticky broadcast - no continuous receiver, no extra wake-ups. Cross-references with radar / dashcam / handshake events in the same log for post-ride battery analysis. `dumpsys batterystats` remains authoritative for per-uid mAh attribution.

### Internal

- **Paparazzi golden coverage expanded from 4 to 13 UI surfaces / 46 snapshot variants.** Onboarding (permissions, pairing, HA), main screen chrome, dashcam picker, debug screen, all settings sub-screens. Each surface gains a stateless `internal` leaf (`SettingsRadarContent`, `MainScreenContent`, `DashcamPickerContent`, etc.) called from the snapshot test with stub state; production bodies forward to the leaves with no behaviour change.
- New JVM unit tests: `BleHandshakeReplyMatchTest`, `ReconnectBackoffTest`, `PhoneBatteryLogTest`, `OverlayDimLabelTest`, plus camera-light encoder and notify-parser tests.
- `/qc` skill updated: the UX reviewer now visually inspects every added or modified Paparazzi PNG in the diff before passing.

### Compatibility

- No migration. SharedPreferences, paired devices, HA credentials, and overlay positioning unchanged. New prefs (`overlay_opacity`, `radar_long_offline_threshold_min`, `radar_long_offline_cap_sec`, `auto_light_mode_enabled`, `camera_light_day_mode`, `camera_light_night_mode`) default to values that preserve prior behaviour.

## v0.5.1-alpha - 2026-05-04

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

## v0.5.0-alpha - 2026-05-03

### Features

- **Live close-pass count on the home screen.** The stats card now shows the actual count of close-pass events for the current ride instead of a permanent zero. Resets when the service starts.
- **Per-ride summary published to Home Assistant.** Ten new MQTT-discovery sensors per radar device - HA derives the entity IDs from the display names: `overtakes`, `close_passes`, `grazing_passes`, `hgv_close_passes`, `peak_closing_speed`, `closing_speed_p90`, `tightest_clearance`, `distance_ridden`, `time_with_traffic`, `close_pass_conversion_rate` (each prefixed `sensor.varia_<slug>_`). A `tightest_pass` JSON attribute carries the worst-pass-of-the-ride record (timestamp, side, vehicle size, clearance, closing speed). Auto-discovers under the same HA device card as the existing battery and close-pass entities; vanilla HA + MQTT integration is enough, no YAML required. Published every 60 s when state changes; sensors flip to `unavailable` ten minutes past the last update. Measurement-class fields (peak closing, p90, tightest clearance) report `unknown` until the first relevant observation lands, instead of a misleading 0.

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

- JVM test suite expanded to 288 Robolectric tests. New coverage: service and activity boot smoke; all 13 Compose screens composed synchronously; gate-matrix tests for `BootReceiver`, `InternalControlReceiver`, and `RemoteControlReceiver`; `HaClient` short-circuit guards; pipeline replay through decoder -> detector -> accumulator; `HaCredentials` round-trip.
- `HaCredentials` crypto extracted to a `Cryptor` interface (`AndroidKeyStoreCryptor` in production, `InMemoryCryptor` in tests). Removes the requirement for a real AndroidKeyStore in JVM tests.
- 11 Paparazzi screenshot tests for `RadarOverlayView`. Goldens cover: empty, single vehicle, close-approach danger border, multiple vehicles, mixed sizes, alongside-stationary hollow outline, battery-low badge, dashcam-missing/dropped icons, scenario replay label, and alert threshold line. No device required. Run locally with `:app:verifyPaparazziDebug` before pushing UI changes; CI runs the Robolectric suite only (Paparazzi's pre-release layoutlib loader is too unreliable on cold-cache JVMs).
- Build upgraded to Java 21 (required for Paparazzi 2.x layoutlib rendering engine).
- `targetSdk` bumped from 35 to 36 (Android 16). Robolectric pinned to SDK 35 via `app/src/test/resources/robolectric.properties` because Robolectric 4.14's SDK 36 runtime ships incomplete shadows.

### Compatibility

- No migration. SharedPreferences, paired devices, HA credentials, overlay positioning, and the existing battery + close-pass MQTT topics are unchanged. The new ride-summary entities appear automatically in HA on first publish.

## v0.4.1-alpha - 2026-05-02

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

## v0.4.0-alpha - 2026-05-01

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

## v0.3.0-alpha - 2026-04-29

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
