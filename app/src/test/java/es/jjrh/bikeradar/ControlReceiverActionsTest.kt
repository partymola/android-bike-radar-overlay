// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guards on the receiver split.
 *
 * The four notification-driven safety actions used to live on the exported
 * [RemoteControlReceiver] alongside the two dev-only adb actions. They were
 * moved to the non-exported [InternalControlReceiver] to stop peer apps
 * from silently pausing the overlay or dismissing the walk-away alarm.
 *
 * These tests pin the action strings (the wire format the system delivers
 * via PendingIntent) and the receiver-class membership (which receiver
 * each action lives on). A future "deduplication" or rename that drifted
 * either side would now fail loudly.
 */
class ControlReceiverActionsTest {

    @Test fun internalReceiverActionsHaveStableStrings() {
        assertEquals("es.jjrh.bikeradar.PAUSE_1H", InternalControlReceiver.ACTION_PAUSE_1H)
        assertEquals("es.jjrh.bikeradar.RESUME", InternalControlReceiver.ACTION_RESUME)
        assertEquals("es.jjrh.bikeradar.WALKAWAY_DISMISS", InternalControlReceiver.ACTION_WALKAWAY_DISMISS)
        assertEquals("es.jjrh.bikeradar.WALKAWAY_SNOOZE", InternalControlReceiver.ACTION_WALKAWAY_SNOOZE)
    }

    @Test fun remoteReceiverActionsHaveStableStrings() {
        assertEquals("es.jjrh.bikeradar.DEV_REPLAY", RemoteControlReceiver.ACTION_DEV_REPLAY)
        assertEquals("es.jjrh.bikeradar.DEV_SYNTH", RemoteControlReceiver.ACTION_DEV_SYNTH)
    }

    @Test fun remoteReceiverDoesNotExposeMovedActions() {
        // Ensure the four moved action constants are not silently re-added
        // to the exported receiver. Any re-introduction would re-open the
        // peer-app reachability hole this split closed.
        val movedNames = setOf(
            "ACTION_PAUSE_1H", "ACTION_RESUME",
            "ACTION_WALKAWAY_DISMISS", "ACTION_WALKAWAY_SNOOZE",
        )
        val remoteFields = RemoteControlReceiver.Companion::class.java.declaredFields
            .map { it.name }
            .toSet()
        for (n in movedNames) {
            assertFalse(
                "RemoteControlReceiver must not declare $n; that action belongs on InternalControlReceiver",
                remoteFields.contains(n),
            )
        }
    }

    @Test fun internalAndServiceWalkawayActionsMatchDeliberately() {
        // The forwarder in InternalControlReceiver passes the action string
        // through to BikeRadarService. Both sides happen to use the same
        // wire string. This test pins that coincidence so a future
        // maintainer who renames one notices the failure on the other side.
        assertEquals(
            BikeRadarService.ACTION_WALKAWAY_DISMISS,
            InternalControlReceiver.ACTION_WALKAWAY_DISMISS,
        )
        assertEquals(
            BikeRadarService.ACTION_WALKAWAY_SNOOZE,
            InternalControlReceiver.ACTION_WALKAWAY_SNOOZE,
        )
    }

    @Test fun receiverClassesArePresent() {
        // Reflection on the class names so the manifest's `<receiver
        // android:name=...>` strings stay in sync with code.
        assertNotNull(Class.forName("es.jjrh.bikeradar.RemoteControlReceiver"))
        assertNotNull(Class.forName("es.jjrh.bikeradar.InternalControlReceiver"))
    }
}
