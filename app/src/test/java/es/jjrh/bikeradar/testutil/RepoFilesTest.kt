// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the locator the source-reading tests depend on. Without this, a
 * locator that resolved the wrong file would not fail here - it would make
 * those tests pass on content they never meant to read.
 *
 * The ordering tests use a synthetic tree because the working directory
 * under Gradle cannot exercise the case: only one candidate exists there,
 * so either order resolves and the order is invisible. A synthetic tree
 * also does not depend on walking up to the real root or on what that root
 * happens to contain.
 */
class RepoFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun moduleBuildFilePrefersTheModulesWhenBothCandidatesExist() {
        // The layout a run started from the repo root sees: a root build file
        // alongside the module's. Picking the root one is the silent failure,
        // because it declares no dependencies and a test counting them sees
        // zero rather than an error.
        val root = tmp.newFolder()
        File(root, "build.gradle.kts").writeText("plugins { }\n")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("the module's\n")

        assertTrue(
            "resolved the root build file instead of the module's",
            RepoFiles.moduleBuildFileFrom(root).readText().contains("the module's"),
        )
    }

    @Test
    fun mainSourceResolvesRelativeToTheStartingDirectory() {
        val root = tmp.newFolder()
        File(root, "app/src/main/java/es/jjrh/bikeradar").mkdirs()
        File(root, "app/src/main/java/es/jjrh/bikeradar/Marker.kt").writeText("the marker\n")

        assertTrue(
            "ignored the starting directory it was given",
            RepoFiles.mainSourceFrom(root, "Marker.kt").readText().contains("the marker"),
        )
    }

    @Test
    fun moduleBuildFileResolvesInThisReposRealLayout() {
        // Pins that the real tree still matches what the locator expects,
        // which the synthetic tests above cannot tell you.
        assertTrue(
            "resolved a build file that is not the app module's",
            RepoFiles.moduleBuildFile().readText().contains("""namespace = "es.jjrh.bikeradar""""),
        )
        // The `app/` in both repo-root candidates is a literal. Renaming the
        // module would kill that fallback while every other test here kept
        // passing, because they all resolve through the bare candidate.
        assertEquals(
            "the module directory is no longer named app",
            "app",
            RepoFiles.moduleBuildFile().canonicalFile.parentFile?.name,
        )
    }

    @Test
    fun mainSourceResolvesInThisReposRealLayout() {
        assertTrue(
            "resolved a file that is not HaClient.kt",
            RepoFiles.mainSource("HaClient.kt").readText().contains("class HaClient("),
        )
    }

    @Test
    fun repoPresentSeesAWorktreeWhereGitIsAFileNotADirectory() {
        // `git worktree` writes `.git` as a file holding a gitdir pointer, so
        // a checkout that is fully a repo looks like a source zip to an
        // isDirectory test - and the caller then asserts the degraded stamp
        // is the correct one.
        val worktree = tmp.newFolder()
        File(worktree, ".git").writeText("gitdir: /elsewhere/.git/worktrees/x\n")

        assertTrue(
            "a worktree checkout was read as having no repo",
            RepoFiles.repoPresentFrom(worktree),
        )
    }

    @Test
    fun repoPresentIsFalseWhereThereIsNoCheckoutAtAll() {
        // The branch a source-zip build genuinely needs.
        assertFalse(
            "an empty directory was read as a git checkout",
            RepoFiles.repoPresentFrom(tmp.newFolder()),
        )
    }

    @Test
    fun aMissingFileFailsLoudlyRatherThanReturningSomethingElse() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            RepoFiles.mainSource("NoSuchFileInThisRepo.kt")
        }
        assertTrue(
            "the failure must name what it looked for",
            thrown.message.orEmpty().contains("NoSuchFileInThisRepo.kt"),
        )
    }
}
