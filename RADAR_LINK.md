# Radar link

What the rear radar's link must not do, and the test that pins each. Read it
before changing `RadarLinkController`, `RadarLinkCoordinator` or
`RadarSelection`. [`AGENTS.md`](AGENTS.md) has the build and test commands.

## Choosing the radar

- Radar selection is name-match by default; a rider with more than one radar
  bonded can pin this bike's (`Prefs.radarMac`), and a pinned-and-still-bonded
  MAC overrides the name-match in `scheduleRead` so a sighting links only the
  pinned radar, not another rear unit. So does a pin when the paired list
  reads empty, which Bluetooth off and a refused read both produce
  (`RadarSelection.pinHolds`,
  `RadarSelectionTest."an empty bonded read with a pin links no other radar"`).
  Pure decider in
  `RadarSelection.shouldLinkRadar`; managed in Settings -> Connections -> Radar,
  and in setup's pairing step until the radar's name can only be a rear unit
  (`setupOffersPick`). A pin changed while a link to another radar runs moves
  that link, and a pinned radar paired later moves it when it is paired or
  sighted;
  the old loop is joined before the pinned radar starts
  (`RadarLinkController.followPin`,
  `BikeRadarServiceSmokeTest.aPin*`,
  `RadarLinkControllerHarnessTest.aPinMovedWhileStreamingEndsTheOldLinkBeforeThePinnedOneOpens`).
  A pin written with no link running starts one when the pin is paired and
  alerts are not paused. The service starting, Bluetooth coming on and a
  pause ending start it the same way, including a pin the known-device cache
  never held (`BikeRadarService.startPinIfIdle`,
  `BikeRadarServiceSmokeTest.aPinSetWhileNoLinkRunsStartsALinkToIt`,
  `aServiceStartedWithAPairedPinTheCacheNeverHeldStartsIt`).
  A failed bonded read never moves a running link
  (`RadarSelectionTest."an empty bonded read never moves a running link"`), so
  on one the other radar keeps streaming until the pinned one is next sighted.
  In AOSP, `getBondedDevices` returns an empty set unless the adapter is on,
  and a device joins the paired list before its pairing broadcast is sent, so
  a non-empty list is taken as complete. A radar starts only if that list
  holds it or the list is empty (`RadarSelection.mayStartLink`,
  `BikeRadarServiceSmokeTest.aPinnedRiderStartsNoAddressTheReadablePairedListLacks`).
  `kickstartFromCache` removes from the known-device cache every entry a
  non-empty list lacks, so a replay never starts it. A running link to an
  address the list lacks gives way to a radar the list holds, since no
  bond-removed broadcast comes for an address already unpaired to end that
  loop (`RadarSelection.shouldReplaceUnpairedLink`,
  `BikeRadarServiceSmokeTest.aStaleCachedAddressDoesNotHoldTheLinkSlot`,
  `RadarLinkControllerHarnessTest.aStartForAPairedRadarEndsALinkToAnUnpairedOne`).

## The older-radar stream

- Never subscribe the CCCD of `6a4e3203` (V1 radar char) on a radar that has
  `6a4e3204`. Writing that CCCD before the enabling sequence (fw 6.70) drops
  the radar into V1: the handshake succeeds, V1 heartbeats arrive on
  `6a4e3203`, and `6a4e3204` never emits. Later connections that never touch
  the CCCD get no V2 either, until the radar is power-cycled (the V1 lock).
  See `Uuids.RADAR_V1`.
  - **The one sanctioned subscribe is the legacy-stream fallback**, and its
    gate is that whole exception. `RadarLinkController.legacyStreamChar`
    returns the characteristic ONLY when the radar service carries no
    `6a4e3204`, read off the discovered GATT table rather than the stored
    probe. A radar with V2 therefore cannot reach the subscribe, so the V1
    lock cannot be applied to a radar it would cost anything.
    `RadarLinkControllerHarnessTest` pins both directions: falls back when
    the characteristic is absent, never falls back when it is present
    however the handshake ends.
  - **Do not relax that gate to a retry count.** A count fires on a healthy
    radar after a transient handshake failure, which is precisely when the V1
    lock is expensive - it would cost the rider V2 until they power-cycle. The
    absence of `6a4e3204` is a fact about the hardware; a retry count is a
    guess about the moment.
  - The stream itself carries range only. `RadarV1Decoder` writes a zero
    closing speed and sets `lateralUnknown` on every track, and both are
    fail-closed sentinels rather than measurements: the urgent cue and
    close-pass detection must stay shut on it, while the distance-scored
    awareness tiers and the all-clear still work. `RadarV1SafetyTest` is the
    argument, and it holds close-pass shut even if a rider speed later arrives
    from another source. Read it before widening this path.

