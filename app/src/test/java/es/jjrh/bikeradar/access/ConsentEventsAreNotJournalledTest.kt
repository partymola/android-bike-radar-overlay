// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Granting and revoking reach none of the sinks a rider can share.
 *
 * The check is a denylist over the four this repo has: the capture log, the
 * link journal, crash reports and logcat. It does not reach a raw `FileWriter`
 * or a new sink invented later, so the claim it backs is "none of these", not
 * "nothing anywhere".
 *
 * The names in the grant store are the rider's installed third-party apps.
 * A capture log is a file riders attach to hardware reports, and the link
 * journal and crash reports travel the same way, so a consent event reaching
 * any of them would put a list of what they have installed into an artefact
 * meant to be shared. `RadarGrantStore`'s KDoc states that as deliberate;
 * this is what stops a later edit quietly undoing it.
 *
 * Source-reading, like `SettingsPrivacyLogcatGuardTest`, and for the same
 * reason: `BuildConfig.DEBUG` is true under test, so no runtime test can
 * reach the release behaviour of a logging call.
 */
class ConsentEventsAreNotJournalledTest {

    // `Log.` rather than the five level names, so `Log.wtf` and anything else
    // on that surface is covered by one token. The journal and crash sinks are
    // named because `RadarGrantStore`'s KDoc claims them and nothing else here
    // would catch a call to either.
    private val forbidden = listOf(
        "clog(",
        "clogPacket(",
        "Log.",
        "println(",
        "LinkEventJournal",
        "CrashLogger",
    )

    private fun accessSources(): List<File> {
        val store = RepoFiles.mainSource("access/RadarGrantStore.kt")
        val dir = requireNotNull(store.parentFile) { "no parent for ${store.absolutePath}" }
        return dir.listFiles { f: File -> f.name.endsWith(".kt") }?.sortedBy { it.name }.orEmpty()
    }

    @Test
    fun theAccessPackageWritesToNoLog() {
        val sources = accessSources()
        assertTrue("no access/ sources found, so this proved nothing", sources.size >= 5)

        val offenders = sources.flatMap { file ->
            val text = file.readText()
            forbidden.filter { text.contains(it) }.map { "${file.name} contains $it" }
        }

        assertEquals(
            "a consent event reaching a log would put the rider's installed apps into a shareable file",
            emptyList<String>(),
            offenders,
        )
    }
}
