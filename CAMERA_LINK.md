# Front camera link

What the front camera's link and the dashcam walk-away alarm's probe must
not do, and the test that pins each. [`AGENTS.md`](AGENTS.md) has the build
and test commands.

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
