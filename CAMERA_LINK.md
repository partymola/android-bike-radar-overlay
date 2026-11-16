# Front camera link

What the front camera's link, its light set and failure report, and the
dashcam walk-away alarm's probe must not do, and the test that pins each.
[`AGENTS.md`](AGENTS.md) has the build and test commands.

## How the walk-away alarm hears the camera

- **The dashcam walk-away alarm hears the camera only through a battery read
  it answered or its established light link, never through
  `BatteryStateBus`**, which sightings with no contact also stamp
  (`RadarLinkCoordinatorTest.aSightingWithNoContactIsNotHeard`). While the
  alarm can still sound (`RadarLinkCoordinator.dashcamAlarmCanSound`) the
  probe starts the next read on the first 2 s tick at least 2 s after the
  camera was last heard, so a camera answering within the 15 s read timeout is
  never older than the alarm's 20 s while both loops run on time
  (`DashcamProbeTest.anArmedProbeKeepsACameraThatAnswersInsideTheAlarmsWindow`,
  `DashcamHeardWindowTest`, whose harness runs both loops in virtual time).
  The walk-away wakelock keeps them on time until the alarm first sounds;
  after that a walk-away tick more than two ticks after the one before counts
  as a wake, and the camera's unheard time counts from the first wake after
  each hearing, giving its read 20 s to land
  (`aLateWakeAfterTheAlarmSoundedDoesNotEndIt`,
  `repeatedShortWakesDoNotHoldOffTheStopOfACameraThatWentOff`). Its answer runs
  the walk-away tick at once while the alarm can sound, so the repeat can sound
  on a phone that sleeps between reads, provided the answer's callback keeps it
  awake that long (`RadarLinkCoordinator.onDashcamAnswered`,
  `aCameraStillAnsweringAcrossRepeatedLongSleepsSoundsItsRepeat`). Failed reads with a radar
  link open back off from 2 s and can pass the bound. It holds only because the
  read's in-flight guard ends before its Home Assistant send
  (`BatteryReaderTest.aSlowPublishDoesNotHoldTheNextRead`); that send goes
  out only on a change or the 5 min heartbeat (`HaPublisher.sendBattery`).

## The dashcam light's set and its failure report

How `CameraLightLinkController` sets the front light and reports a failed set.
The tail light's equivalent is in [`RADAR_LINK.md`](RADAR_LINK.md). A test
named bare below is in `CameraLightLinkControllerHarnessTest`, and the radar
harness has some of the same names.

- **The initial set runs in a job of its own beside the notify loop, and the
  dusk or dawn switch waits for it.** An unanswered write holds neither a
  battery reading nor a mode-state notify
  (`anUnansweredLightSetDoesNotHoldTheCamerasNotifications`,
  `aFlipDueDuringTheInitialSetComesAfterIt`). The initial set's report is a
  job of its own too, so a cue holding the report does not hold the switch
  (`aSwitchThatLandsDuringTheHoldStandsTheHeldReportDown`). A link's end
  cancels its switch (`aSwitchOfALinkThatEndedNeverFires`).
- **The camera's echo of the mode being written is not a press.** The echo
  can reach the notify loop before the write's answer reaches the set, so the
  loop does not read a frame carrying the mode held in `cameraLightSetting`
  as a press (`anEchoOfTheSetBeforeItsAnswerIsNotAPress`). A frame carrying
  neither the mode being written nor the last one that landed is a press
  (`aPressDuringASetIsStillAPress`),
  and a frame after a failed set is read against the last mode that landed
  (`aFrameAfterAFailedSetIsReadAgainstTheLastLandedMode`). The loop reads the
  setting before the last-written mode. A set stores the landed mode and then
  clears the setting, so read the other way round, a frame between the two
  sees the old landed mode and no setting and takes the echo for a press. No
  test reaches that order on a single-threaded dispatcher. A set clears only
  the setting it made, so a cancelled set of an old connection cannot clear a
  newer one's; no test reaches that case.
- **A press of the light's button does not stop the set's remaining tries,
  deliberately, unlike the tail light's.** A mode-state frame from a camera
  that has just come up reports its own mode before our write reaches it and
  can read as a press, and stopping on it would leave the light in the mode the
  camera started in. The set can therefore land over the rider's choice; do
  not give it a stop on a press
  (`aPressDuringATryDoesNotStopTheRemainingTries`). A press still holds
  auto-mode off from the next connection (`aPressDuringASetIsStillAPress`)
  and ends the report, whether it lands while the set runs, before the proof
  read below is answered or during the hold
  (`aPressDuringTheSetLeavesTheAlertedEntryAsItWas`,
  `aPressWhileTheProofReadIsPendingReportsNothing`,
  `aPressDuringTheHoldLeavesTheSilentPostOnly`).
- **A failure is reported only after the camera answers a read of its battery
  characteristic since the failure.** The op queue reports a write the camera
  refused and one it never answered alike, so every failure waits on that
  read, which gives up at the queue's own op timeout
  (`unansweredWritesAreReportedOnceTheCameraAnswersARead`,
  `unansweredWritesAndAnUnansweredReadReportNothing`,
  `aRefusedSetWhoseFollowingReadGoesUnansweredReportsNothing`,
  `BleOpQueueHarnessTest.readTimesOutToNullWhenCallbackNeverArrives`). A read
  answered with an error status counts as no answer
  (`BleOpQueueHarnessTest.readCompletesNullOnGattError`; no test answers the
  proof read with one). A camera with no battery characteristic never
  reports, deliberately, since it cannot prove it answers
  (`aCameraWithNoBatteryToReadNeverProvesItAnswers`). On a phone, after a
  write the camera never answered, the platform's GATT client stays busy
  until its own timeout, so the read is usually refused at submit and nothing
  is reported. No test reaches that state; the late answer is the case
  `unansweredWritesAreReportedOnceTheCameraAnswersARead` covers.
