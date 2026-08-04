// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Formats the build-provenance line written into every capture-log header.
 *
 * Why it exists: a capture recorded which radar firmware produced it but
 * nothing about the app that decoded it, so attributing a ride to the code it
 * ran meant comparing the APK's install time against commit timestamps - an
 * inference, not a record, and one that silently misattributes a build made
 * from an edited working tree. Debug APKs all report the same
 * versionName/versionCode until the next release bump, so the version alone
 * does not discriminate; the commit does.
 *
 * Scope of the stamp, stated precisely because the obvious reading is wider
 * than the truth: it names the code only for non-release builds. A release
 * build carries version and build type but no commit (see [headerLine]), so a
 * release-variant capture is attributable to a published tag but NOT to a
 * particular tree - two release APKs built from different code stamp
 * identically. A short SHA is also only resolvable once that commit is pushed;
 * this repo rewrites unpushed history freely.
 */
internal object BuildStamp {
    /** Marker for a non-release build whose commit could not be resolved.
     *  Records an attempted-and-failed resolution, rather than an absent
     *  field the reader has to interpret. */
    const val UNKNOWN_COMMIT = "unknown"

    /**
     * Build the header line.
     *
     * [commit] is null for release builds, which omit the field entirely: a
     * published APK is pinned by its tag, and the build strips
     * non-reproducible bytes so F-Droid can verify it builds from source (see
     * `dependenciesInfo` in app/build.gradle.kts), which an embedded SHA would
     * work against. Note the abbreviation length is the non-reproducible part -
     * `--short` scales with object count, so two clones of one tag can differ.
     *
     * A non-release build that could not resolve its SHA is stamped
     * [UNKNOWN_COMMIT] rather than omitting the field, so the log records that
     * resolution was attempted and failed instead of leaving a reader to infer
     * it from an absent field.
     *
     * [dirty] is ignored when the commit is unresolved: there is no commit to
     * qualify, so "unknown-dirty" would claim more than is known.
     */
    fun headerLine(
        versionName: String,
        versionCode: Int,
        buildType: String,
        commit: String?,
        dirty: Boolean,
    ): String {
        val base = "# app version=$versionName code=$versionCode build=$buildType"
        if (commit == null) return base
        val resolved = commit.ifBlank { UNKNOWN_COMMIT }
        if (resolved == UNKNOWN_COMMIT) return "$base commit=$UNKNOWN_COMMIT"
        val suffix = if (dirty) "-dirty" else ""
        return "$base commit=$resolved$suffix"
    }
}

/** Binds [BuildStamp] to the generated BuildConfig fields. Kept separate so the
 *  formatting stays pure and unit-testable without a build-time dependency.
 *  The empty-means-release mapping lives here, at the boundary, rather than
 *  inside the formatter. */
internal object BuildConfigStamp {
    fun line(): String = BuildStamp.headerLine(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        commit = BuildConfig.GIT_COMMIT.ifEmpty { null },
        dirty = BuildConfig.GIT_DIRTY,
    )
}
