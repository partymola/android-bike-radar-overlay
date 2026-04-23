// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * AMV 04 unlock handshake for the 6a4e3204 (V2 measurement) stream.
 * Drives the command/reply channel on 6a4e2800 (TX=2821 WRITE-NO-RESP,
 * RX=2811 NOTIFY).
 *
 * Session-dynamic values:
 *   pfxEnum — byte 13 of AMV cmd-04 reply → prefix for enumerate commands 00..04
 *   pfxCmd  — byte 13 of AMV cmd-16 reply → prefix for capability exchange
 *   base    — byte 0 of device-pushed device-ID frame → substituted as e0/e1 in
 *             capability frames (seen as 0xe0 or 0xd0 across sessions)
 *
 * Returns true on success (3204 stream should now flow).
 * Returns false on any ABORT — caller must close + reopen GATT (APK-reinstall
 * self-heal: Bluedroid keeps a half-open GATT reference on SIGKILL; reopening
 * clears it). Log fingerprint: "# script: ABORT" then "# gatt reopened".
 */
object RadarUnlock {

    // Phone-side device-ID payload. Three length-prefixed strings:
    // client-name, vendor, model. Length and structure must match what
    // the head unit expects; the client-name itself is not validated by
    // the device, so we send the project's own name.
    // TODO: read vendor/model from android.os.Build at runtime instead
    // of hardcoding.
    private const val DEVICE_ID_SUFFIX =
        "1162696b657261646172206f7665726c617906476f6f676c650f506978656c2031302050726f20584c" +
        "01148400"

    @SuppressLint("MissingPermission")
    suspend fun runHandshake(
        gatt: BluetoothGatt,
        queue: BleOpQueue,
        notifies: Channel<Pair<UUID, ByteArray>>,
        clog: (String) -> Unit,
    ): Boolean {
        queue.requestMtu(gatt, 247)
        delay(100)

        // Pre-handshake subscriptions.
        // 2811 (HANDSHAKE_RX) NOTIFY — handshake reply channel, must come first.
        // 2f11 (SETTINGS_ACK) INDICATE — WRITE_REQ to 2f11 returns 0xFD without it.
        if (!subscribeCccd(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX, clog)) return false
        if (!subscribeCccd(gatt, queue, Uuids.SVC_CONTROL, Uuids.SETTINGS_ACK, clog)) return false

        // Battery read + CCCD subscribe. Without this the radar stays in
        // legacy V1 mode after a successful AMV handshake.
        readChar(gatt, queue, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, clog)
        delay(250)
        subscribeCccd(gatt, queue, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, clog)
        delay(400)

        // Open AMV session. Reply opcode = 0x06.
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "00050000000000414d560000")
        val rOpen = awaitNotify(notifies, Uuids.HANDSHAKE_RX, 800) {
            it.size >= 2 && it[1].toInt() == 0x06
        }
        if (rOpen == null) { clog("ABORT: AMV open reply never arrived"); return false }
        delay(60)

