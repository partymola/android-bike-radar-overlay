// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.testutil

import java.io.File

/**
 * Where a file is, from a test's point of view.
 *
 * Gradle runs unit tests with the module directory as the working directory,
 * while a test invoked from the repo root has to reach one level down, so every
 * such test has to probe both. One place rather than one per test.
 */
object RepoFiles {

    /**
     * The app module's build file.
     *
     * The candidate order is load-bearing: a `build.gradle.kts` also sits at the
     * repo root and declares no dependencies, so probing the bare name first
     * reads THAT whenever the working directory is the repo root.
     */
    fun moduleBuildFile(): File = moduleBuildFileFrom(File("."))

    /** A main-source file or directory, by name under the app's package. */
    fun mainSource(name: String): File = mainSourceFrom(File("."), name)

    /** The AIDL source root, which sits beside `java/` rather than under it. */
    fun aidlDir(): File = locate(
        File("."),
        "app/src/main/aidl/es/jjrh/bikeradar/ipc",
        "src/main/aidl/es/jjrh/bikeradar/ipc",
    )

    /**
     * The app's manifest, which is where the strings a consumer binds with
     * really live.
     */
    fun manifest(): File = locate(File("."), "app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")

    /** A file at the repo ROOT, which is one level up from the module. */
    fun repoFile(fileName: String): File = locate(File("."), fileName, "../$fileName")

    /**
     * These two take their starting directory so a test can put them somewhere
     * other than the working directory, which is the only way to make the
     * two-candidate case reachable: under Gradle just one candidate exists, so
     * either order resolves and nothing running on the real working directory
     * can pin the order. A test that cannot see the order cannot pin it.
     *
     * The zero-argument resolvers above hardwire `File(".")` and the JVM cannot
     * change directory, so a mutant reversing one of them still resolves
     * correctly under Gradle. `repoPresent` is the exception in practice,
     * because `BuildStampTest` calls it from the real working directory.
     */
    internal fun moduleBuildFileFrom(base: File): File = locate(base, "app/build.gradle.kts", "build.gradle.kts")

    internal fun mainSourceFrom(base: File, name: String): File = locate(
        base,
        "app/src/main/java/es/jjrh/bikeradar/$name",
        "src/main/java/es/jjrh/bikeradar/$name",
    )

    /**
     * Whether the build ran inside a git checkout, for the tests that branch on
     * it rather than skipping.
     *
     * `exists()` and not `isDirectory`: in a git worktree `.git` is a FILE, so
     * an isDirectory probe reads a good checkout as a source zip and the test
     * then asserts the degraded value is the correct one.
     *
     * Absence is an answer here rather than a failure, which is why this does
     * not go through `locate`. `repoPresentIsFalseWhereThereIsNoCheckoutAtAll`
     * pins it.
     */
    fun repoPresent(): Boolean = repoPresentFrom(File("."))

    internal fun repoPresentFrom(base: File): Boolean = listOf(".git", "../.git").any { File(base, it).exists() }

    private fun locate(base: File, vararg candidates: String): File {
        val found = candidates.map { File(base, it) }.firstOrNull { it.exists() }
        return found ?: error("could not locate ${candidates.first()} from ${base.absolutePath}")
    }
}
