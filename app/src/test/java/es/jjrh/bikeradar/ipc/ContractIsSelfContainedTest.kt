// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A permissive licence on a file nobody can lift out is decorative.
 *
 * One reference back into the app withdraws that in practice while the header
 * still says otherwise, and nothing else can see it: inside this repo the
 * reference resolves. Which files are permissive is read off the headers here;
 * `InterfaceIsPermissiveImplementationIsNotTest` is what pins that list.
 */
class ContractIsSelfContainedTest {

    /**
     * Recursive, so a file tucked into a subdirectory of `ipc/` is classified
     * rather than falling outside every check.
     */
    private fun sources(): List<File> = listOf(RepoFiles.mainSource("ipc"), RepoFiles.aidlDir())
        .flatMap { it.walkTopDown().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "aidl") } }
        .sortedBy { it.name }

    private fun isPermissive(f: File) = f.useLines { it.firstOrNull() } == "// SPDX-License-Identifier: Apache-2.0"

    private fun withoutComments(f: File): List<String> = withoutComments(f.readLines())

    // Private, not internal. These two ARE the pipeline the table below exists
    // to constrain, so another test reusing them would be one more check whose
    // expectation comes from the thing it is testing.
    private fun withoutComments(lines: List<String>): List<String> = lines.filterNot {
        val t = it.trimStart()
        t.startsWith("//") || t.startsWith("*") || (t.startsWith("/*") && t.endsWith("*/"))
    }

    /**
     * Comments and string literals removed, so only real references remain.
     *
     * The string strip is PER LINE, which is the whole point rather than a
     * detail. Run over the joined file, `[^"]*` crosses newlines, so one stray
     * quote in a surviving comment blanks every line to the next quote and the
     * scans below silently see nothing. Per line it cannot reach past its own.
     *
     * No permissive file carries an unbalanced line today, measured: no raw
     * strings, no char literals, no escaped quotes. Kotlin permits all three,
     * so this is a fact about these files rather than about the language, and
     * an unbalanced line would leave itself unstripped, which reports a false
     * positive rather than missing a real one.
     */
    private fun code(f: File): String = code(f.readLines())

    private fun code(lines: List<String>): String = withoutComments(lines).joinToString("\n") { it.replace(Regex("\"[^\"\\n]*\""), "\"\"") }

    @Test
    fun theStripKeepsAndDropsExactlyWhatItShould() {
        // The only check here that does not read the production files, and the
        // one the others cannot replace. Those files offer no independent
        // answer to compare against, so every other check approximates one and
        // inherits a blind spot from the pipeline it is testing. A literal
        // expectation has none, and it pins the edges the approximations argue
        // about.
        val source = listOf(
            "// SPDX-License-Identifier: Apache-2.0",
            "package es.jjrh.bikeradar.ipc",
            "",
            "import android.os.Parcel",
            "",
            "/** One-line KDoc naming RadarOverlayGate. */",
            "/*",
            " * Block comment naming es.jjrh.bikeradar.RadarState.",
            " */",
            "object X {",
            "    const val A = \"es.jjrh.bikeradar.action.THING\"",
            "    val b = es.jjrh.bikeradar.DataSource.V2",
            "    val c = 1 // trailing \" quote",
            "    val d = \"tail\"",
            "}",
        )

        assertEquals(
            listOf(
                "package es.jjrh.bikeradar.ipc",
                "",
                "import android.os.Parcel",
                "",
                // The opening line of a multi-line block comment survives, which
                // is a false positive in the safe direction: a reference on it
                // gets reported rather than missed.
                "/*",
                "object X {",
                // Blanked, so an action string naming our own package is not
                // mistaken for a reference into the app.
                "    const val A = \"\"",
                // Survives, so the reaching check can catch it.
                "    val b = es.jjrh.bikeradar.DataSource.V2",
                // An odd quote reaches no further than its own line, and the
                // line after it keeps its own literal. Joined and stripped
                // globally these two collapse into one, so this pair is what
                // makes the table catch a revert to that form by itself.
                "    val c = 1 // trailing \" quote",
                "    val d = \"\"",
                "}",
            ),
            code(source).lines(),
        )
    }

    @Test
    fun theScannerKeepsEveryImportItIsMeantToJudge() {
        // THE anti-vacuity check, and the only one guarding what actually
        // carries a violation. An import cannot be a comment and cannot hold a
        // string literal, so no legitimate strip may drop one, and the
        // comparison needs nothing derived from the stripping itself. The two
        // checks below both do, which is why neither can carry this weight.
        for (f in sources()) {
            val declared = f.readLines().map { it.trim() }.filter { it.startsWith("import ") }
            val scanned = code(f).lines().map { it.trim() }.filter { it.startsWith("import ") }
            assertEquals(
                "${f.name}: the scanner is not looking at every import, which is where a " +
                    "reference into the app usually lives",
                declared,
                scanned,
            )
        }
    }

    @Test
    fun theScannerNeverSwallowsWholeLines() {
        // Non-blank, deliberately: a strip that blanked every line preserves the
        // raw count exactly. The joined form collapses lines outright, so this
        // still fails the moment someone reverts to it.
        for (f in sources()) {
            assertEquals(
                "${f.name}: the scanner lost lines, so it is no longer reading all the code",
                withoutComments(f).count { it.isNotBlank() },
                code(f).lines().count { it.isNotBlank() },
            )
        }
    }

    @Test
    fun theScannerStillHoldsRealCodeAfterStripping() {
        // A cheap backstop, and no more than that. It cannot carry the weight,
        // because every file keeps these two lines through any edit that
        // targets the imports.
        for (f in sources()) {
            val body = code(f)
            assertTrue(
                "${f.name}: the scanner dropped even the package declaration",
                body.contains("package es.jjrh.bikeradar.ipc"),
            )
            val declares =
                if (f.extension == "aidl") {
                    Regex("\\b(interface|parcelable)\\b")
                } else {
                    Regex("\\b(object|class|const)\\b")
                }
            assertTrue(
                "${f.name}: nothing declaration-shaped survived, so the scan reads nothing",
                declares.containsMatchIn(body),
            )
        }
    }

    @Test
    fun theContractFilesReferenceNothingOutsideTheContractsOwnPackage() {
        val permissive = sources().filter { isPermissive(it) }
        assertTrue("no permissive contract file found; the split has moved", permissive.isNotEmpty())

        // Imports and fully-qualified references alike. An import-only check
        // misses `es.jjrh.bikeradar.DataSource.V2` written out in place, which
        // breaks copyability exactly as an import would.
        val reaching = Regex("""es\.jjrh\.bikeradar\.(?!ipc\b)""")
        for (f in permissive) {
            assertEquals(
                "${f.name} is licensed for another project to copy, and this reaches into the " +
                    "GPL code behind it",
                emptyList<String>(),
                reaching.findAll(code(f)).map { it.value }.toList(),
            )
        }
    }

    @Test
    fun theContractFilesNameNoneOfTheirCopyleftNeighbours() {
        // Everything in `ipc/` shares one package, so a permissive file can
        // reach a GPL sibling with no import line to show for it.
        val (permissive, copyleft) = sources().partition { isPermissive(it) }
        assertTrue("no copyleft file left in ipc/; this check has nothing to look for", copyleft.isNotEmpty())

        val neighbours = copyleft.map { it.nameWithoutExtension }
        for (f in permissive) {
            val body = code(f)
            val mentioned = neighbours.filter { Regex("\\b$it\\b").containsMatchIn(body) }
            assertEquals(
                "${f.name} names ${mentioned.joinToString()}, which stays GPL and would not travel with it",
                emptyList<String>(),
                mentioned,
            )
        }
    }
}
