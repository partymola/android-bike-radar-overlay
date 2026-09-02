// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The rider gets their overlay back.
 *
 * A bare boolean would leave it hidden whenever a consumer went away without
 * saying so, which is every crash. Holding the hide against the asking package
 * is what makes "drop this consumer's hold" expressible at all.
 */
class RadarOverlayGateTest {

    @Before fun clean() = RadarOverlayGate.reset()

    @After fun cleanUp() = RadarOverlayGate.reset()

    @Test
    fun nothingHidesItByDefault() {
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun oneConsumerCanHideAndRestoreIt() {
        RadarOverlayGate.hide("com.example.trailbuddy")
        assertTrue(RadarOverlayGate.hidden)

        RadarOverlayGate.show("com.example.trailbuddy")
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun oneConsumerShowingDoesNotOverrideAnother() {
        // Two apps both drawing their own map. The second releasing its hold
        // must not put the overlay back over the first one's screen.
        RadarOverlayGate.hide("com.example.trailbuddy")
        RadarOverlayGate.hide("com.example.other")

        RadarOverlayGate.show("com.example.other")

        assertTrue("trailbuddy is still asking", RadarOverlayGate.hidden)
    }

    @Test
    fun theLastHolderReleasingRestoresIt() {
        RadarOverlayGate.hide("com.example.trailbuddy")
        RadarOverlayGate.hide("com.example.other")

        RadarOverlayGate.show("com.example.other")
        RadarOverlayGate.show("com.example.trailbuddy")

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun hidingTwiceStillNeedsOneRelease() {
        // Set semantics, not a count: a consumer that re-registers and asks
        // again must not need two releases, or one lost unregister leaves the
        // rider's overlay off for the rest of the session.
        RadarOverlayGate.hide("com.example.trailbuddy")
        RadarOverlayGate.hide("com.example.trailbuddy")

        RadarOverlayGate.show("com.example.trailbuddy")

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun resetDropsEveryHold() {
        RadarOverlayGate.hide("com.example.trailbuddy")
        RadarOverlayGate.hide("com.example.other")

        RadarOverlayGate.reset()

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun showingSomethingThatNeverHidIsHarmless() {
        RadarOverlayGate.hide("com.example.trailbuddy")

        RadarOverlayGate.show("com.example.neverasked")

        assertTrue(RadarOverlayGate.hidden)
    }

    @Test
    fun concurrentHidesAndShowsNeverResurrectAHold() {
        // Holders arrive on binder pool threads while the app's own
        // revalidation drops them from a coroutine, so two of these overlap in
        // ordinary use. A read-compute-write would then let a hide computed
        // from a stale set write back a package the rider has just had removed,
        // restoring the hold of an app with no grant that does not know it is
        // holding - and nothing lifts it until the next write to the store.
        //
        // The assertion is the SET's contents, not that nothing threw: a lost
        // update throws nothing, which is the whole difficulty.
        //
        // The bystanders are what make the kill reliable rather than lucky.
        // Each hide copies the whole set, so a set with some size in it widens
        // the window between reading and writing from almost nothing to
        // something several threads land in. With a handful of entries the
        // unsynchronised version passes most runs, which is worse than no test.
        //
        // What this catches is the FIX being reverted, both mutators together.
        // Reverting one alone it does not catch, measured rather than assumed:
        // the other's compare-and-set retries over the gap and narrows the race
        // past what any load here can find. `RadarOverlayGateIsAtomicTest` is
        // what covers the half-revert, by reading the source.
        val bystanders = (1..BYSTANDERS).map { "com.example.bystander$it" }.toSet()
        bystanders.forEach { RadarOverlayGate.hide(it) }

        val start = CountDownLatch(1)
        val threads = (1..CHURNERS).map { n ->
            Thread {
                start.await()
                repeat(TURNS) {
                    RadarOverlayGate.hide("com.example.holder$n")
                    RadarOverlayGate.show("com.example.holder$n")
                }
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        threads.forEach { assertFalse("a thread is still running", it.isAlive) }

        assertEquals(
            "every hold was lifted by its own owner, and no bystander may have been dropped",
            bystanders,
            RadarOverlayGate.hiddenBy.value,
        )
    }

    private companion object {
        const val CHURNERS = 6
        const val TURNS = 4_000
        const val BYSTANDERS = 400
    }
}
