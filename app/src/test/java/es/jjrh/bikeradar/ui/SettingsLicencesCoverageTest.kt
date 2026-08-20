// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two absolute claims the licences screen makes: that every
 * third-party library the app depends on directly is listed, and that all
 * of them are compatible with GPL-3.0.
 *
 * What it pins is the LIST behind those sentences, checked against the
 * build file - not the sentences themselves. Reword the string and this
 * stays green. It earns its place because a hand-curated list under a bot
 * that raises dependency bumps is exactly where such a claim rots.
 *
 * Scope is what `app/build.gradle.kts` declares on a configuration that
 * reaches a release APK - see the regex below for the exact set;
 * `debugImplementation`, `testImplementation` and the BOM platform
 * declaration are not attribution obligations and are deliberately
 * excluded. Transitive libraries the APK
 * also packages are OUT of scope here, which is why the screen claims only
 * direct use - see SettingsLicenses.kt's KDoc.
 */
class SettingsLicencesCoverageTest {

    /**
     * Maps each shipped coordinate to the entry expected to attribute it.
     * Written out by hand from the licences screen rather than derived from
     * it, so a wrong entry cannot agree with itself.
     */
    private val expectedAttribution = mapOf(
        "androidx.core:core-ktx" to "AndroidX Core / AppCompat / Lifecycle",
        "androidx.appcompat:appcompat" to "AndroidX Core / AppCompat / Lifecycle",
        "androidx.lifecycle:lifecycle-runtime-ktx" to "AndroidX Core / AppCompat / Lifecycle",
        "androidx.lifecycle:lifecycle-runtime-compose" to "AndroidX Core / AppCompat / Lifecycle",
        "com.google.android.material:material" to "Material Components for Android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android" to "Kotlinx Coroutines",
        "androidx.activity:activity-compose" to "Activity Compose",
        "androidx.navigation:navigation-compose" to "Navigation Compose",
        "androidx.compose.ui:ui" to "Jetpack Compose UI / Material 3 / Material Icons Extended",
        "androidx.compose.ui:ui-tooling-preview" to "Jetpack Compose UI / Material 3 / Material Icons Extended",
        "androidx.compose.material3:material3" to "Jetpack Compose UI / Material 3 / Material Icons Extended",
        "androidx.compose.material:material-icons-extended" to "Jetpack Compose UI / Material 3 / Material Icons Extended",
    )

    /**
     * A vetted allow-list, NOT the set of GPL-3.0-compatible licences. It
     * fails closed: a genuinely compatible licence such as MIT reds this
     * test until someone checks it against the FSF list and adds it. The
     * FSF holds Apache 2.0 compatible with GPLv3, and holds EPL 1.0 and
     * CPL 1.0 incompatible - which is why build and test tooling has no
     * place on a screen that claims compatibility.
     */
    private val vettedGplV3CompatibleLicences = setOf("Apache 2.0")

    private fun shippedCoordinates(): List<String> {
        // Module path first: a root `build.gradle.kts` also exists, so probing
        // the bare name first picks it whenever the working directory is the
        // repo root, and it declares no dependencies at all.
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .firstOrNull { it.exists() }
            ?: error("could not locate the module build file from ${File(".").absolutePath}")
        // `coreLibraryDesugaring` is in the list because its payload is
        // GPL-2.0-with-Classpath-Exception, not Apache 2.0: enabling it would
        // ship a licence this screen does not list, under a sentence saying
        // every one of them is compatible. `api` ships the same as
        // `implementation` and differs only in what it exposes to consumers.
        return Regex(
            """^\s*(?:implementation|api|coreLibraryDesugaring)\("([^"]+)"\)""",
            RegexOption.MULTILINE,
        )
            .findAll(gradle.readText())
            .map { withoutVersion(it.groupValues[1]) }
            .toList()
    }

    /** Compose artifacts take their version from the BOM and carry none here. */
    private fun withoutVersion(coordinate: String): String {
        val parts = coordinate.split(':')
        return if (parts.size >= 3) "${parts[0]}:${parts[1]}" else coordinate
    }

    private fun screenEntries() = LANGUAGE_RUNTIME_LICENCES + ANDROID_PLATFORM_LICENCES + UI_LICENCES

    @Test
    fun everyShippedDependencyIsAttributedOnTheLicencesScreen() {
        val listed = screenEntries().map { it.name }.toSet()
        val unattributed = shippedCoordinates().filter { coordinate ->
            expectedAttribution[coordinate]?.let { it !in listed } ?: true
        }
        assertEquals(
            "every implementation dependency ships in the APK and must be attributed on the " +
                "licences screen, whose intro promises the list covers every direct dependency",
            emptyList<String>(),
            unattributed,
        )
    }

    @Test
    fun theBuildFileDeclaresNoShippedDependencyTheTestDoesNotKnowAbout() {
        assertEquals(
            "a shipped dependency was added or renamed; map it to its licence entry",
            emptySet<String>(),
            shippedCoordinates().toSet() - expectedAttribution.keys,
        )
    }

    @Test
    fun theRegexStillSeesEveryDeclaredDependency() {
        // Every other test here passes on an empty list, so anything that
        // stops the regex matching - a wrapped declaration, a version
        // catalog, or reading the root build file from the wrong working
        // directory - would unpin the screen's claim while staying green.
        // Measured against a wrapped `implementation(` declaration, which
        // survives all four of the other tests.
        assertEquals(
            "the build file's directly-declared dependency count changed; " +
                "update this count and the attribution map together",
            12,
            shippedCoordinates().size,
        )
    }

    @Test
    fun noListedLicenceIsIncompatibleWithGpl3() {
        val incompatible = screenEntries().filterNot { it.licence in vettedGplV3CompatibleLicences }
        assertEquals(
            "the screen states every listed licence is compatible with GPL-3.0; this licence " +
                "is not on the vetted allow-list, so check it against the FSF list and add it there",
            emptyList<LicenseEntry>(),
            incompatible,
        )
    }

    @Test
    fun everyListedEntryNamesAnAuthorAndALicence() {
        assertTrue(
            "an attribution with a blank author or licence is not an attribution",
            screenEntries().all { it.name.isNotBlank() && it.author.isNotBlank() && it.licence.isNotBlank() },
        )
    }
}