## When the radar counts as up

- **The radar counts as up from its first data frame, not from service
  discovery or the handshake.** `RadarLinkController` calls `markLinkOpen` at
  discovery (for the radio-link readers listed on `radarGattActive`) and
  `markConnected` once, on the first V2 or legacy frame. Moving
  `markConnected` earlier makes a radar that fails every handshake read as
  up: the drop cue and the no-radar warning go silent while the rider has no
  rear cover. No alert about rear cover reads `radarGattActive`; the one
  alert that reads it is the radar-left-on alarm, which holds while a link is
  open, for up to `RadarLeftOnDecider.LINK_HOLD_MAX_MS` after the drop,
  because an open link means the phone heard a radar again. The
  `RadarLinkState.radarGattActive` KDoc says what each alert reads, and the
  two ways the flag reaches the dashcam alarm: the radar-left-on check it
  holds for, and the dashcam probe backoff.
  `RadarLinkControllerHarnessTest` (`...IsNeverMarkedUp`) and the
  coordinator's abort-loop tests pin it. Not closed: a radar that
  sends one frame and then fails, every attempt, still counts as up each time.

## Connect-time writes and the tail-light failure report

- **The decode loop starts straight after the handshake, and the
  connect-time writes run beside it in one setup job per connection.** The
  job sends the 2f14 subscribe, then the debug 2f12 one, then the tail-light
  set. Each try of the set can run to the 5 s op timeout. No frame waits for
  an unanswered write, so no alert does
  (`RadarLinkControllerHarnessTest.aSlowTailLightSetDoesNotHoldTheRadarFrames`,
  `anUnansweredLightStateSubscribeDoesNotHoldTheRadarFrames`,
  `theFirstFrameMarksTheRadarUpDuringASlowLightSet`,
  `theLightStateSubscribeGoesOutBeforeTheFirstLightWrite`,
  `theDebugProbeSubscribesBothSettingsCharacteristicsBeforeTheLightSet`,
  `theDebugProbeAloneSubscribesBothSettingsCharacteristics`). The legacy
  stream's battery read runs beside its loop the same way
  (`aLegacyBatteryReadWithNoAnswerDoesNotHoldTheLegacyFrames`), and a legacy
  link's jobs end with it (`aLegacyLinkThatEndedLeavesNoWatchdogBehind`).
  - **Neither a granted app nor the debug probe can write to the radar
    before the subscribes.** Their handles are installed after them
    (`nothingIsWrittenToTheRadarBeforeItsSubscribesGoOut`). The granted-app
    handler is not installed at all once the link has ended
    (`aLinkLostDuringTheSubscribesLeavesNoHandlerForAGrantedApp`); no test
    covers the debug probe's handles in that case.
  - **The data-flow watchdog counts silence only once the setup job has
    ended**, from when it ended. A radar may hold its stream while a write
    goes unanswered, and that must not cost it the link. A radar that never
    streams, or stops during the setup, is torn down at the first tick past
    the stall window after the setup ends
    (`aSilentRadarIsTornDownAStallWindowAfterTheSetupEnds`,
    `aStreamThatStopsDuringASlowSetupIsTornDownAStallWindowAfterIt`,
    `aStreamThatStopsAfterASlowSetupIsStillTornDown`). The setup job adds no
    duration of its own: the wait is its writes' op timeouts and retry gaps.
  - **A failed set is reported only after the radar streams again.** After a
    last try that went unanswered or was refused, the journal line
    `applied=false`, the notification and the tone wait for a V2 frame;
    frames the loop counted before the failure do not count. Nothing is
    reported if the link ends first or the app ends it
    (`anAutoModeWriteTheRadarRefusesIsJournalledAsRefused`,
    `threeUnansweredTriesOnAStreamingLinkReportTheFailure`,
    `framesBeforeTheFailureDoNotReportIt`,
    `aFrameAfterTheAppEndedTheLinkReportsNoFailure`,
    `aFrameConsumedDuringTheDropReportsNoFailure`). The notification has its
    own text about the light rather than the connection, expanding to both
    sentences
    (`ServiceNotificationsTest.theRadarLightFailureSaysToCheckTheLightAndShowsInFull`).
    With no cue sounding, no pause holding and no call on (`AUDIO_DESIGN.md`,
    "When the audio steps back"; `AlertBeeper.cueSounding`), it is posted
    once without a silent marking, so it alerts as its channel is set to,
    and the tone plays. Otherwise it is posted marked silent at once, then
    again in the same slot without the marking, with the tone, once nothing
    holds. No test checks what a phone then plays (`AUDIO_DESIGN.md`). A link
    that ends or that the app ends during the wait leaves the silent post
    only, and a service that stops sounds nothing
    (`ServiceNotificationsTest.theRadarLightFailureIsPostedSilentThenAlertingInPlaceAndTheDashcamsAlerts`,
    `RadarLinkControllerHarnessTest.aFailureWithNothingInTheWayIsPostedOnceAndSoundsOnce`,
    `aHeldFailureIsPostedSilentlyThenAgainWithItsTone`,
    `aHoldEndingAsTheRadarDisconnectsPostsNothingMore`,
    `aHoldEndingAfterTheAppEndedTheLinkPostsNothingMore`,
    `aHeldFailToneNeverSoundsOnceTheBeeperIsReleased`). A service that stops
    takes an entry already posted out of the shade
    (`BikeRadarServiceSmokeTest.theTailLightFailureDoesNotOutliveTheService`).
    A failed dusk/dawn switch is reported like the connect-time set
    (`aFlipTheRadarRefusesIsReportedToo`).
    The alert goes out once per fault per ride, a ride being the app's own:
    a radar that streams again after an off-episode of at least the rider's
    idle-radar threshold (`Prefs.radarLongOfflineThresholdMinutes`) starts a
    new one (`RadarLinkState.newRideAtConnect`). After the alert, any failure
    in the same ride is posted silent at once, with no tone and no wait,
    until a tail-light write lands or a new ride starts
    (`aRepeatFailureOnALaterConnectionOnlyUpdatesTheEntry`,
    `aRepeatFailureWhileACueSoundsIsPostedAtOnceAndNeverAgain`,
    `aFailureOnANewRideAlertsInFullAgain`,
    `aFailureAMinuteShortOfTheNewRideGapOnlyUpdatesTheEntry`,
    `theNewRideGapIsTheRidersOwn`; the re-arm comes before the new ride's
    first frame is counted, `aNewRideIsReArmedBeforeItsFirstFrameCanReport`).
    Two reports held together alert once
    (`twoReportsHeldTogetherAlertOnceAndSoundOnce`). Only a report that
    alerts writes a line of its own, `radar light <phase> mode=<mode> alerted`
    (`aFailureWithNothingInTheWayIsPostedOnceAndSoundsOnce`; a repeat writes
    none, `aRepeatFailureOnALaterConnectionOnlyUpdatesTheEntry`). A report
    that ends with no alert leaves the next failure to alert
    (`aHoldThatEndsWithTheLinkLeavesTheNextFailureToAlert`). A write that
    lands, the auto-mode's or a granted app's, takes the notification out of
    the shade and lets the next failure alert; a refused one and a press on
    the light's button do neither (`aLandedSetTakesTheFailureOffTheShade`,
    `aFailureAfterALandedSetAlertsAgain`,
    `aGrantedAppsLandedWriteTakesTheFailureOffTheShadeAndRearmsItsAlert`,
    `aGrantedAppsRefusedWriteLeavesTheFailureInTheShade`,
    `aPressNeitherTakesTheFailureOffTheShadeNorRearmsItsAlert`). A write
    that lands after a failure, before the frame that would report it,
    leaves that report nothing to send: no `applied=false` line, post or tone
    (`aSwitchThatLandsBeforeTheFrameLeavesTheFailureUnreported`). One that
    lands during the report's hold comes after its `applied=false` line and
    its silent post; the landing takes the entry out of the shade, and no
    alerting post or tone follows
    (`aSwitchThatLandsDuringTheHoldStandsTheHeldReportDown`,
    `aGrantedAppsWriteThatLandsDuringTheHoldStandsTheSwitchsReportDown`).
  - **Two journal lines mark a set that the link's end stopped.**
    `ended with the link` marks one the end cut short mid-try or whose last
    try it failed, for example through a drop or a pin move
    (`aLinkLostDuringTheLightSetSoundsNoLightFailure`,
    `aPinMovedDuringTheLightSetLeavesNothingOfTheOldLinksSet`,
    `aLastTryTheDropFailsEndsWithTheLink`). `failed before the link ended`
    marks one whose tries had all failed and whose link then ended before
    the next frame, for example through a drop or a watchdog teardown
    (`aSilentRadarIsTornDownAStallWindowAfterTheSetupEnds`,
    `aFrameConsumedDuringTheDropReportsNoFailure`). A watchdog teardown
    cannot cut short the connect-time set, since the watchdog counts only
    after it (`aSilentRadarIsTornDownAStallWindowAfterTheSetupEnds`); it can
    end a report's wait or a dusk/dawn switch.
  - **The connect-time set does not overwrite a granted app's choice.** It
    makes no further try once the app has asked, even if the app's own write
    then fails, and nothing is reported then
    (`aGrantedAppsChoiceStandsOverTheConnectTimeSet`,
    `aGrantedAppsUnansweredWriteStillStopsTheConnectTimeSet`). A dusk/dawn
    switch still overwrites it, deliberately, once the setup job has ended
    (`aDuskSwitchStillWritesAfterAGrantedAppSetTheLight`).
  - A press on the radar's light button stops the set or a dusk/dawn switch
    before its next try. When the try in flight then fails, the press leaves
    no light line (`aPressBeforeTheFirstTryStopsTheSet`,
    `aPressDuringTheLightRetriesEndsThem`,
    `aPressDuringTheSecondTryEndsTheRetries`,
    `aPressDuringTheLastTryReportsNoFailure`,
    `aPressDuringADuskSwitchLeavesNoLightLine`). A try whose answer reaches
    the set after the loop has read a press journals `answered after the
    rider changed the light`, not `applied=true`, and neither takes a failure
    entry out of the shade nor re-arms its alert
    (`aSetAnsweredAfterAPressIsNotJournalledAsApplied`,
    `aDuskSwitchAnsweredAfterAPressIsNotJournalledAsApplied`,
    `aSetAnsweredAfterAPressLeavesTheFailureInTheShadeAndItsAlertSpent`). A
    repeat of the unchanged light state during a write is not a press
    (`theUnchangedLightStateDuringASetIsNotAPress`).
    When a press and a write cross, the phone cannot tell which mode the
    radar ended in, because the radar never reports a type the app set, and
    the app deliberately does not write the rider's mode back
    (`aPressWhileTheSetAwaitsItsAnswerHoldsAutoModeOff`). A press after
    a failed try of the set or of a switch, or an app's request after a failed
    connect-time set, before the frame that would report it, leaves no report
    (`aPressAfterAFailedSetReportsNothing`,
    `aPressAfterAFailedDuskSwitchReportsNothing`,
    `aGrantedAppsRequestAfterAFailedSetReportsNothing`). The same holds
    while a report's alert waits for a cue, a pause or a call: the press,
    or for the connect-time set an app's request the radar refuses, leaves
    the silent post with no alert and no tone
    (`aPressDuringTheHoldLeavesTheSilentPostOnly`,
    `aPressDuringTheHoldLeavesADuskSwitchsSilentPostOnly`,
    `aGrantedAppsRequestDuringTheHoldLeavesTheSilentPostOnly`); a request
    that lands takes the entry out of the shade, as the writes above do.
    An app's request the radar refuses, whether made before a switch, after
    its failed try or while its alert waits, never stops that switch's
    failure report
    (`aGrantedAppsRequestBeforeADuskSwitchLeavesItsFailureReported`,
    `aGrantedAppsRequestAfterAFailedDuskSwitchLeavesItReported`,
    `aGrantedAppsRequestDuringTheHoldStillLeavesADuskSwitchReported`); one
    that lands ends it as any landed write does
    (`aGrantedAppsWriteThatLandsDuringTheHoldStandsTheSwitchsReportDown`). A
    dusk/dawn switch waits for the setup job
    (`aFlipDueDuringTheInitialSetComesAfterIt`).
    The light baseline and the override deadband are reset before the job
    and the loop start
    (`aLightStateWaitingAtTheLoopsStartIsTheNewLinksBaseline`).
    In the journal the light line can follow "radar V2 stream live".