- **A set the link's end failed reports nothing, nor does one on a link the
  app ended.** The disconnect callback flags the link down before it fails
  the pending write, and the report reads that flag and the app-ended one, so
  a report that wakes before the teardown reaches it stands down
  (`aLinkLostDuringTheLightSetSoundsNoLightFailure`,
  `aSetTheLinksEndFailedReportsNothingBeforeTheTeardownReachesIt`,
  `aReportWakingAsTheAppEndsTheLinkPostsNothing`,
  `aSetTheAppEndsReportsNothing`,
  `aHoldEndingAsTheCameraDisconnectsPostsNothingMore`,
  `aLinkLostDuringARepeatSetLeavesTheAlertedEntryAsItWas`).
- **The alert waits for the audio as the tail light's does**
  ([`RADAR_LINK.md`](RADAR_LINK.md) has the rule; `lightFailToneQuiet` in
  `LightFailAudio.kt` is the hold both share). With no cue sounding, no pause
  and no call, the notification is posted once without a silent marking and
  the tone plays (`aFailureWithNothingInTheWayIsPostedOnceAndSoundsOnce`).
  Otherwise it is posted marked silent at once, then again in the same slot
  with the tone once nothing holds
  (`aHeldFailureIsPostedSilentlyThenAgainWithItsTone`,
  `theFailToneWaitsForAResume`, `theFailToneWaitsForAPauseToRunOut`,
  `theFailToneWaitsForACallToEnd`). A link that ends during the wait, or a
  press, leaves the silent post only
  (`aHeldFailToneNeverSoundsOnceTheLinkEnds`,
  `aPressDuringTheHoldLeavesTheSilentPostOnly`). A released beeper never
  reads quiet, so a report waiting on it, or made after it, leaves the silent
  post only (`aHeldFailToneNeverSoundsOnceTheBeeperIsReleased`,
  `aFailureReportedAfterTheBeeperIsReleasedSoundsNothing`). A cancel during
  the tone ends its caller too, so nothing after the tone runs for a link
  that has ended (`LightFailAudioTest.aCancelDuringTheToneEndsTheCallerToo`).
- **The alert goes out once per fault per ride.** After it, any failure in the
  same ride is posted silent at once, with no tone and no wait for the audio,
  until a light write lands or a new ride starts
  (`aRepeatFailureOnALaterConnectionOnlyUpdatesTheEntry`,
  `aRepeatFailureWhileACueSoundsIsPostedAtOnceAndNeverAgain`,
  `aSwitchTheCameraRefusesOnlyUpdatesTheEntry`). A write that lands takes
  the notification out of the shade and lets the next failure alert
  (`aLandedSetTakesTheFailureOffTheShade`,
  `aFailureAfterALandedSetAlertsAgain`). A write that lands after a failure
  ends its report: no alerting post or tone follows, including when it
  lands while the report still waits for the cue flow's first value
  (`aSwitchThatLandsDuringTheHoldStandsTheHeldReportDown`,
  `aWriteThatLandsWhileTheCueFlowIsStartingLeavesNoPost`); no test lands one
  during the proof read or with nothing holding the tone. A ride is the
  app's own: the camera's link coming back after being off for at least the
  rider's idle-radar threshold (`Prefs.radarLongOfflineThresholdMinutes`)
  starts a new one. The gap is read on the camera's own off-time, so it holds
  with no radar, as in the harness
  (`aFailureOnANewRideAlertsInFullAgain`,
  `aFailureAMinuteShortOfTheNewRideGapOnlyUpdatesTheEntry`,
  `theNewRideGapIsTheRidersOwn`).
- **The notification's text is about the light, not the connection, and
  expands to both sentences, in both locales.** It is posted only on a link
  that has answered, and it names the mode the failed set was writing, a
  switch's included
  (`ServiceNotificationsTest.theDashcamLightFailureSaysToLookAtTheLightAndShowsInFull`,
  `ServiceNotificationsTest.theDashcamLightFailureSaysToLookAtTheLightAndShowsInFullInSpanishToo`,
  `CameraLightLinkControllerHarnessTest.aSwitchTheCameraRefusesOnlyUpdatesTheEntry`).
  The silent marking is the support library's, and the alerting repost
  replaces it in the same slot
  (`ServiceNotificationsTest.theDashcamLightFailureIsPostedSilentThenAlertingInPlace`).
  Its id is its own, so a cancel takes it out alone and leaves the tail
  light's
  (`ServiceNotificationsTest.theDashcamLightFailureIsCancelledAloneLeavingTheTailLights`).
- **A service that stops takes an entry already posted out of the shade**
  (`DashcamLightFailureServiceTest.theDashcamLightFailureDoesNotOutliveTheService`).
  The link waits on the cue flow of the ride's own beeper, since a flow from
  anywhere else would sound the alert over a cue
  (`DashcamLightFailureServiceTest.theDashcamLinkWaitsOnTheCuesOfTheRidesBeeper`).
