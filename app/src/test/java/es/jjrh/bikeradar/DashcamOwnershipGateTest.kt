// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The rider's dashcam ownership switch turning off keeps their picked device,
 * so the pick is no longer what stops the app using it - `activeDashcamMac` is.
 * Anything that LINKS to the camera or READS it must go through that accessor,
 * and nothing else re-derives the rule.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamOwnershipGateTest {

    /**
     * Files allowed to read the raw `dashcamMac`, each with the reason.
     *
     * The first three hold it: `Prefs` defines both the raw value and the gate,
     * the picker writes it, and the dashcam settings screen shows the rider
     * which device is remembered - which is the whole point of keeping it.
     *
     * The last three RENDER and never act, and each already resolves its
     * rider-facing state through the ownership value at the point of use
     * (`deviceLinkState` answers NOT_PAIRED whenever `linked` is false, the
     * battery chip is gated on that answer, and the onboarding step reads the
     * pick inside a `when (dashcamOwnership)` arm). They are here deliberately
     * rather than migrated, because the gating they do is a fact about those
     * expressions; a new FILE reading the raw value is what this test is for.
     */
    private val mayReadTheRawMac = setOf(
        "Prefs.kt",
        "DashcamPickerSheet.kt",
        "SettingsDashcam.kt",
        "MainScreen.kt",
        "SettingsScreen.kt",
        "OnboardingPairingStep.kt",
        // The Debug scenario player swaps in a synthetic device for the run and
        // puts the rider's own back afterwards, so it has to touch the raw
        // value. Which device it demos against goes through the gate.
        "SyntheticScenarioService.kt",
    )

    /** Files that act on the camera: link to it, probe it, or publish it. */
    private val mustNotReadTheRawMac = listOf(
        "BikeRadarService.kt",
        "BatteryReader.kt",
        "OverlayPipeline.kt",
        "CameraLightLinkController.kt",
    )

    private fun mainSourceDir(): File {
        val anchor = RepoFiles.mainSource("RadarUnlock.kt")
        return requireNotNull(anchor.parentFile) { "RadarUnlock.kt must sit in a directory" }
    }

    private fun mainSources(): List<File> = mainSourceDir().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /** A read of the raw property, not of `activeDashcamMac`. The single
     *  lookbehind is what excludes the accessor: the character before its `D`
     *  is a word character, so no second `(?<!active)` is needed and one that
     *  looked load-bearing would be dead. */
    private val rawRead = Regex("""(?<!\w)[Dd]ashcamMac\b""")

    // No line-level "contains activeDashcamMac" exemption: the regex already
    // rejects that spelling, and a line-level one would silently excuse a line
    // carrying both, which is exactly how a raw read would come back.
    private fun rawReadsIn(file: File): List<String> = file.readLines()
        .filter { rawRead.containsMatchIn(it) }
        .map { it.trim() }

    @Test
    fun theDashcamIsOnlyReachedThroughTheOwnershipGate() {
        val offenders = mainSources()
            .filter { it.name !in mayReadTheRawMac }
            .flatMap { f -> rawReadsIn(f).map { "${f.name}: $it" } }
        assertEquals(
            "a camera the rider switched off must not be linked, probed or published. " +
                "Read Prefs.activeDashcamMac, or add the file to mayReadTheRawMac with its reason. Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theFilesThatActOnTheCameraReadNothingElse() {
        // The allow-list above is a set of names, so a file moving into it is a
        // one-word edit. These three are the ones where that edit would cost
        // the rider something, so they are named on their own.
        mustNotReadTheRawMac.forEach { name ->
            val reads = rawReadsIn(RepoFiles.mainSource(name))
            assertEquals("$name acts on the camera and must read only activeDashcamMac", emptyList<String>(), reads)
        }
    }

    @Test
    fun theSweepReadsTheSourcesAndCanStillSeeARawRead() {
        // Anti-vacuity, and the counting half is the weak one: a count over the
        // ALLOW-LISTED files stays healthy under a predicate narrowed to a
        // spelling those files happen to use, which is why the literals below
        // carry the real weight.
        val kt = mainSources()
        assertTrue("the sweep must reach the main sources, found ${kt.size}", kt.size > 20)
        val seen = kt.filter { it.name in mayReadTheRawMac }.sumOf { rawReadsIn(it).size }
        // The floor is per allow-listed FILE rather than a total, so a
        // predicate that stops seeing one file's spelling drops below it. A
        // total floor is met by one file carrying them all.
        val filesSeen = kt.count { it.name in mayReadTheRawMac && rawReadsIn(it).isNotEmpty() }
        assertTrue("the sweep must still recognise a raw read, saw $seen", seen >= 3)
        assertEquals(
            "every file allowed to read the raw value must still be seen doing so",
            mayReadTheRawMac.size,
            filesSeen,
        )
    }

    @Test
    fun thePredicateStillMatchesEverySpellingAReaderUses() {
        // Each of these is a line shape this repo uses or has used. A predicate
        // narrowed to any one of them (`prefs\.dashcamMac`, say) stops seeing
        // the others, and the count above would not notice.
        listOf(
            "        val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->",
            "        val savedMac = prefs.dashcamMac",
            "    val dashcamMac: String?,",
            "        dashcamMac = prefsSnap.dashcamMac,",
            "            if (prefs.dashcamMac.equals(mac, ignoreCase = true)) {",
        ).forEach {
            assertTrue("the predicate must still see a raw read written as: $it", rawRead.containsMatchIn(it))
        }
        listOf(
            "val mac = prefs.activeDashcamMac ?: return null",
            "        val dashcamSlug = prefsSnap.activeDashcamMac?.let { mac ->",
        ).forEach {
            assertTrue("and must not count the gated accessor as one: $it", !rawRead.containsMatchIn(it))
        }
    }

    @Test
    fun everyNamedFileIsInsideTheSweep() {
        // Narrowing the sweep to a subtree would pass the size check above while
        // silently dropping the files that matter. Every name both lists carry
        // has to be reachable from the walk itself.
        val swept = mainSources().map { it.name }.toSet()
        (mayReadTheRawMac + mustNotReadTheRawMac).forEach {
            assertTrue("$it must be inside the sweep, or the sweep has been narrowed", it in swept)
        }
    }

    @Test
    fun theGateHidesAPickTheRiderSwitchedOffAndGivesItBack() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.dashcamMac = "11:22:33:44:55:66"
        prefs.dashcamOwnership = DashcamOwnership.YES
        assertEquals("11:22:33:44:55:66", prefs.activeDashcamMac)

        prefs.dashcamOwnership = DashcamOwnership.NO
        assertNull("a camera the rider switched off must not be reachable", prefs.activeDashcamMac)
        assertEquals("but the pick survives, so turning it back on is an undo", "11:22:33:44:55:66", prefs.dashcamMac)

        prefs.dashcamOwnership = DashcamOwnership.YES
        assertEquals("11:22:33:44:55:66", prefs.activeDashcamMac)
    }

    @Test
    fun anUnansweredOwnershipQuestionReachesNoCamera() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.dashcamMac = "11:22:33:44:55:66"
        prefs.dashcamOwnership = DashcamOwnership.UNANSWERED
        assertNull("only an explicit yes reaches the camera", prefs.activeDashcamMac)
    }

    @Test
    fun theSnapshotAnswersTheGateTheSameWayThePrefsDo() {
        // The dashcam settings screen reads the SNAPSHOT's accessor, not the
        // Prefs one, and every other test here asserts the Prefs one. Both
        // delegate to the same private rule, so what this pins is the
        // delegation: point the snapshot at the raw mac and the screen resolves
        // a battery slug for a camera the rider switched off, suite still green.
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.dashcamMac = "11:22:33:44:55:66"
        prefs.dashcamOwnership = DashcamOwnership.YES
        assertEquals("11:22:33:44:55:66", prefs.snapshot().activeDashcamMac)

        prefs.dashcamOwnership = DashcamOwnership.NO
        assertNull("the snapshot must hide it too", prefs.snapshot().activeDashcamMac)
        assertEquals("while still carrying the raw pick", "11:22:33:44:55:66", prefs.snapshot().dashcamMac)
    }

    @Test
    fun noNameIsBothOfferedAsTheCameraAndReadAsARadar() {
        // The battery read treats a radar's name as permission to connect, and
        // the rider's switch governs only what they picked as the camera. So no
        // name should pass both. That holds because both sites ask the same
        // predicate, which no behaviour test can see: narrow the
        // picker's exclusion alone and a radar becomes pickable, stays readable
        // once switched off, and every other test still passes.
        val picker = RepoFiles.mainSource("ui/DashcamPickerSheet.kt").readText()
        assertTrue(
            "the picker must keep out exactly what the battery read calls a radar",
            "if (DeviceNameMatcher.isUnambiguousRadar(name)) return@mapNotNull null" in picker,
        )
        val mayRead = RepoFiles.mainSource("BatteryReader.kt").readText()
            .substringAfter("private fun mayRead(")
            .substringBefore("@SuppressLint")
        assertEquals(
            "and the battery read must use that predicate and no other name test",
            listOf("isUnambiguousRadar"),
            Regex("""DeviceNameMatcher\.(\w+)""").findAll(mayRead).map { it.groupValues[1] }.toList(),
        )
    }

    @Test
    fun theScenarioPlayerTouchesNothingWhenTheRiderHasAPick() {
        // One condition, and it reads the STORED pick. Deciding it on what a
        // reader can see instead borrows the ownership switch from a rider who
        // turned it off, which is the state onboarding's skip and the home
        // prompt both produce: their switch goes back on for the run, and the
        // app links to and probes the camera they turned off for its length.
        // The 60 s loop is not drivable, so the structure is what is reachable.
        // Comment lines are dropped: the prose here explains the gated accessor
        // and would otherwise satisfy a search for it.
        val src = RepoFiles.mainSource("SyntheticScenarioService.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "the borrow must be decided on the stored pick",
            Regex("""val borrowing = savedMac == null""").containsMatchIn(src),
        )
        assertEquals(
            "every dashcam write must sit under that one guard, borrow and restore",
            2,
            Regex("""if \(borrowing\) \{""").findAll(src).count(),
        )
        assertTrue(
            "and no code there may consult the gated accessor to decide it",
            "activeDashcamMac" !in src,
        )
    }

    @Test
    fun theScenarioPlayersSwapSetsWhatItRestoresAndRestoresWhatItSets() {
        // The Debug scenario player borrows the dashcam slot for its run. Two
        // ways that goes wrong once the pick survives the switch, and a source
        // read is the only thing reaching them: the 60 s loop is not drivable.
        // Set without restore strands the rider's camera on the synthetic one.
        // Restore without set leaves the swap unreachable, because every reader
        // goes through the ownership gate and the swap never moved it.
        val src = RepoFiles.mainSource("SyntheticScenarioService.kt").readText()
        // Any right-hand side, not just an identifier: one of the borrowed
        // values is a string literal, and a narrower pattern reads as an
        // asymmetry in the code rather than in the scan.
        val assignments = Regex("""prefs\.(dashcam\w+)\s*=\s*(\S+)""").findAll(src)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("the scan found no dashcam assignments at all", assignments.isNotEmpty())
        val borrowed = assignments.filterNot { it.second.startsWith("saved") }.map { it.first }.toSet()
        val restored = assignments.filter { it.second.startsWith("saved") }.map { it.first }.toSet()
        assertTrue("the swap must set the ownership, or no reader can see the synthetic camera", "dashcamOwnership" in borrowed)
        assertTrue("the swap must set the mac", "dashcamMac" in borrowed)
        assertEquals("every dashcam pref the scenario player borrows must be put back", borrowed, restored)

        // Order, not just symmetry: a capture that moved below the swap would
        // save the synthetic value and make the restore a silent no-op, which
        // reads the same as a correct one from the sets alone.
        val firstBorrow = Regex("""prefs\.dashcam\w+\s*=\s*(?!saved)""").find(src)?.range?.first
        val lastCapture = Regex("""val saved\w+ = prefs\.""").findAll(src).map { it.range.first }.maxOrNull()
        assertTrue("the scan must see both the captures and the swap", firstBorrow != null && lastCapture != null)
        assertTrue(
            "every saved* capture must be read before the swap overwrites it",
            lastCapture!! < firstBorrow!!,
        )
    }
}
