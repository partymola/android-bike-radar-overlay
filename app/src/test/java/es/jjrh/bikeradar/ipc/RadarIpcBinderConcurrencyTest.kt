// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.os.RemoteCallbackList
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.access.CallerIdentity
import es.jjrh.bikeradar.access.PackageIdentity
import es.jjrh.bikeradar.access.RadarAccessGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Three threads reach one `RemoteCallbackList`.
 *
 * The frame feed and the grant-revalidation collector run on separate
 * coroutines on the same dispatcher, and `dropRegistrations` runs on a binder
 * pool thread whenever a consumer registers - which is the ordinary connect
 * path, during a ride. `RemoteCallbackList` permits one broadcast at a time.
 *
 * The failure this guards is silent and permanent rather than noisy: the throw
 * escapes `collectLatest` and cancels that collector, and the SupervisorJob
 * keeps the service alive with nothing to restart it. Either every consumer's
 * stream stops for good, or revalidation stops and a revoked app keeps
 * receiving frames - the one thing the held-grant design depends on.
 */
@RunWith(RobolectricTestRunner::class)
class RadarIpcBinderConcurrencyTest {

    private val uid = 10101

    private val identity = object : PackageIdentity {
        override fun resolve(uid: Int) = CallerIdentity("com.example.trailbuddy", "Trail Buddy")
        override fun digests(packageName: String) = setOf("aa11")
        override fun uidOf(packageName: String) = uid
    }

    private val gate = object : RadarAccessGate {
        override fun canRead(uid: Int) = true
        override fun canControl(uid: Int) = true
    }

    private fun binder() = RadarIpcBinder(
        gate = gate,
        identity = identity,
        radarState = { state },
        batteryPercent = { 50 },
        setLightMode = { _: RadarLightMode -> true },
        markUsed = { },
        callingUid = { uid },
    )

    private val state = RadarState(
        vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = -5f)),
        source = DataSource.V2,
    )

    private class Listener : IRadarListener.Stub() {
        override fun onRadarState(state: RadarStateParcel?) = Unit
    }

    @Before fun clean() = RadarOverlayGate.reset()

    @After fun cleanUp() = RadarOverlayGate.reset()

    @Test
    fun overlappingBroadcastsAreWhatTheLockPrevents() {
        // The premise, measured rather than assumed: without serialisation a
        // second broadcast on the same list throws. If this ever stops
        // throwing, the lock is no longer load-bearing and this file can go.
        val list = RemoteCallbackList<IRadarListener>()
        // The register is load-bearing: the guard is on the broadcast COUNT, so
        // on an empty list a second beginBroadcast would not throw and this
        // would prove nothing.
        list.register(Listener(), "com.example.trailbuddy")
        list.beginBroadcast()
        try {
            assertThrows(IllegalStateException::class.java) { list.beginBroadcast() }
        } finally {
            list.finishBroadcast()
        }
    }

    @Test
    fun registeringDuringAStreamOfFramesNeverThrows() {
        // The real collision: a consumer connecting mid-ride while the feed is
        // delivering. `registerTargetListener` takes a broadcast to drop any
        // previous registration, and the feed is taking one to deliver.
        val b = binder()
        b.registerTargetListener(Listener())
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val turns = AtomicInteger(0)

        val feed = Thread {
            start.await()
            repeat(FEED_TURNS) {
                runCatching { b.broadcast(state) }.onFailure { failure.compareAndSet(null, it) }
                turns.incrementAndGet()
            }
        }
        val registrations = Thread {
            start.await()
            repeat(OTHER_TURNS) {
                runCatching { b.registerTargetListener(Listener()) }.onFailure { failure.compareAndSet(null, it) }
                turns.incrementAndGet()
            }
        }
        val revalidations = Thread {
            start.await()
            repeat(OTHER_TURNS) {
                runCatching { b.revalidate() }.onFailure { failure.compareAndSet(null, it) }
                turns.incrementAndGet()
            }
        }

        val threads = listOf(feed, registrations, revalidations)
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        // join() returns without throwing on timeout, so a thread deadlocked on
        // the monitor would leave the loop above complete and `failure` null -
        // a green result on precisely the risk a new lock introduces. Assert
        // each thread actually finished.
        threads.forEach { assertFalse("a thread is still running: deadlock", it.isAlive) }
        // And that every turn ran. A method that returned early - a guard
        // inverted, a package no longer resolving - would leave no collisions
        // to find, so the absence of a throw would prove nothing.
        assertEquals(FEED_TURNS + 2 * OTHER_TURNS, turns.get())
        assertNull("a collision here kills the feed or revalidation for good", failure.get())
    }

    private companion object {
        // A collision is a race, so a green run is not by itself proof the lock
        // is there: these counts make a collision near enough to certain over a
        // run, not certain. Removing the lock from any one site is a weaker
        // defect than removing it from all of them, and only the second is what
        // this reliably catches.
        const val FEED_TURNS = 400
        const val OTHER_TURNS = 200
    }
}
