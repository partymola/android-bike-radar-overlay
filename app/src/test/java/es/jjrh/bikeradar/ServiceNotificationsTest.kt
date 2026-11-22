// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.VibrationAttributes
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Channel lifecycle + the foreground notification's paused/active branch.
 * The smoke test only asserts "some channel exists"; this pins the specific
 * channels, the legacy-channel migration, and the notification text.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifications(prefs: Prefs = Prefs(app)) = ServiceNotifications(app) { prefs }

    @Test fun ensureChannelsCreatesAllServiceChannels() {
        notifications().ensureChannels()
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.LIGHT_FAIL_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.RIDE_SUMMARY_CHANNEL_ID))
    }

    @Test fun ensureChannelsDeletesSupersededWalkAwayChannel() {
        // A v1 install left a channel under the old id; the migration must drop
        // it so the v3 channel's properties take effect.
        nm.createNotificationChannel(
            android.app.NotificationChannel("bike_radar_walkaway", "old", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications().ensureChannels()
        assertNull("legacy walk-away channel must be deleted", nm.getNotificationChannel("bike_radar_walkaway"))
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID))
    }

    @Test fun ensureChannelsDeletesSupersededForgotLockChannel() {
        // A pre-v2 install left the forgot-lock channel under the old id; the
        // migration must drop it so the v2 properties (channel vibration off,
        // bypass-DND) take effect on the new id.
        nm.createNotificationChannel(
            android.app.NotificationChannel("bike_radar_forgot_lock", "old", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications().ensureChannels()
        assertNull("legacy forgot-lock channel must be deleted", nm.getNotificationChannel("bike_radar_forgot_lock"))
        val v2 = nm.getNotificationChannel(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID)
        assertNotNull(v2)
        // v2 disables channel vibration so the explicit DndVibration buzz is the
        // only one - a regression to channel vibration would double-buzz off DND.
        assertFalse("v2 forgot-lock channel must disable channel vibration", v2.shouldVibrate())
    }

    @Test fun theWalkAwayChannelTakesTheCurrentName() {
        // An upgrader's channel was created under an earlier name.
        nm.createNotificationChannel(
            android.app.NotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID, "old", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications().ensureChannels()
        val ch = nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID)
        assertEquals(app.getString(R.string.svc_main_walkaway_channel_name), ch.name.toString())
        assertEquals(app.getString(R.string.svc_main_walkaway_channel_desc), ch.description)
    }

    @Test fun ensureChannelsIsIdempotent() {
        val n = notifications()
        n.ensureChannels()
        n.ensureChannels()
        assertNotNull(nm.getNotificationChannel(ServiceNotifications.CHANNEL_ID))
    }

    @Test fun foregroundTextIsActiveWhenNotPaused() {
        val prefs = Prefs(app).apply { pausedUntilEpochMs = 0L }
        val notif = notifications(prefs).buildForeground()
        assertEquals(
            app.getString(R.string.svc_main_notif_active),
            notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun foregroundTextSwitchesToPausedWhenPaused() {
        val prefs = Prefs(app).apply { pausedUntilEpochMs = System.currentTimeMillis() + 3_600_000L }
        val text = notifications(prefs).buildForeground()
            .extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // Paused branch differs from the active string - proves the toggle flips
        // rather than always rendering one state.
        assertNotNull(text)
        assert(text != app.getString(R.string.svc_main_notif_active)) {
            "paused notification must not show the active text"
        }
    }

    private fun title(sbn: android.service.notification.StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test fun bondLostPostsToMinChannelThenCancels() {
        val n = notifications()
        n.postBondLost()
        val sbn = nm.activeNotifications.single()
        assertEquals(ServiceNotifications.CHANNEL_ID, sbn.notification.channelId)
        assertEquals(
            app.getString(R.string.svc_main_bond_lost_text),
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        n.cancelBondLost()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun walkAwayPostsWithDismissAndSnoozeActionsThenCancels() {
        val n = notifications()
        val expected = mapOf(
            WalkAwayAlarmKind.DASHCAM to (
                "Dashcam still on" to
                    "The radar has disconnected but the dashcam is still on. Turn it off to save its battery."
                ),
            WalkAwayAlarmKind.RADAR to (
                "Radar still on?" to
                    "Looks like it went out of range while still on. Go back and turn it off."
                ),
            WalkAwayAlarmKind.RADAR_AND_DASHCAM to (
                "Radar and dashcam still on?" to
                    "Looks like the radar went out of range while still on, and the dashcam was on too. Turn them both off."
                ),
        )
        for ((kind, words) in expected) {
            n.postWalkAway(kind)
            val sbn = nm.activeNotifications.single()
            assertEquals(ServiceNotifications.WALKAWAY_CHANNEL_ID, sbn.notification.channelId)
            assertEquals(words.first, title(sbn))
            assertEquals(words.second, bigText(sbn))
            // One alert slot, so the dismiss and snooze buttons answer whichever sounded.
            assertEquals(listOf("Dismiss", "Remind in 2 min"), sbn.notification.actions.map { it.title.toString() })
            n.cancelWalkAway()
            assertEquals(0, nm.activeNotifications.size)
        }
    }

    @Test fun forgotToLockPostsToForgotLockChannelAndVibrates() {
        // The reminder must post on the forgot-lock channel AND drive an explicit
        // vibration: the v2 channel disables channel vibration so the buzz comes
        // from DndVibration (USAGE_ALARM) and pierces Do Not Disturb.
        val shadowVibrator = shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val n = notifications()
        n.postForgotToLock()
        val sbn = nm.activeNotifications.single()
        assertEquals(ServiceNotifications.FORGOT_LOCK_CHANNEL_ID, sbn.notification.channelId)
        assertEquals(app.getString(R.string.svc_main_forgot_lock_title), title(sbn))
        assertTrue("forgot-to-lock must drive an explicit DND-piercing vibration", shadowVibrator.isVibrating)
        // The DND-piercing property IS the USAGE_ALARM attribute (channel
        // vibration is silenced under DND; a USAGE_ALARM vibration is not). Pin
        // it so a regression to USAGE_NOTIFICATION can't silently re-break it.
        assertEquals(
            VibrationAttributes.USAGE_ALARM,
            (shadowVibrator.vibrationAttributesFromLastVibration as VibrationAttributes).usage,
        )
        n.cancelForgotToLock()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun lightFailAndRadarLightFailUseDistinctIdsAndTitles() {
        // Distinct ids are load-bearing: a dashcam-light failure and a
        // radar-light failure must coexist, not clobber each other.
        val n = notifications()
        n.postLightFail("Day Flash", silent = false)
        n.postRadarLightFail("Solid", silent = false)
        assertEquals(2, nm.activeNotifications.size)
        assertEquals(
            setOf(
                app.getString(R.string.svc_main_dashcam_light_title),
                app.getString(R.string.svc_main_radar_light_title),
            ),
            nm.activeNotifications.map { title(it) }.toSet(),
        )
    }

    private fun lightFail(title: String) = nm.activeNotifications.single { title(it) == title }.notification.extras

    @Test fun theRadarLightFailureSaysToCheckTheLightAndShowsInFull() {
        // It posts only on a link that has answered, so the text is about the
        // light; it is two sentences, so the collapsed line alone cuts it.
        val n = notifications()
        n.postRadarLightFail("Solid", silent = false)
        val radar = lightFail("Tail light")
        val expected = "Couldn't switch the tail light to Solid. Check it while you're stopped."
        assertEquals(expected, radar.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(expected, radar.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    @Test fun theDashcamLightFailureSaysToLookAtTheLightAndShowsInFull() {
        val n = notifications()
        n.postLightFail("Day Flash", silent = false)
        val dashcam = lightFail("Dashcam light")
        val expected = "Couldn't switch the dashcam light to Day Flash. Have a look at it."
        assertEquals(expected, dashcam.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(expected, dashcam.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    @Test
    @Config(qualifiers = "+es")
    fun theDashcamLightFailureSaysToLookAtTheLightAndShowsInFullInSpanishToo() {
        notifications().postLightFail("Fija", silent = false)
        val dashcam = lightFail("Luz de la cámara")
        val expected = "No se ha podido cambiar la luz de la cámara a «Fija». Échale un vistazo."
        assertEquals(expected, dashcam.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(expected, dashcam.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    @Test fun theRadarLightFailureIsPostedSilentThenAlertingInPlaceAndTheDashcamsAlerts() {
        // The radar's failure is posted silent while its tone is held for cues,
        // a pause and a call, then again in the same slot without the marking.
        // The support library's silent marking is a child of its own group that
        // alerts only through a summary; the channel's sound and buzz are not
        // on the posted object at all, so only the marking can be pinned here.
        val n = notifications()
        fun posted(title: String) = nm.activeNotifications.single { title(it) == title }.notification
        n.postRadarLightFail("Solid", silent = true)
        val silent = posted("Tail light")
        assertEquals("silent", silent.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, silent.groupAlertBehavior)
        n.postRadarLightFail("Solid", silent = false)
        val alerting = posted("Tail light")
        assertNull(alerting.group)
        assertEquals(Notification.GROUP_ALERT_ALL, alerting.groupAlertBehavior)
        assertEquals(0, alerting.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        n.postLightFail("Day Flash", silent = false)
        val dashcam = posted("Dashcam light")
        assertNull(dashcam.group)
        assertEquals(Notification.GROUP_ALERT_ALL, dashcam.groupAlertBehavior)
        assertEquals(0, dashcam.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun theRadarLightFailureIsCancelledAloneLeavingTheDashcams() {
        val n = notifications()
        n.postRadarLightFail("Solid", silent = false)
        n.postLightFail("Day Flash", silent = false)
        n.cancelRadarLightFail()
        assertEquals(listOf("Dashcam light"), nm.activeNotifications.map { title(it) })
    }

    @Test fun theDashcamLightFailureIsPostedSilentThenAlertingInPlace() {
        // Posted silent while its tone is held, then again in the same slot
        // without the marking, as the tail light's is.
        val n = notifications()
        fun posted() = nm.activeNotifications.single { title(it) == "Dashcam light" }.notification
        n.postLightFail("Day Flash", silent = true)
        val silent = posted()
        assertEquals("silent", silent.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, silent.groupAlertBehavior)
        n.postLightFail("Day Flash", silent = false)
        val alerting = posted()
        assertNull(alerting.group)
        assertEquals(Notification.GROUP_ALERT_ALL, alerting.groupAlertBehavior)
        assertEquals(0, alerting.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun theDashcamLightFailureIsCancelledAloneLeavingTheTailLights() {
        val n = notifications()
        n.postRadarLightFail("Solid", silent = false)
        n.postLightFail("Day Flash", silent = false)
        n.cancelLightFail()
        assertEquals(listOf("Tail light"), nm.activeNotifications.map { title(it) })
    }

    @Test
    @Config(qualifiers = "+es")
    fun theRadarLightFailureSaysToCheckTheLightInSpanishToo() {
        notifications().postRadarLightFail("Fija", silent = false)
        val radar = lightFail("Luz trasera")
        val expected = "No se ha podido cambiar la luz trasera a «Fija». Échale un vistazo con la bici parada."
        assertEquals(expected, radar.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(expected, radar.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    /** An upgrader's light-failure channel, created under earlier words, after
     *  the service has started. The id is what keeps the rider's settings for
     *  the channel, so it is spelled out here rather than read off the code. */
    private fun lightFailChannelAfterAnUpgrade(): android.app.NotificationChannel {
        nm.createNotificationChannel(
            android.app.NotificationChannel("bike_radar_light_fail", "old", NotificationManager.IMPORTANCE_HIGH).apply { description = "old" },
        )
        val n = notifications()
        n.ensureChannels()
        n.postRadarLightFail("Solid", silent = false)
        assertEquals("bike_radar_light_fail", nm.activeNotifications.single().notification.channelId)
        return nm.getNotificationChannel("bike_radar_light_fail")
    }

    @Test fun theLightFailChannelTakesTheCurrentNameInEachLocale() {
        val ch = lightFailChannelAfterAnUpgrade()
        assertEquals("Light auto-mode", ch.name.toString())
        assertEquals("Tells you when the tail light or dashcam light couldn't be switched to its day or night mode.", ch.description)
        assertEquals("Tail light", title(nm.activeNotifications.single()))
    }

    @Test
    @Config(qualifiers = "+es")
    fun theLightFailChannelTakesTheCurrentNameInSpanishToo() {
        val ch = lightFailChannelAfterAnUpgrade()
        assertEquals("Modo automático de luces", ch.name.toString())
        assertEquals("Avisa si no se ha podido cambiar la luz trasera o la de la cámara a su modo diurno o nocturno.", ch.description)
        assertEquals("Luz trasera", title(nm.activeNotifications.single()))
    }

    private fun blankSnapshot() = RideStatsSnapshot(
        overtakesTotal = 6,
        closePassCount = 1,
        grazingCount = 0,
        hgvClosePassCount = 0,
        peakClosingKmh = null,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceRiddenKm = 4.2f,
        exposureSeconds = 600L,
        closePassConversionRatePct = 0f,
        tightestPass = null,
        rideStartedAtMs = 0L,
        alertsPerKm = null,
        alertsPerHourOfRide = null,
        closePassesCounted = true,
    )

    private fun bigText(sbn: android.service.notification.StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    @Test fun rideSummaryWithNoAttentionKeepsTheStatsRecap() {
        notifications().postRideSummary(blankSnapshot(), emptyList())
        val sbn = nm.activeNotifications.single()
        assertEquals(app.getString(R.string.notif_ride_summary_title), title(sbn))
        // The stats body still leads with the close passes, km and vehicles
        // detected line.
        assertTrue(bigText(sbn)!!.contains("Close passes"))
    }

    @Test fun rideSummaryOfAnUncountedRideLeavesOutClosePasses() {
        notifications().postRideSummary(blankSnapshot().copy(closePassesCounted = false), emptyList())
        val body = bigText(nm.activeNotifications.single())!!
        assertFalse(body, body.contains("Close passes"))
        assertTrue(body, body.contains("Vehicles detected behind: 6") && body.contains("4.2 km"))
    }

    @Test fun theRideSummaryLeadsWithClosePassesThenKmThenTheVehiclesDetected() {
        notifications().postRideSummary(blankSnapshot(), emptyList())
        val extras = nm.activeNotifications.single().notification.extras
        assertEquals(
            "Close passes: 1 • 4.2 km • Vehicles detected behind: 6",
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun theRidePassedCountHasItsOwnExpandedLine() {
        notifications().postRideSummary(blankSnapshot().copy(vehiclesPassed = 4), emptyList())
        val sbn = nm.activeNotifications.single()
        assertEquals(
            "Close passes: 1 • 4.2 km • Vehicles detected behind: 6\nAbout 4 passed you",
            bigText(sbn),
        )
        // Only the expanded view carries it, so the collapsed line is unchanged.
        assertEquals(
            "Close passes: 1 • 4.2 km • Vehicles detected behind: 6",
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun aRideWithNoPassedFigureHasNoPassedLine() {
        notifications().postRideSummary(blankSnapshot(), emptyList())
        val body = bigText(nm.activeNotifications.single())!!
        assertFalse(body, body.contains("passed you"))
    }

    @Test fun anUncountedRideStillShowsItsPassedCountOnItsOwnLine() {
        notifications().postRideSummary(blankSnapshot().copy(closePassesCounted = false, vehiclesPassed = 1), emptyList())
        assertEquals(
            "4.2 km • Vehicles detected behind: 6\nAbout 1 passed you",
            bigText(nm.activeNotifications.single()),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun theRideSummaryIsLabelledInSpanishToo() {
        notifications().postRideSummary(blankSnapshot().copy(vehiclesPassed = 1), emptyList())
        assertEquals(
            "Adelantamientos ajustados: 1 • 4,2 km • Vehículos detectados detrás: 6\nAdelantamientos: aprox. 1",
            bigText(nm.activeNotifications.single()),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun anUncountedRideIsLabelledInSpanishToo() {
        notifications().postRideSummary(blankSnapshot().copy(closePassesCounted = false, vehiclesPassed = 5), emptyList())
        assertEquals(
            "4,2 km • Vehículos detectados detrás: 6\nAdelantamientos: aprox. 5",
            bigText(nm.activeNotifications.single()),
        )
    }

    @Test fun rideSummaryLeadsWithAttentionWhenItemsExist() {
        notifications().postRideSummary(
            blankSnapshot(),
            listOf(
                AttentionItem(AttentionKind.RADAR_BATTERY, 12),
                AttentionItem(AttentionKind.AUDIO_FAILURES, 2),
            ),
        )
        val sbn = nm.activeNotifications.single()
        assertEquals(app.getString(R.string.attention_title), title(sbn))
        val body = bigText(sbn)!!
        assertTrue("attention body should charge the radar at 12%", body.contains("12"))
        // The stats recap must NOT appear when attention items lead.
        assertFalse(body.contains("Close passes"))
    }
}
