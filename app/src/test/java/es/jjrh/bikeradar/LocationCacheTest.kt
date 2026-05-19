// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * State-machine tests for [LocationCache]. The actual `getLastKnownLocation`
 * read needs Android context + permissions and is covered by an instrumented
 * test if one is added later; the JVM tests here pin the cache semantics
 * around [LocationCache.overrideForTest] / [LocationCache.current] /
 * [LocationCache.lastFetchMs].
 */
class LocationCacheTest {

    @After fun tearDown() {
        LocationCache.overrideForTest(null, null, 0L)
    }

    @Test
    fun emptyCacheReturnsNull() {
        LocationCache.overrideForTest(null, null, 0L)
        assertNull(LocationCache.current())
        assertEquals(0L, LocationCache.lastFetchMs())
    }

    @Test
    fun populatedCacheReturnsLatLonAndTimestamp() {
        LocationCache.overrideForTest(51.5, -0.1, 12345L)
        val current = LocationCache.current()
        assertNotNull(current)
        assertEquals(51.5, current!!.first, 0.0001)
        assertEquals(-0.1, current.second, 0.0001)
        assertEquals(12345L, LocationCache.lastFetchMs())
    }

    @Test
    fun overrideClearsAndRepopulates() {
        LocationCache.overrideForTest(40.4, -3.7, 1000L)
        assertEquals(40.4, LocationCache.current()!!.first, 0.0001)
        LocationCache.overrideForTest(null, null, 0L)
        assertNull(LocationCache.current())
        LocationCache.overrideForTest(-33.9, 151.2, 2000L)
        assertEquals(-33.9, LocationCache.current()!!.first, 0.0001)
        assertEquals(2000L, LocationCache.lastFetchMs())
    }

    @Test
    fun partialOverrideTreatedAsEmpty() {
        // Defensive: a half-populated cache (lat set, lon null) reads as
        // empty rather than as a corrupt half-known location. Guards against
        // a future refactor that forgets to set both.
        LocationCache.overrideForTest(51.5, null, 1L)
        assertNull(LocationCache.current())
        LocationCache.overrideForTest(null, -0.1, 1L)
        assertNull(LocationCache.current())
    }
}
