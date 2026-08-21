// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.testutil

import java.io.File

/**
 * Facts about the repo's layout, from a test's point of view: where a file
 * is, and whether a git checkout is there at all.
 *
 * Gradle runs unit tests with the module directory as the working directory,
 * while a test invoked from the repo root has to reach one level down, so any
 * such test has to probe both - in an order that is easy to get wrong, which
 * is why this is one place rather than one per test.
 */
object RepoFiles {

    /**
     * The app module's build file.
     *
     * The order is load-bearing rather than arbitrary: a `build.gradle.kts`
     * also sits at the repo root and declares no dependencies, so probing the
     * bare name first reads THAT file whenever the working directory is the
     * repo root, and a test counting what it finds sees zero.
     */
    fun moduleBuildFile(): File = moduleBuildFileFrom(File("."))

    /** A main-source file by name, for the tests that grep their own subject. */
    fun mainSource(fileName: String): File = mainSourceFrom(File("."), fileName)

    /**
     * Both resolvers take their starting directory so a test can put them
     * somewhere other than the working directory: for the build file that
     * makes the two-candidate case reachable, and for a source file it pins
     * that the argument is honoured at all. Under Gradle only ONE candidate
     * exists - from the module directory `app/build.gradle.kts` would mean
     * `app/app/...` - so either order resolves there and nothing running on
     * the real working directory can pin the order. A test that cannot see
     * the order cannot pin it, which is what these two exist for.
     *
     * The zero-argument wrappers - these two and `repoPresent` - are the part
     * no test reaches directly: they hardwire `File(".")` and the JVM cannot
     * change directory, so a mutant that inlined the wrong candidate into one
     * would still resolve correctly under Gradle. `repoPresent` is the
     * exception in practice, because `BuildStampTest` calls it from the real
     * working directory: dropping the `../.git` candidate fails that test and
     * nothing else.
     */
    internal fun moduleBuildFileFrom(base: File): File = locate(base, "app/build.gradle.kts", "build.gradle.kts")

    internal fun mainSourceFrom(base: File, fileName: String): File = locate(
        base,
        "app/src/main/java/es/jjrh/bikeradar/$fileName",
        "src/main/java/es/jjrh/bikeradar/$fileName",
    )

    /**
     * Whether the build ran inside a git checkout, for the tests that branch
     * on it rather than skipping.
     *
     * `exists()` and not `isDirectory`: in a git worktree `.git` is a FILE
     * pointing at the real git directory, so an isDirectory probe reads a
     * perfectly good checkout as a source zip. A test branching on that then
     * asserts the degraded value is the correct one, which is the opposite of
     * what it was written to catch.
     *
     * Returning false rather than throwing is deliberate, and is why this does
     * not go through `locate`: absence is an answer here, not a failure. Do not
     * unify the two - `repoPresentIsFalseWhereThereIsNoCheckoutAtAll` pins it.
     */
    fun repoPresent(): Boolean = repoPresentFrom(File("."))

    internal fun repoPresentFrom(base: File): Boolean = listOf(".git", "../.git").any { File(base, it).exists() }

    private fun locate(base: File, vararg candidates: String): File {
        val found = candidates.map { File(base, it) }.firstOrNull { it.exists() }
        return found ?: error("could not locate ${candidates.first()} from ${base.absolutePath}")
    }
}
