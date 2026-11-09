// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothGatt
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [BatteryReader]'s GATT read itself, with the client from Robolectric and its
 * callbacks driven here: however the read ends, the client is closed, including
 * when it ends before `connectGatt` has returned it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatteryReaderGattTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var gatt: BluetoothGatt
    private lateinit var callback: BluetoothGattCallback

    @Before
    fun setUp() {
        BatteryStateBus.clearForTest()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
    }

    @After
    fun tearDown() = BatteryStateBus.clearForTest()

    /** Starts a read of the radar; [beforeReturn] runs inside `connectGatt`. */
    private fun TestScope.startRead(beforeReturn: () -> Unit = {}) {
        val reader = BatteryReader(
            context = app,
            scope = CoroutineScope(EmptyCoroutineContext),
            prefs = Prefs(app),
            knownDevices = KnownDevices(app.getSharedPreferences("gatt_kd", Context.MODE_PRIVATE)),
            publishBattery = { _, _ -> true },
            macToSlug = mutableMapOf(),
            slug = { it.lowercase() },
            dashcamProbeFailures = mutableMapOf(),
            dashcamAnsweredAtMs = mutableMapOf(),
            onDashcamAnswered = {},
            connectGatt = { device, cb ->
                gatt = ShadowBluetoothGatt.newInstance(device)
                callback = cb
                beforeReturn()
                gatt
            },
        )
        backgroundScope.launch { reader.doReadBattery("RearVue8", RADAR) }
        runCurrent()
    }

    private fun disconnected() = callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)

    private fun shown() = BatteryStateBus.entries.value["rearvue8"]?.pct

    @Test
    fun aReadThatAnswersClosesTheClient() = runTest {
        startRead()
        val ch = BluetoothGattCharacteristic(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        callback.onCharacteristicRead(gatt, ch, byteArrayOf(77), BluetoothGatt.GATT_SUCCESS)
        disconnected()
        runCurrent()
        assertEquals(77, shown())
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aLinkThatDropsClosesTheClient() = runTest {
        startRead()
        disconnected()
        runCurrent()
        assertNull(shown())
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aReadThatTimesOutClosesTheClient() = runTest {
        startRead()
        advanceTimeBy(BatteryReader.READ_TIMEOUT_MS + 1L)
        runCurrent()
        assertNull(shown())
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aLinkThatDropsBeforeConnectGattReturnsStillClosesTheClient() = runTest {
        startRead(beforeReturn = { disconnected() })
        runCurrent()
        assertNull(shown())
        assertTrue(shadowOf(gatt).isClosed)
    }

    private companion object {
        const val RADAR = "AA:BB:CC:DD:EE:01"
    }
}
