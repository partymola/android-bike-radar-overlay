// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * What another app gets, and what it does not.
 *
 * The gate is the whole point of this object, so every method is checked
 * refused as well as allowed. A test that only proves the allowed path passes
 * just as well against a binder that never checks anything.
 */
@RunWith(RobolectricTestRunner::class)
class RadarIpcBinderTest {

    // The wire values a shipped consumer sends, as literals rather than
    // RadarContract's constants, which are the thing under test.
    private val wireNightFlash = 0
    private val wireDayFlash = 1
    private val wireSolid = 2
    private val wirePeloton = 3
    private val wireOff = 4

    private val trailBuddyUid = 10101
    private val strangerUid = 10202

    /**
     * Distinct per uid, so a binder that resolves the wrong caller is visible.
     *
     * [installed] is what makes an uninstall reachable: a package that is gone
     * resolves to no uid at all, which is a different answer from a refusal and
     * is not something a fixed table can produce.
     */
    private var installed = true

    private val identity = object : PackageIdentity {
        override fun resolve(uid: Int): CallerIdentity? = when (uid) {
            trailBuddyUid -> CallerIdentity("com.example.trailbuddy", "Trail Buddy")
            strangerUid -> CallerIdentity("com.example.stranger", "Stranger")
            else -> null
        }

        override fun digests(packageName: String): Set<String> = setOf("aa11")

        override fun uidOf(packageName: String): Int? = when {
            !installed -> null
            packageName == "com.example.trailbuddy" -> trailBuddyUid
            packageName == "com.example.stranger" -> strangerUid
            else -> null
        }
    }

    /**
     * Answers per uid rather than a single flag, so a swapped caller shows up,
     * and mutable so a grant can be withdrawn from a LIVE binder - which is
     * what revocation actually is. A second binder would have its own callback
     * list and prove nothing.
     */
    private class Gate(var read: Set<Int>, var control: Set<Int>) : RadarAccessGate {
        /**
         * The gate reaches the PackageManager, which throws when the system
         * server is having a bad day. Nothing else here can produce that, and
         * the difference between one package failing and the whole revalidation
         * being abandoned is a rider's revoke that did or did not happen.
         */
        var throwFor: Int? = null

        override fun canRead(uid: Int): Boolean {
            if (uid == throwFor) throw IllegalStateException("package manager died")
            return uid in read
        }

        override fun canControl(uid: Int) = uid in control
    }

    private lateinit var gate: Gate

    private var uid = trailBuddyUid
    private var lightModeSet: RadarLightMode? = null
    private var lightWriteSucceeds = true
    private var battery: Int? = 77
    private val stamped = mutableListOf<String>()
    private var nowMs = 1_000_000L
    private var state = RadarState(
        vehicles = listOf(Vehicle(id = 1, distanceM = 30, speedMs = -6f)),
        source = DataSource.V2,
        bikeSpeedMs = 5f,
    )

    private fun binder(
        read: Set<Int> = setOf(trailBuddyUid),
        control: Set<Int> = emptySet(),
    ) = RadarIpcBinder(
        gate = Gate(read, control).also { gate = it },
        identity = identity,
        radarState = { state },
        batteryPercent = { battery },
        setLightMode = {
            lightModeSet = it
            lightWriteSucceeds
        },
        markUsed = { stamped += it },
        callingUid = { uid },
        clock = { nowMs },
    )

    private class Listener : IRadarListener.Stub() {
        val received = mutableListOf<RadarStateParcel>()
        override fun onRadarState(state: RadarStateParcel?) {
            state?.let { received += it }
        }
    }

    /**
     * A listener that is a different OBJECT wrapping the same binder.
     *
     * This is what a real consumer's call looks like on our side and what a
     * local test otherwise cannot produce: `asInterface` mints a fresh proxy
     * per transaction, so the interface reference we are handed is never the
     * one the registry holds while the binder underneath is. Anything here
     * comparing interface references passes every other test in this file and
     * fails for every app that ever ships.
     */
    private class IdentityOnlyListener(private val real: IRadarListener) : IRadarListener {
        override fun asBinder() = real.asBinder()
        override fun onRadarState(state: RadarStateParcel?) = Unit
    }

