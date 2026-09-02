// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract is copyable by anyone. What implements it is not.
 *
 * Pinned both ways, because each mistake is silent: normalising a permissive
 * header withdraws a permission already published, and one creeping onto an
 * implementation file gives code away. The permissive set is hand-written, so a
 * new `.aidl` fails here until someone puts it on a side. A new `.kt` does not,
 * because it lands in the copyleft bucket and passes on an ordinary GPL header.
 */
class InterfaceIsPermissiveImplementationIsNotTest {

    @Test
    fun everyInterfaceDefinitionIsPermissivelyLicensed() {
        val aidl = RepoFiles.aidlDir().listFiles { f -> f.name.endsWith(".aidl") }.orEmpty()
        assertEquals("the interface definitions moved or changed", AIDL_SOURCES, aidl.map { it.name }.sorted())
        for (f in aidl) assertPermissive(f.name, f.readLines().first())
    }

    @Test
    fun theWireLayoutAndItsConstantsAreCopyableToo() {
        // The `.aidl` alone are not a usable contract: `RadarStateParcel.aidl`
        // is a forward declaration, and the constants are not in them.
        for (name in PERMISSIVE_SOURCES) {
            assertPermissive(name, RepoFiles.mainSource("ipc/$name").readLines().first())
        }
    }

    @Test
    fun everythingThatImplementsTheContractStaysCopyleft() {
        // Recursive, matching the self-containment scan. Otherwise a file in a
        // future `ipc/internal/` would be scanned there and header-checked
        // nowhere, which is the gap that lets an unlabelled file exist at all.
        val impl = RepoFiles.mainSource("ipc").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in PERMISSIVE_SOURCES }
            .toList()
        assertTrue("the ipc implementation moved; this check found none", impl.isNotEmpty())

        for (f in impl) {
            assertEquals(
                "${f.name} implements the contract, it is not the contract. A permissive " +
                    "header here gives away code that was never meant to be given away.",
                "// SPDX-License-Identifier: $COPYLEFT",
                f.readLines().first(),
            )
        }
    }

    @Test
    fun theReadmeNamesEveryCopyableFileAndPointsAtTheGrant() {
        // A licence split nobody can find is one nobody can rely on.
        val readme = RepoFiles.repoFile("README.md").readText()

        assertTrue("the README no longer names the contract's licence", readme.contains(PERMISSIVE))
        for (name in PERMISSIVE_SOURCES + AIDL_SOURCES) {
            assertTrue("the README does not name $name as copyable", readme.contains(name))
        }
        assertTrue(
            "the README should point at the additional permission, which covers writing a " +
                "consumer rather than copying our files",
            readme.contains(GRANT_FILE),
        )
    }

    @Test
    fun theGrantNamesTheSameFilesItIsGrantingOver() {
        // The operative legal document hard-codes the paths, so a rename has to
        // reach it too: the headers, the README and the list above all have
        // checks.
        // Whitespace collapsed, because the grant is hard-wrapped prose and the
        // qualifier below straddles a line break.
        val grant = RepoFiles.repoFile(GRANT_FILE).readText().replace(Regex("\\s+"), " ")

        for (name in PERMISSIVE_SOURCES + AIDL_SOURCES) {
            assertTrue("$GRANT_FILE grants over no file called $name", grant.contains(name))
        }
        assertTrue(
            "the grant has lost the qualifier that keeps it off an app which also embeds our " +
                "GPL code, which is the one phrase in it that must survive a rewrite",
            grant.contains("incorporates no part of this program other than the interface files"),
        )
    }

    @Test
    fun theApacheTextIsShippedAndIsTheRealThing() {
        // Six files are published under it, and a fork or a from-source build
        // redistributing them cannot comply without the text. `isFile` alone
        // would pass on a truncated stub.
        val text = RepoFiles.repoFile("LICENSES/Apache-2.0.txt").readText()

        assertTrue("the repo ships no copy of $PERMISSIVE", text.contains("Apache License"))
        assertTrue("the licence text is truncated", text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(
            "the grant of patent rights is missing, so this is not the whole licence",
            text.contains("Grant of Patent License"),
        )
        // A floor as well as the phrases, or a stub carrying just those three
        // passes. The canonical text is 202 lines and is immutable, so this
        // cannot drift.
        assertEquals("the licence text is not the canonical 202 lines", 202, text.trimEnd().lines().size)
    }

    private fun assertPermissive(name: String, firstLine: String) = assertEquals(
        "$name is the contract another app copies. A GPL header here means only a GPL app " +
            "can copy it, which is the one thing this licence exists to allow.",
        "// SPDX-License-Identifier: $PERMISSIVE",
        firstLine,
    )

    private companion object {
        const val PERMISSIVE = "Apache-2.0"
        const val COPYLEFT = "GPL-3.0-or-later"
        const val GRANT_FILE = "additional-permission.txt"

        val PERMISSIVE_SOURCES = listOf(
            "RadarContract.kt",
            "RadarStateParcel.kt",
            "RadarVehicleParcel.kt",
        )

        val AIDL_SOURCES = listOf(
            "IRadarListener.aidl",
            "IRadarService.aidl",
            "RadarStateParcel.aidl",
        )
    }
}
