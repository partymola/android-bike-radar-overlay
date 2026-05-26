// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the "read-only GATT client" safety contract for [EBikeStatusReader].
 *
 * The CHANGELOG, README, in-app Privacy screen, and Settings → eBike copy all
 * promise the eBike's command channel is never written. The only allowed GATT
 * write is the standard CCCD subscribe (`queue.writeCccd`), which enables the
 * status-notify stream and never touches the bike's command surface. A future
 * refactor that adds `gatt.writeCharacteristic(...)` or `queue.writeCharacteristic(...)`
 * to this file would silently break that promise, so this test source-greps the
 * reader and fails if any forbidden write call appears.
 *
 * Pattern mirrors [HaClientDataDisclosureTest]: read the .kt as a text file
 * under `app/src/test`, look for the forbidden call sites, and assert exactly
 * the expected allowed sites are present (no more, no fewer).
 */
class EBikeStatusReaderReadOnlyTest {

    @Test
    fun readerDoesNotWriteTheBikesCommandChannel() {
        val source = readMainSource("EBikeStatusReader.kt")

        // No characteristic writes. The reader subscribes via CCCD and only
        // reads notifications afterwards.
        val charWrites = forbiddenMatches(source, "writeCharacteristic")
        assertEquals(
            "EBikeStatusReader must never call writeCharacteristic on the bike. " +
                "Adding a write breaks the read-only safety contract that ships " +
                "in the CHANGELOG, README, Privacy screen, and Settings copy. " +
                "Found: $charWrites",
            0,
            charWrites.size,
        )

        // No raw descriptor writes either; the BleOpQueue's writeCccd wraps the
        // single standard 0x2902 = 0100 enable, which is what we're allowing.
        val rawDescWrites = forbiddenMatches(source, "writeDescriptor")
        assertEquals(
            "EBikeStatusReader must not call writeDescriptor directly; the only " +
                "allowed descriptor write is queue.writeCccd(...) for the standard " +
                "CCCD subscribe. Found: $rawDescWrites",
            0,
            rawDescWrites.size,
        )

        // Exactly one CCCD subscribe is expected (the connect path's enable).
        val cccdSubscribes = source.lineSequence()
            .filter { !it.trimStart().startsWith("//") }
            .filter { !it.trimStart().startsWith("*") }
            .count { it.contains("queue.writeCccd(") }
        assertEquals(
            "Expected exactly one queue.writeCccd(...) call (the connect-path " +
                "subscribe). A second site would mean we are doing something other " +
                "than enabling the notify stream.",
            1,
            cccdSubscribes,
        )
    }

    private fun forbiddenMatches(source: String, needle: String): List<String> = source.lineSequence()
        .withIndex()
        .filter { (_, line) ->
            val trimmed = line.trimStart()
            // Comments and KDoc may legitimately mention the names while
            // explaining what is allowed and what is not. Code calls are
            // what we are guarding against.
            !trimmed.startsWith("//") &&
                !trimmed.startsWith("*") &&
                line.contains("$needle(")
        }
        .map { (idx, line) -> "line ${idx + 1}: ${line.trim()}" }
        .toList()

    private fun readMainSource(fileName: String): String {
        val file = listOf(
            "src/main/java/es/jjrh/bikeradar/$fileName",
            "app/src/main/java/es/jjrh/bikeradar/$fileName",
        ).map { File(it) }.firstOrNull { it.exists() }
            ?: error("could not locate $fileName from ${File(".").absolutePath}")
        return file.readText()
    }
}