    @Before fun clean() {
        ShadowLog.clear()
        RadarOverlayGate.reset()
    }

    @After fun cleanUp() = RadarOverlayGate.reset()

    @Test
    fun theVersionIsAnsweredWithoutAnyGrant() {
        // The one ungated method: a consumer must be able to find out whether
        // it speaks our layout before asking the rider for anything.
        uid = strangerUid

        assertEquals(RadarContract.VERSION, binder(read = emptySet()).contractVersion)
    }

    @Test
    fun readingIsRefusedWithoutAGrant() {
        uid = strangerUid
        val b = binder(read = setOf(trailBuddyUid))

        assertFalse(b.registerTargetListener(Listener()))
        assertFalse(b.isConnected)
        assertEquals(RadarIpcBinder.NO_READING, b.batteryPercent)
    }

    @Test
    fun readingIsAllowedWithOne() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))

        assertTrue(b.registerTargetListener(Listener()))
        assertTrue(b.isConnected)
        assertEquals(77, b.batteryPercent)
    }

    @Test
    fun aReadGrantDoesNotCarryControl() {
        // The two are separate switches on the consent screen and must be
        // separate here, or the rider's "see only" answer is not honoured.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = emptySet())

        assertFalse(b.setRadarLightMode(wireSolid))
        assertFalse(b.setOverlayVisible(false))
        assertEquals("the light must not have been written", null, lightModeSet)
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun controlIsAllowedWithItsOwnGrant() {
        uid = trailBuddyUid
        val b = binder(read = emptySet(), control = setOf(trailBuddyUid))

        assertTrue(b.setRadarLightMode(wirePeloton))
        assertEquals(RadarLightMode.PELOTON, lightModeSet)
    }

    @Test
    fun aControlGrantDoesNotCarryRead() {
        uid = trailBuddyUid
        val b = binder(read = emptySet(), control = setOf(trailBuddyUid))

        assertFalse(b.isConnected)
        assertEquals(RadarIpcBinder.NO_READING, b.batteryPercent)
    }

    @Test
    fun theWireValueOfEveryLightModeIsFixed() {
        // Literals on both sides. Writing `RadarLightMode.X.ordinal` would
        // restate the enum against itself and move with any reorder, which is
        // the coupling the wire constants exist to break.
        uid = trailBuddyUid
        val b = binder(control = setOf(trailBuddyUid))

        for ((wire, expected) in listOf(
            wireNightFlash to RadarLightMode.NIGHT_FLASH,
            wireDayFlash to RadarLightMode.DAY_FLASH,
            wireSolid to RadarLightMode.SOLID,
            wirePeloton to RadarLightMode.PELOTON,
            wireOff to RadarLightMode.OFF,
        )) {
            lightModeSet = null
            assertTrue("wire $wire must be accepted", b.setRadarLightMode(wire))
            assertEquals("wire $wire", expected, lightModeSet)
        }
    }

    @Test
    fun everyLightModeIsReachableOverTheContract() {
        // The `when` is over an Int, so it is not exhaustiveness-checked: a
        // sixth RadarLightMode with no wire value compiles, and is simply
        // unsettable by any consumer for ever, with nothing failing. That is
        // the safe direction and still a decision somebody should take
        // deliberately, so this is what makes them take it.
        uid = trailBuddyUid
        val b = binder(control = setOf(trailBuddyUid))

        val reached = buildSet {
            for (wire in -1..64) {
                lightModeSet = null
                if (b.setRadarLightMode(wire)) add(lightModeSet)
            }
        }
        assertEquals(RadarLightMode.entries.toSet(), reached)
    }

    @Test
    fun anOutOfRangeLightModeIsRefusedRatherThanCoerced() {
        // A consumer built against a later version can send a value this build
        // has no meaning for. Writing an arbitrary mode to the rider's tail
        // light is the wrong answer.
        uid = trailBuddyUid
        val b = binder(control = setOf(trailBuddyUid))

        assertFalse("one past the last mode is the boundary that matters", b.setRadarLightMode(5))
        assertFalse(b.setRadarLightMode(99))
        assertFalse(b.setRadarLightMode(-1))
        assertEquals(null, lightModeSet)
    }

    @Test
    fun aRefusedLightWriteIsReportedAsFailure() {
        uid = trailBuddyUid
        lightWriteSucceeds = false
        val b = binder(control = setOf(trailBuddyUid))

        assertFalse("no radar linked means no", b.setRadarLightMode(wireOff))
    }

    @Test
    fun aRegisteredListenerReceivesSnapshots() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)

        b.broadcast(state)

        assertEquals(1, listener.received.size)
        assertEquals(1, listener.received[0].vehicles.size)
        assertTrue(listener.received[0].streamLive)
    }

    @Test
    fun anUnregisteredListenerReceivesNothing() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)
        b.unregisterTargetListener(listener)

        b.broadcast(state)

        assertTrue(listener.received.isEmpty())
    }

    @Test
    fun asecondRegistrationFromOnePackageReplacesTheFirst() {
        // Not refused: a consumer reconnecting after its own crash would
        // otherwise believe it held a stream and receive nothing.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val first = Listener()
        val second = Listener()
        b.registerTargetListener(first)

        assertTrue(b.registerTargetListener(second))
        b.broadcast(state)

        assertTrue("the replaced listener must stop receiving", first.received.isEmpty())
        assertEquals(1, second.received.size)
    }

    @Test
    fun revokingDropsThatPackageAndLeavesOthersAlone() {
        val b = binder(read = setOf(trailBuddyUid, strangerUid))
        uid = trailBuddyUid
        val buddy = Listener()
        b.registerTargetListener(buddy)
        uid = strangerUid
        val stranger = Listener()
        b.registerTargetListener(stranger)

        b.revoke("com.example.trailbuddy")
        b.broadcast(state)

        assertTrue("the revoked app must stop receiving", buddy.received.isEmpty())
        assertEquals("the other app is unaffected", 1, stranger.received.size)
    }

    @Test
    fun revalidatingDropsAListenerWhoseGrantWentAway() {
        // The grant is checked once at registration and then held, because
        // resolving a package per frame is not free. That trade is only honest
        // if something invalidates the held answer, and this is it. Without
        // it, "stop sharing" means "stop sharing whenever the app next
        // unbinds", which on a live ride is not what the rider asked for.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)

        gate.read = emptySet()
        b.revalidate()
        b.broadcast(state)

        assertTrue(listener.received.isEmpty())
    }

    @Test
    fun revalidatingKeepsAListenerWhoseGrantStands() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)

        b.revalidate()
        b.broadcast(state)

        assertEquals("an untouched grant must not be collateral", 1, listener.received.size)
    }

    @Test
    fun revalidatingDropsOnlyTheRevokedPackage() {
        val b = binder(read = setOf(trailBuddyUid, strangerUid))
        uid = trailBuddyUid
        val buddy = Listener()
        b.registerTargetListener(buddy)
        uid = strangerUid
        val stranger = Listener()
        b.registerTargetListener(stranger)

        gate.read = setOf(strangerUid)
        b.revalidate()
        b.broadcast(state)

        assertTrue(buddy.received.isEmpty())
        assertEquals(1, stranger.received.size)
    }

    @Test
    fun revalidatingAlsoLiftsTheRevokedPackagesOverlayHold() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        gate.read = emptySet()
        b.revalidate()

        assertFalse("revoking must give the rider their overlay back", RadarOverlayGate.hidden)
    }

    @Test
    fun revokingLiftsThatPackagesOverlayHold() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        b.revoke("com.example.trailbuddy")

        assertFalse("the rider gets their overlay back", RadarOverlayGate.hidden)
    }

    @Test
    fun unregisteringLiftsTheOverlayHold() {
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)
        b.setOverlayVisible(false)

        b.unregisterTargetListener(listener)

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun unregisteringWorksAfterTheGrantIsGone() {
        // Ungated deliberately: a revoked consumer must still be able to
        // withdraw, or it holds a registration it cannot get rid of.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)

        gate.read = emptySet()
        b.unregisterTargetListener(listener)

        b.broadcast(state)
        assertTrue(listener.received.isEmpty())
    }

    @Test
    fun aConsumerCrashingRestoresTheOverlay() {
        // The one exit where the consumer runs no code of its own, so it is
        // the likeliest way a hold gets left behind. Driven through the method
        // the death recipient calls: a test's listener is a local object in
        // this process and never actually dies.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        // Through the real death path, not the helper it calls: the two-arg
        // override and the cookie cast are where a silent mistake lives.
        b.listeners.onCallbackDied(listener, "com.example.trailbuddy")

        assertFalse("a crashed app must not cost the rider their overlay", RadarOverlayGate.hidden)
    }

    @Test
    fun hidingTheOverlayNeedsALiveRegistration() {
        // The hold has to be anchored to something that dies. A registered
        // listener has a death recipient; a bare control grant has nothing, so
        // a consumer that hid our display and then crashed would leave the
        // rider with no collision warning and no way back.
        uid = trailBuddyUid
        val b = binder(read = emptySet(), control = setOf(trailBuddyUid))

        assertFalse("no registration, no hold", b.setOverlayVisible(false))
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun showingSurvivesLosingTheReadGrant() {
        // Restoring must always be available, or a consumer whose read grant
        // went away is left unable to undo its own hide.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        gate.read = emptySet()
        assertTrue(b.setOverlayVisible(true))
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun showingPutsTheOverlayBack() {
        // The `true` arm: every other test here passes false, so a binder that
        // hid on both would pass them all while ignoring a consumer politely
        // giving the rider their display back.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        assertTrue(b.setOverlayVisible(true))

        assertFalse("show must show", RadarOverlayGate.hidden)
    }

    @Test
    fun losingControlAloneLiftsTheHold() {
        // The consent screen takes read and control separately and a rider can
        // re-run it to change their mind. Narrowing to read only has to lift
        // the hold, because the hold is a control-grant thing and the stream
        // it was taken for carries on.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        gate.control = emptySet()
        b.revalidate()

        assertFalse("turning control off must give the overlay back", RadarOverlayGate.hidden)
    }

    @Test
    fun showingNeedsNoGrantAtAll() {
        // Restoring the rider's own display is never privileged, and this is
        // the escape hatch that makes any stranded hold recoverable.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)

        gate.read = emptySet()
        gate.control = emptySet()

        assertTrue(b.setOverlayVisible(true))
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun anUnregisteredCallerCannotDeregisterAnotherApp() {
        // Unregistering needs no grant, so any app that can bind can try it,
        // and the caller having no registration of its own is the shape the
        // guard has to cover rather than the one it can skip.
        val b = binder(read = setOf(trailBuddyUid, strangerUid))
        uid = trailBuddyUid
        val victim = Listener()
        b.registerTargetListener(victim)

        uid = strangerUid
        b.unregisterTargetListener(victim)

        b.broadcast(state)
        assertEquals("the victim must still be receiving", 1, victim.received.size)
    }

    @Test
    fun tearDownRestoresTheOverlayForEveryone() {
        val b = binder(read = setOf(trailBuddyUid, strangerUid), control = setOf(trailBuddyUid, strangerUid))
        uid = trailBuddyUid
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)
        uid = strangerUid
        b.registerTargetListener(Listener())
        b.setOverlayVisible(false)

        b.releaseAll()

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun aStreamWithNoRadarReportsNotLiveRatherThanClear() {
        // The failure this contract exists to prevent: an app with no radar
        // and a radar seeing an empty road are otherwise identical on the
        // wire, and a consumer reads the first as an all-clear.
        uid = trailBuddyUid
        state = RadarState(source = DataSource.NONE)
        val b = binder(read = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)

        b.broadcast(state)

        assertFalse(b.isConnected)
        assertFalse(listener.received.single().streamLive)
    }

    @Test
    fun anUnresolvableCallerGetsNothing() {
        // A shared UID resolves to no single package, so there is no honest
        // answer to who is asking.
        uid = 99999
        val b = binder(read = setOf(99999), control = setOf(99999))

        assertFalse(b.registerTargetListener(Listener()))
        assertFalse(b.isConnected)
        assertFalse(b.setOverlayVisible(false))
        // Showing is ungated, so this is the one method that could report
        // success without anything happening: there is no package to lift a
        // hold for, and a consumer told "done" would stop trying.
        assertFalse(b.setOverlayVisible(true))
        // The only method that touches the rider's hardware, so it completes
        // the refused-path table where it matters most.
        assertFalse(b.setRadarLightMode(wireOff))
        assertEquals(RadarIpcBinder.NO_READING, b.batteryPercent)
    }

    @Test
    fun aMissingBatteryReadingIsNotZero() {
        // Zero percent is a real reading a consumer would show as a flat
        // battery; absence has to be distinguishable from it. The literal is
        // the point: asserting NO_READING against itself passes just as well
        // when the constant IS zero, and -1 is a published wire value that
        // every shipped consumer has already read out of the contract.
        uid = trailBuddyUid
        battery = null

        assertEquals(-1, binder(read = setOf(trailBuddyUid)).batteryPercent)
        assertEquals("the constant is that wire value", -1, RadarIpcBinder.NO_READING)
    }

    @Test
    fun unregisteringWorksThroughADifferentInterfaceObject() {
        // What a real consumer's unregister looks like from here. Every other
        // test in this file hands back the very object it registered, which no
        // cross-process caller can do, so a reference comparison passes all of
        // them and refuses every app that ever ships. The consequence is not
        // just a leaked registration: the hold below never lifts either, so the
        // rider keeps a screen with no radar on it.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)
        b.setOverlayVisible(false)
        assertTrue(RadarOverlayGate.hidden)

        b.unregisterTargetListener(IdentityOnlyListener(listener))

        b.broadcast(state)
        assertTrue("the registration must be gone", listener.received.isEmpty())
        assertFalse("and the overlay must come back", RadarOverlayGate.hidden)
    }

    @Test
    fun anotherAppsRegistrationDoesNotAuthoriseAHide() {
        // The registration has to be the CALLER'S. A binder that only checked
        // whether anybody at all was registered would anchor this hold to a
        // stranger's binder, so the holder crashing would not lift it.
        val b = binder(read = setOf(strangerUid), control = setOf(trailBuddyUid))
        uid = strangerUid
        b.registerTargetListener(Listener())

        uid = trailBuddyUid
        assertFalse("someone else's registration is not an anchor", b.setOverlayVisible(false))
        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun anUninstalledConsumerLosesItsHold() {
        // An uninstalled package resolves to no uid at all, which the gate
        // never sees as a refusal because it is never asked. Left out, a
        // consumer could be uninstalled with its hold still standing.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        val listener = Listener()
        b.registerTargetListener(listener)
        b.setOverlayVisible(false)

        installed = false
        b.revalidate()

        b.broadcast(state)
        assertTrue("an uninstalled app must stop receiving", listener.received.isEmpty())
        assertFalse("and must not keep the rider's overlay", RadarOverlayGate.hidden)
    }

    @Test
    fun revalidateLiftsAHoldWithNoRegistration() {
        // Taking a hold requires a registration, so this state is unreachable
        // through the interface today. It is pinned because the rider's revoke
        // is the only remedy for a stranded hold: a later path that took one
        // without registering would otherwise put the hold somewhere revoke
        // cannot see, and nothing would say so.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid), control = setOf(trailBuddyUid))
        RadarOverlayGate.hide("com.example.trailbuddy")

        gate.read = emptySet()
        b.revalidate()

        assertFalse(RadarOverlayGate.hidden)
    }

    @Test
    fun streamingCountsAsUse() {
        // The settings list shows how long ago each app last read the radar,
        // and the stream is the loudest thing an app does and the only path
        // that never re-enters the gate. Without this the app receiving most
        // is reported as the one gone quietest.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        stamped.clear()

        b.broadcast(state)

        assertEquals(listOf("com.example.trailbuddy"), stamped)
    }

    @Test
    fun everyFrameDoesNotRestampTheSameConsumer() {
        // The settings list shows an age, not a moment, so a stamp a minute is
        // the same picture. Every frame instead means re-reading and parsing
        // the whole grant file per consumer for the length of a ride, because
        // the store applies its own floor only after that read.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        b.registerTargetListener(Listener())
        stamped.clear()

        b.broadcast(state)
        b.broadcast(state)
        nowMs += 30_000L
        b.broadcast(state)

        assertEquals("one stamp inside the interval", listOf("com.example.trailbuddy"), stamped)

        nowMs += 31_000L
        b.broadcast(state)

        assertEquals(
            "and another once it has passed, or the list freezes on a live app",
            listOf("com.example.trailbuddy", "com.example.trailbuddy"),
            stamped,
        )
    }

    @Test
    fun aRecoveredConsumerIsReportedAgainIfItFailsAgain() {
        // The suppression is per bad spell, not for the life of the binder. A
        // consumer that wedges on one ride and again on another is two
        // incidents, and the second is the one a maintainer would be reading
        // about - so the assertion is on the LOG, which is the whole of what
        // this behaviour produces.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        var throwNow = true
        b.registerTargetListener(
            object : IRadarListener.Stub() {
                override fun onRadarState(state: RadarStateParcel?) {
                    if (throwNow) throw IllegalStateException("buffer full")
                }
            },
        )

        b.broadcast(state)
        b.broadcast(state)
        assertEquals("one spell, one line", 1, wedgedLines())

        throwNow = false
        b.broadcast(state)
        throwNow = true
        b.broadcast(state)

        assertEquals("a second spell is its own incident", 2, wedgedLines())
    }

    private fun wedgedLines() = ShadowLog.getLogs()
        .count { it.msg?.contains("stopped taking frames") == true }

    @Test
    fun aWedgedConsumerThatComesBackIsReportedAgain() {
        // "Once per spell" has to survive a registration boundary too. A
        // consumer that wedges, goes away and comes back wedged - a crash loop,
        // which is the likeliest shape - never delivers a good frame in
        // between, so the success path alone would never clear it and the
        // second incident would be silent.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        val wedged = object : IRadarListener.Stub() {
            override fun onRadarState(state: RadarStateParcel?) = throw IllegalStateException("buffer full")
        }

        b.registerTargetListener(wedged)
        b.broadcast(state)
        assertEquals("the first spell is logged", 1, wedgedLines())

        b.registerTargetListener(wedged)
        b.broadcast(state)

        assertEquals("and so is the one after it reconnected", 2, wedgedLines())
    }

    @Test
    fun broadcastingToNobodyStampsNobody() {
        // The stamp drives a rider-facing "last used", so a frame with nobody
        // listening must not move it.
        //
        // Not a pin on the empty-listener early return beside it. That return
        // exists to skip projecting a snapshot no one will receive, which costs
        // a bound-but-ungranted consumer a projection per frame for a whole
        // ride, and nothing observable from here changes either way.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        stamped.clear()

        b.broadcast(state)

        assertTrue(stamped.isEmpty())
    }

    @Test
    fun aWedgedConsumerIsNotReportedAsReading() {
        // A `oneway` buffer that has filled throws WITHOUT killing the binder,
        // so the consumer keeps its registration and receives nothing for the
        // rest of the ride. Counting that as a read tells the rider, on the one
        // screen built to show who is using their radar, that a dead consumer
        // read seconds ago.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))
        b.registerTargetListener(
            object : IRadarListener.Stub() {
                override fun onRadarState(state: RadarStateParcel?) = throw IllegalStateException("buffer full")
            },
        )
        stamped.clear()

        b.broadcast(state)

        assertTrue("a delivery that threw is not a read", stamped.isEmpty())
    }

    @Test
    fun onePackageFailingDoesNotStopTheRestBeingRechecked() {
        // Revalidation is the rider's revoke taking effect. It runs only on a
        // write to the grant store, so a turn abandoned half way is not retried
        // - every package after the failing one keeps its stream until the
        // rider happens to change something else.
        val b = binder(read = setOf(trailBuddyUid, strangerUid))
        uid = trailBuddyUid
        val buddy = Listener()
        b.registerTargetListener(buddy)
        uid = strangerUid
        val stranger = Listener()
        b.registerTargetListener(stranger)

        gate.read = emptySet()
        gate.throwFor = trailBuddyUid
        b.revalidate()

        b.broadcast(state)
        assertTrue("the package after the failing one must still be re-checked", stranger.received.isEmpty())
    }

    @Test
    fun aNullListenerIsRefusedRatherThanCrashing() {
        // AIDL can deliver null across the boundary whatever the Kotlin type
        // says, and a consumer is not obliged to be well behaved.
        uid = trailBuddyUid
        val b = binder(read = setOf(trailBuddyUid))

        assertFalse(b.registerTargetListener(null))
        b.unregisterTargetListener(null)
    }
}
