// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttentionStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun store(name: String) = AttentionStore(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    @Test fun loadIsEmptyWhenUnset() {
        assertTrue(store("empty").load().isEmpty())
    }

    @Test fun saveAndLoadRoundTripsWithAndWithoutValue() {
        val s = store("roundtrip")
        val items = listOf(
            AttentionItem(AttentionKind.RADAR_BATTERY, 12),
            AttentionItem(AttentionKind.AUDIO_FAILURES, 3),
            AttentionItem(AttentionKind.UNCLEAN_RESTART),
        )
        s.save(items)
        assertEquals(items, s.load())
    }

    @Test fun saveEmptyClearsThePreviousSet() {
        val s = store("clear")
        s.save(listOf(AttentionItem(AttentionKind.RADAR_BATTERY, 5)))
        s.save(emptyList())
        assertTrue(s.load().isEmpty())
    }

    @Test fun saveOverwritesThePreviousSet() {
        val s = store("overwrite")
        s.save(listOf(AttentionItem(AttentionKind.RADAR_BATTERY, 5)))
        s.save(listOf(AttentionItem(AttentionKind.EBIKE_BATTERY, 8)))
        assertEquals(listOf(AttentionItem(AttentionKind.EBIKE_BATTERY, 8)), s.load())
    }

    @Test fun loadReturnsEmptyOnCorruptJson() {
        app.getSharedPreferences("corrupt", Context.MODE_PRIVATE).edit()
            .putString("items", "{not valid json").apply()
        assertTrue(store("corrupt").load().isEmpty())
    }

    @Test fun loadSkipsUnknownKindEntries() {
        // A future/removed kind name must be dropped, not crash the whole load.
        app.getSharedPreferences("unknown", Context.MODE_PRIVATE).edit()
            .putString("items", """[{"k":"RADAR_BATTERY","v":9},{"k":"GONE_KIND"}]""").apply()
        assertEquals(listOf(AttentionItem(AttentionKind.RADAR_BATTERY, 9)), store("unknown").load())
    }
}