        // AMV cmd 04 — reply carries enumerate prefix at byte 13.
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "00000000000000414d56040000")
        val r04 = awaitNotify(notifies, Uuids.HANDSHAKE_RX, 1000) {
            it.size >= 14 && it[1].toInt() == 0x01 && it[10].toInt() == 0x04
        }
        if (r04 == null) { clog("ABORT: AMV 04 reply never arrived"); return false }
        val pfxEnum = "%02x".format(r04[13].toInt() and 0xff)
        clog("pfxEnum=$pfxEnum reply=${r04.toHex()}")
        delay(50)

        // Enumerate commands 00..04.
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxEnum}00"); delay(80)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxEnum}01"); delay(180)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxEnum}02"); delay(90)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxEnum}03"); delay(100)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxEnum}04"); delay(170)

        // AMV cmd 01 + 16 back-to-back. Await both replies then the device-ID push.
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "00000000000000414d56010002"); delay(5)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "00000000000000414d56160000")

        val r16 = awaitNotify(notifies, Uuids.HANDSHAKE_RX, 1200) {
            it.size >= 14 && it[1].toInt() == 0x01 && it[10].toInt() == 0x16
        }
        if (r16 == null) { clog("ABORT: AMV 16 reply never arrived"); return false }
        val pfxCmd = "%02x".format(r16[13].toInt() and 0xff)
        clog("pfxCmd=$pfxCmd reply=${r16.toHex()}")

        // Device-ID push frame: byte 0 >= 0x80, size > 20.
        val devId = awaitNotify(notifies, Uuids.HANDSHAKE_RX, 1500) {
            it.size > 20 && (it[0].toInt() and 0xff) >= 0x80
        }
        if (devId == null) { clog("ABORT: device-ID frame never arrived"); return false }
        val base = devId[0].toInt() and 0xff
        val e0 = "%02x".format(base)
        val e1 = "%02x".format((base + 1) and 0xff)
        clog("base=$e0 baseE1=$e1 devId=${devId.toHex()}")
        delay(20)

        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, "${pfxCmd}0119000000"); delay(15)

        // Capability exchange — minimal common-denominator across 4 captured sessions
        // (2026-04-17 multi-session HCI diff). Single-byte probes inconsistent across
        // sessions, removed to match the common denominator.
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX,
            "${e0}4000023f058813a013029608ffffffffffff9b2fffff$DEVICE_ID_SUFFIX"); delay(180)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX,
            "${e0}81000209010481ba13039de900"); delay(130)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX,
            "${e0}820002130432800c010101010101010380100404f66a00"); delay(110)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX,
            "${e0}c3000218032b8101010101010204010102040101046a024203d21000"); delay(85)
        writeNoResp(gatt, queue, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX,
            "${e1}44000211010482b41301010101010101010398dd00"); delay(60)

        clog("handshake complete — observing for 3204")

        // Post-handshake: DIS reads flanking the 3204 CCCD subscribe.
        // Order as observed from a reference session; DIS-CCCD-DIS
        // ordering is what the radar expects to unlock V2 reliably.
        delay(80)
        readChar(gatt, queue, Uuids.SVC_DIS, Uuids.DIS_MODEL_NUMBER, clog)
        delay(140)
        if (!subscribeCccd(gatt, queue, Uuids.SVC_RADAR, Uuids.RADAR_V2, clog)) return false
        delay(140)
        readChar(gatt, queue, Uuids.SVC_DIS, Uuids.DIS_FIRMWARE_REV, clog)
        delay(90)
        readChar(gatt, queue, Uuids.SVC_DIS, Uuids.DIS_SERIAL_NUMBER, clog)
        clog("DIS-CCCD-DIS sequence complete — observing 3204")

        return true
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private suspend fun subscribeCccd(
        gatt: BluetoothGatt,
        queue: BleOpQueue,
        svcUuid: UUID,
        charUuid: UUID,
        clog: (String) -> Unit,
    ): Boolean {
        val ch = gatt.getService(svcUuid)?.getCharacteristic(charUuid) ?: run {
            clog("subscribeCccd: char not found ${charUuid.toString().substring(4, 8)}")
            return false
        }
        val ok = queue.writeCccd(gatt, ch)
        clog("# subscribed ${charUuid.toString().substring(4, 8)} ok=$ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    private suspend fun readChar(
        gatt: BluetoothGatt,
        queue: BleOpQueue,
        svcUuid: UUID,
        charUuid: UUID,
        clog: (String) -> Unit,
    ) {
        val ch = gatt.getService(svcUuid)?.getCharacteristic(charUuid) ?: run {
            clog("readChar: char not found ${charUuid.toString().substring(4, 8)}")
            return
        }
        val result = queue.read(gatt, ch)
        clog("# read ${charUuid.toString().substring(4, 8)} -> ${result?.toHex() ?: "null"}")
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeNoResp(
        gatt: BluetoothGatt,
        queue: BleOpQueue,
        svcUuid: UUID,
        charUuid: UUID,
        hex: String,
    ) {
        val ch = gatt.getService(svcUuid)?.getCharacteristic(charUuid) ?: return
        queue.write(gatt, ch, hex.hexToBytes(), noResponse = true)
    }

    private suspend fun awaitNotify(
        notifies: Channel<Pair<UUID, ByteArray>>,
        charUuid: UUID,
        timeoutMs: Long,
        matches: (ByteArray) -> Boolean = { true },
    ): ByteArray? = withTimeoutOrNull(timeoutMs) {
        for ((uuid, bytes) in notifies) {
            if (uuid == charUuid && matches(bytes)) return@withTimeoutOrNull bytes
        }
        null
    }
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length: $this" }
    return ByteArray(length / 2) {
        ((Character.digit(this[it * 2], 16) shl 4) or
            Character.digit(this[it * 2 + 1], 16)).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
