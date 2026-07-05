// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [AttentionCopy] map so the notification and the home card can't
 * drift: every kind resolves to the right resource and carries its value /
 * count into the [AttentionCopy.Line].
 */
class AttentionCopyTest {

    @Test fun batteryKindsCarryTheirPercentAsSimpleArg() {
        val radar = AttentionCopy.lineFor(AttentionItem(AttentionKind.RADAR_BATTERY, 12))
        assertEquals(AttentionCopy.Line.Simple(R.string.attention_radar_battery, 12), radar)

        val dashcam = AttentionCopy.lineFor(AttentionItem(AttentionKind.DASHCAM_BATTERY, 7))
        assertEquals(AttentionCopy.Line.Simple(R.string.attention_dashcam_battery, 7), dashcam)

        val ebike = AttentionCopy.lineFor(AttentionItem(AttentionKind.EBIKE_BATTERY, 18))
        assertEquals(AttentionCopy.Line.Simple(R.string.attention_ebike_battery, 18), ebike)
    }

    @Test fun audioFailuresIsAPluralCarryingItsCount() {
        val line = AttentionCopy.lineFor(AttentionItem(AttentionKind.AUDIO_FAILURES, 4))
        assertEquals(AttentionCopy.Line.Plural(R.plurals.attention_audio_failures, 4), line)
    }

    @Test fun audioFailuresNullValueDefaultsToZeroCount() {
        val line = AttentionCopy.lineFor(AttentionItem(AttentionKind.AUDIO_FAILURES, null))
        assertEquals(AttentionCopy.Line.Plural(R.plurals.attention_audio_failures, 0), line)
    }

    @Test fun uncleanRestartHasNoArg() {
        val line = AttentionCopy.lineFor(AttentionItem(AttentionKind.UNCLEAN_RESTART))
        assertEquals(AttentionCopy.Line.Simple(R.string.attention_unclean_restart, null), line)
    }
}
