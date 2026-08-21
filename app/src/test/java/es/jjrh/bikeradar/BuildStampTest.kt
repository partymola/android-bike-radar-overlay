// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildStampTest {
    /** Rendered width of a microsecond-precision `java.time.Instant`. */
    private val instantWidth = 27

    @Test
    fun `debug build off a clean tree names its commit`() {
        assertEquals(
            "# app version=1.1.0 code=22 build=debug commit=6219cb5",
            BuildStamp.headerLine(
                versionName = "1.1.0",
                versionCode = 22,
                buildType = "debug",
                commit = "6219cb5",
                dirty = false,
            ),
        )
    }

    @Test
    fun `a dirty tree is marked, because the commit alone would misattribute it`() {
        assertEquals(
            "# app version=1.1.0 code=22 build=debug commit=6219cb5-dirty",
            BuildStamp.headerLine(
                versionName = "1.1.0",
                versionCode = 22,
                buildType = "debug",
                commit = "6219cb5",
                dirty = true,
            ),
        )
    }

    @Test
    fun `a release build carries no commit field at all`() {
        // Release APKs are pinned by their tag, and an embedded SHA would be a
        // non-reproducible byte in the DEX (see the dependenciesInfo strip).
        assertEquals(
            "# app version=1.2.0 code=23 build=release",
            BuildStamp.headerLine(
                versionName = "1.2.0",
                versionCode = 23,
                buildType = "release",
                commit = null,
                dirty = false,
            ),
        )
    }

    @Test
    fun `a release build ignores a stray dirty flag rather than emitting a bare marker`() {
        assertEquals(
            "# app version=1.2.0 code=23 build=release",
            BuildStamp.headerLine(
                versionName = "1.2.0",
                versionCode = 23,
                buildType = "release",
                commit = null,
                dirty = true,
            ),
        )
    }

    @Test
    fun `a blank commit reads unknown rather than collapsing into a release-shaped line`() {
        // The failure that makes this load-bearing: git cannot answer in a
        // non-release build. Stamping the marker records that resolution was
        // attempted and failed; an absent field would leave a reader to infer
        // that from nothing.
        assertEquals(
            "# app version=1.1.0 code=22 build=debug commit=unknown",
            BuildStamp.headerLine(
                versionName = "1.1.0",
                versionCode = 22,
                buildType = "debug",
                commit = "",
                dirty = false,
            ),
        )
    }

    @Test
    fun `an unresolved commit is not qualified as dirty`() {
        // There is no commit to qualify, so "unknown-dirty" would claim more
        // than is known.
        assertEquals(
            "# app version=1.1.0 code=22 build=debug commit=unknown",
            BuildStamp.headerLine(
                versionName = "1.1.0",
                versionCode = 22,
                buildType = "debug",
                commit = "",
                dirty = true,
            ),
        )
    }

    @Test
    fun `an explicit unknown behaves like a blank one`() {
        assertEquals(
            "# app version=1.1.0 code=22 build=debug commit=unknown",
            BuildStamp.headerLine(
                versionName = "1.1.0",
                versionCode = 22,
                buildType = "debug",
                commit = "unknown",
                dirty = true,
            ),
        )
    }

    @Test
    fun `the onbtest variant keeps its version suffix`() {
        // versionNameSuffix and buildType both name the variant; the repetition
        // is intended, not a formatting bug.
        assertEquals(
            "# app version=1.1.0-onbtest code=22 build=onbtest commit=6219cb5",
            BuildStamp.headerLine(
                versionName = "1.1.0-onbtest",
                versionCode = 22,
                buildType = "onbtest",
                commit = "6219cb5",
                dirty = false,
            ),
        )
    }

    @Test
    fun `the stamp is a comment line so every replay parser skips it`() {
        // CorpusReplayGate, CueLedgerReplayTest, PipelineReplayTest,
        // RadarV2DecoderReplayTest and ReplayService all drop lines starting
        // with '#'. A stamp that lost the prefix would be parsed as a packet.
        val line = BuildStamp.headerLine("1.1.0", 22, "debug", "6219cb5", false)
        assertTrue(line.startsWith("#"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `the BuildConfig binding resolves a real commit, not a degraded one`() {
        // Structural, not value-based: asserting BuildConfig.GIT_COMMIT against
        // itself would be the tautology the repo bans. Pinning the SHAPE still
        // catches the failure that matters - gitValue silently degrading on
        // every build, which would stamp commit=unknown everywhere and kill the
        // feature's whole purpose while every other test stayed green.
        val line = BuildConfigStamp.line()
        assertTrue(line.startsWith("# app version="))
        assertTrue(line.contains(" code="))
        assertTrue(line.contains(" build=debug"))
        // Branch on whether a repo was present at BUILD time rather than
        // skipping: a source-zip checkout has no .git and must degrade to the
        // marker, while a git checkout that still produced "unknown" is the
        // silent degradation this test exists to catch.
        val repoPresent = RepoFiles.repoPresent()
        val expected = if (repoPresent) {
            Regex(" commit=[0-9a-f]{7,}(-dirty)?$")
        } else {
            Regex(" commit=${BuildStamp.UNKNOWN_COMMIT}$")
        }
        assertTrue(
            "repoPresent=$repoPresent but stamp was: $line",
            expected.containsMatchIn(line),
        )
    }

    @Test
    fun `a whole header stays well inside the header-only prune threshold`() {
        // prune() deletes plain logs under MIN_USEFUL_LOG_BYTES as header-only.
        // The property that matters is the WHOLE header, not this line alone -
        // asserting the relation, not a magic number, so lowering the threshold
        // or lengthening a sibling header line surfaces here.
        val stamp = BuildStamp.headerLine(
            versionName = "10.10.10-onbtest",
            versionCode = 999,
            buildType = "onbtest",
            commit = "6219cb5",
            dirty = true,
        )
        // The started-line's tail is an Instant, whose rendered width is what
        // matters here, not its value - so it is stood in for by filler.
        val startedLine = "# bike-radar capture started " + "X".repeat(instantWidth)
        val header = listOf(
            startedLine,
            "# format: unix_ms char_tail_4hex hex_bytes_no_spaces",
            stamp,
        ).sumOf { it.length + 1 }
        assertTrue(
            "header $header bytes must stay under the prune threshold",
            header < CaptureLogManager.MIN_USEFUL_LOG_BYTES,
        )
    }
}
