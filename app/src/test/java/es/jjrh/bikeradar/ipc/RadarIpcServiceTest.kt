// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.security.MessageDigest

/**
 * The service shell: what it starts, what it hands out, and what it tears
 * down.
 *
 * Every decision about who may read or act lives in [RadarIpcBinder], which
 * has its own tests. What is only true here is the lifecycle - that a bind
 * returns a working binder, that the frame feed and the grant-revalidation
 * subscription are actually started, and that going away releases the overlay
 * rather than leaving a rider without one.
 *
 * Both subscriptions are driven from their real source, the state bus and the
 * grant store, rather than by calling the binder. Calling it would leave the
 * launch itself unpinned, so deleting either subscription would keep the suite
 * green while every consumer received nothing for the rest of the ride.
 */
@RunWith(RobolectricTestRunner::class)
class RadarIpcServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ServiceController<RadarIpcService>

    private fun store() = PrefsRadarGrantStore(
        context.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE),
    )

    /**
     * Robolectric reports the test process as the caller, so the consumer this
     * service sees IS this package. Installing a signature for it and storing
     * the matching digest is what lets these tests run the REAL gate - package
     * resolution and certificate check included - rather than a stand-in.
     */
    private fun grantSelf(read: Boolean = true, control: Boolean = false) {
        val cert = byteArrayOf(1, 2, 3, 4)
        val signing = SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(Signature(cert)))
        val info = PackageInfo().apply {
            packageName = context.packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = context.packageName
                uid = android.os.Process.myUid()
                name = "Bike Radar"
            }
            signingInfo = signing
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(android.os.Process.myUid(), context.packageName)
        val digest = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        store().put(
            RadarGrant(context.packageName, digest, "Bike Radar", 0L, 0L, read = read, control = control),
        )
    }

    @Before
    fun setUp() {
        RadarOverlayGate.reset()
        RadarStateBus.clear()
        context.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        controller = Robolectric.buildService(RadarIpcService::class.java)
    }

    @After
    fun tearDown() {
        RadarOverlayGate.reset()
        RadarStateBus.clear()
        // Both are process-global and the battery tests write to them, so a
        // later test would otherwise read this one's radars.
        BatteryStateBus.clearForTest()
        BikeRadarService.macToSlug.clear()
        Prefs(context).radarMac = null
    }

    @Test
    fun bindingReturnsAWorkingBinder() {
        val service = controller.create().get()

        val binder = service.onBind(Intent(RadarContract.ACTION))

        assertNotNull("nothing to bind to", binder)
        assertTrue(binder is RadarIpcBinder)
        assertEquals(
            "the version must answer without any grant",
            RadarContract.VERSION,
            (binder as RadarIpcBinder).contractVersion,
        )
    }

    @Test
    fun bindingTwiceHandsOutTheSameBinder() {
        // One registry, or a consumer's registration and its overlay hold
        // would live on an object the next bind cannot see.
        val service = controller.create().get()

        val first = service.onBind(Intent(RadarContract.ACTION))
        val second = service.onBind(Intent(RadarContract.ACTION))

        assertTrue(first === second)
    }

    @Test
    fun everyConsumerLeavingRestoresTheOverlay() {
        val service = controller.create().get()
        service.onBind(Intent(RadarContract.ACTION))
        RadarOverlayGate.hide("com.example.trailbuddy")

        service.onUnbind(Intent(RadarContract.ACTION))

        assertFalse("nobody is left to lift the hold themselves", RadarOverlayGate.hidden)
    }

    @Test
    fun stoppingTheServiceRestoresTheOverlay() {
        controller.create().get()
        RadarOverlayGate.hide("com.example.trailbuddy")

        controller.destroy()

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun revokingInSettingsDropsALiveStreamWithoutWaitingForAnUnbind() {
        // Driven from the grant store, not from the binder: the subscription
        // between the two is the thing under test, and asserting the store's
        // own write counter would certify this property without checking it.
        val service = controller.create().get()
        val binder = service.binder
        val listener = RecordingListener()

        grantSelf()
        assertTrue("the grant must let it register", binder.registerTargetListener(listener))

        store().revoke(context.packageName)

        // The subscription runs on a real background dispatcher, so this waits
        // for the effect rather than assuming it has already landed. Bounded,
        // so a broken subscription fails the test rather than hanging it.
        var dropped = false
        val deadline = System.currentTimeMillis() + 5_000
        while (!dropped && System.currentTimeMillis() < deadline) {
            listener.received.clear()
            binder.broadcast(liveState())
            dropped = listener.received.isEmpty()
            if (!dropped) Thread.sleep(20)
        }

        assertTrue("a revoked app must stop receiving without waiting to unbind", dropped)
    }

    @Test
    fun rebindingAfterEveryConsumerLeftStillWorks() {
        // A consumer binding in onStart and unbinding in onStop rebinds on
        // every rotation. Killing the callback list on unbind would refuse its
        // registration for the life of the service instance, and the consumer
        // could not tell that from having no grant.
        val service = controller.create().get()
        val binder = service.binder
        grantSelf()
        service.onBind(Intent(RadarContract.ACTION))
        binder.registerTargetListener(RecordingListener())

        service.onUnbind(Intent(RadarContract.ACTION))
        service.onBind(Intent(RadarContract.ACTION))

        assertTrue(
            "a rebind must be able to register again",
            binder.registerTargetListener(RecordingListener()),
        )
    }

    @Test
    fun aFrameOnTheBusReachesARegisteredConsumer() {
        // The whole happy path, end to end, and the only test that drives the
        // feed from where the radar actually publishes. Every other test here
        // calls `broadcast` directly, so deleting the feed's launch would leave
        // them all green while no consumer ever received a frame.
        val service = controller.create().get()
        val binder = service.binder
        val listener = RecordingListener()
        grantSelf()
        assertTrue(binder.registerTargetListener(listener))

        RadarStateBus.publish(liveState())

        // The collector runs on a real background dispatcher, so wait for the
        // effect rather than assuming it has landed. Bounded, so a feed that
        // was never started fails rather than hangs.
        val deadline = System.currentTimeMillis() + 5_000
        while (listener.received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals("a frame published to the bus must reach the consumer", 1, listener.received.size)
        assertTrue(listener.received.single().streamLive)
    }

    @Test
    fun thePinnedRadarsBatteryIsWhatCrossesTheWire() {
        // The contract promises the PRIMARY radar, and with two radars bonded
        // an unordered map is the difference between honouring that and
        // answering with the other bike's unit under a documented promise. The
        // rider's pin is what makes it exact, so this is the case worth pinning.
        val service = controller.create().get()
        grantSelf()
        Prefs(context).radarMac = "AA:BB:CC:DD:EE:FF"
        BikeRadarService.macToSlug["AA:BB:CC:DD:EE:FF"] = "pinned"
        BatteryStateBus.update(BatteryEntry("other", "OtherRadar", 11))
        BatteryStateBus.update(BatteryEntry("pinned", "TestRadar", 88))

        assertEquals(88, service.binder.batteryPercent)
    }

    @Test
    fun anUppercaseStoredMacStillFindsThePinnedRadar() {
        // The pin is stored as the rider's system settings spell it and the
        // slug map is keyed as the scan reported it, and the two disagree on
        // case. Without the retry the pin silently stops applying and the
        // answer falls back to whichever radar the map yields first.
        val service = controller.create().get()
        grantSelf()
        Prefs(context).radarMac = "aa:bb:cc:dd:ee:ff"
        BikeRadarService.macToSlug["AA:BB:CC:DD:EE:FF"] = "pinned"
        BatteryStateBus.update(BatteryEntry("other", "OtherRadar", 11))
        BatteryStateBus.update(BatteryEntry("pinned", "TestRadar", 88))

        assertEquals(88, service.binder.batteryPercent)
    }

    @Test
    fun withNoPinTheAnswerIsStillARadarRatherThanAnyDevice() {
        // No pin is the ordinary case, and the fallback has to be name-matched:
        // the same map carries the dashcam, whose battery is not what a
        // consumer asked for. "TestRearRadar" rather than "TestRadar" because
        // the matcher looks for what these units actually advertise, and a
        // fixture the matcher rejects would test the empty answer instead.
        val service = controller.create().get()
        grantSelf()
        Prefs(context).radarMac = null
        BatteryStateBus.update(BatteryEntry("dashcam", "TestDashcam", 22))
        BatteryStateBus.update(BatteryEntry("radar", "TestRearRadar", 66))

        assertEquals(66, service.binder.batteryPercent)
    }

    @Test
    fun aStartedBindOnlyServiceStopsItselfAndDoesNotComeBack() {
        // Exported behind an install-granted permission, so any app can start
        // it. Left to the default it would be restarted after a kill with no
        // client - both collectors running, `onUnbind` never firing, and every
        // registration held. The stop is the half that closes the live case.
        val service = controller.create().get()

        val mode = service.onStartCommand(Intent(RadarContract.ACTION), 0, 7)

        assertEquals(Service.START_NOT_STICKY, mode)
        assertTrue("a start with no client must not pin the service", shadowOf(service).isStoppedBySelf)
    }

    private fun liveState() = RadarState(
        vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = -5f)),
        source = DataSource.V2,
    )

    private class RecordingListener : IRadarListener.Stub() {
        val received = mutableListOf<RadarStateParcel>()
        override fun onRadarState(state: RadarStateParcel?) {
            state?.let { received += it }
        }
    }
}
