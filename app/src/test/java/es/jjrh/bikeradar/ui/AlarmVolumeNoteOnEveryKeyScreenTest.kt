// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A screen that hands the volume buttons to the alerts can move the wake-up
 * alarm, so it says why: every main-source file that calls
 * [VolumeKeysFollowCues] also shows [AlarmVolumeNote].
 */
class AlarmVolumeNoteOnEveryKeyScreenTest {

    private val keysCall = Regex("""(?<!fun )\bVolumeKeysFollowCues\(""")
    private val noteCall = Regex("""(?<!fun )\bAlarmVolumeNote\(""")

    private fun mainSources(): List<File> = RepoFiles.mainSource("ui").parentFile!!.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun filesCalling(call: Regex): Set<String> = mainSources()
        .filter { call.containsMatchIn(it.readText()) }
        .map { it.name }
        .toSet()

    @Test
    fun theButtonsAreHandedOverOnTheseThreeScreens() {
        assertEquals(
            setOf("SoundDemoStep.kt", "AlertVolumeStep.kt", "SettingsAlertSounds.kt"),
            filesCalling(keysCall),
        )
    }

    @Test
    fun everyScreenThatHandsThemOverShowsTheNote() {
        assertEquals(emptySet<String>(), filesCalling(keysCall) - filesCalling(noteCall))
    }
}
