import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val debugKeystoreFile = rootProject.file("debug.keystore")

/**
 * Read a git value at configuration time, or null if git could not answer.
 *
 * Null and "" are deliberately distinct: "" means git ran and returned nothing
 * (a clean tree), null means it could not run at all - no binary, no .git as in
 * a source tarball, a non-zero exit, or a rejected mount. Collapsing the two
 * would let a failed dirty-check read as "clean" and stamp an edited tree with
 * a bare commit, the exact misattribution the marker exists to prevent.
 *
 * `-C` pins the repo so git cannot walk up into a parent repository, and
 * `--no-optional-locks` stops a build from writing the developer's git index as
 * a side effect. providers.exec keeps the result a configuration-cache input
 * rather than a cache-breaking side effect.
 */
fun gitValue(vararg args: String): String? = try {
    val out = providers.exec {
        commandLine("git", "--no-optional-locks", "-C", rootProject.projectDir.absolutePath, *args)
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue != 0) null else out.standardOutput.asText.get().trim()
} catch (_: Exception) {
    null
}

// Stamped into non-release capture logs only. Every debug APK reports the same
// versionName/versionCode between releases, so the commit is the only field
// that says which code produced a given ride log. Release builds deliberately
// omit it - see BuildStamp's KDoc and the dependenciesInfo strip below.
val gitCommitOrNull = gitValue("rev-parse", "--short", "HEAD")

// Scoped to the paths that actually build the APK, NOT the whole worktree.
// A bare `status --porcelain` reports anything untracked, and this tree carries
// per-developer directories excluded only via .git/info/exclude - which is
// per-clone and not synced, while the working tree is. On any other clone those
// show up as untracked, pinning the flag to dirty forever and destroying the
// only field that discriminates one debug build from another.
// Untracked files inside these paths still count: an untracked .kt under
// app/src is compiled into the APK, so the tree is genuinely not the named
// commit. Build output and .gradle are gitignored and never appear.
val buildInputPaths = listOf(
    "app",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle",
    "gradle.properties",
)
val gitStatusOrNull = gitValue("status", "--porcelain", "--", *buildInputPaths.toTypedArray())
// ifBlank as well as the elvis: an exit-0-with-empty-stdout would otherwise
// leave GIT_COMMIT empty, which the boundary reads as "release" and omits the
// field entirely - making a debug build indistinguishable from a released one.
val gitCommit = gitCommitOrNull?.ifBlank { null } ?: "unknown"

// A status that failed outright also counts as dirty - erring toward "cannot
// vouch for this tree" is the safe direction.
val gitDirty = gitStatusOrNull == null || gitStatusOrNull.isNotBlank()

// Both failures are warned separately: a failed status alone would otherwise
// stamp a clean tree -dirty forever with nothing said.
if (gitCommitOrNull.isNullOrBlank()) {
    logger.warn("bike-radar: no git commit resolved - non-release logs stamp commit=unknown")
}
if (gitStatusOrNull == null) {
    logger.warn("bike-radar: git status failed - non-release logs stamp -dirty regardless of tree")
}

/**
 * Generate a debug.keystore via `keytool` when one is not already
 * present at the root. Implemented as a typed task class so the
 * @TaskAction body does not close over the build script's Project
 * reference, which is required for Gradle's configuration cache
 * (otherwise the doLast closure on a generic DefaultTask would
 * capture script-object references that the cache cannot serialise).
 *
 * Uses ProcessBuilder rather than Gradle's exec{} / commandLine{}
 * because those Project-level APIs were removed in Gradle 9.
 * ProcessBuilder is JDK-native and stable across versions.
 */
abstract class EnsureDebugKeystore : DefaultTask() {
    @get:OutputFile
    abstract val keystoreFile: RegularFileProperty

    @TaskAction
    fun run() {
        val file = keystoreFile.get().asFile
        if (file.exists()) return
        val process = ProcessBuilder(
            "keytool", "-genkeypair", "-v",
            "-keystore", file.absolutePath,
            "-storepass", "android",
            "-keypass", "android",
            "-alias", "androiddebugkey",
            "-dname", "CN=Android Debug,O=Android,C=US",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
        ).inheritIO().start()
        val code = process.waitFor()
        if (code != 0) {
            throw RuntimeException(
                "keytool exited with status $code while generating debug.keystore",
            )
        }
    }
}

tasks.register<EnsureDebugKeystore>("ensureDebugKeystore") {
    keystoreFile.set(rootProject.layout.projectDirectory.file("debug.keystore"))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("ensureDebugKeystore")
}

android {
    namespace = "es.jjrh.bikeradar"
    compileSdk = 37

    // Pinned rather than left to AGP's default, which is a different version
    // (36.0.0 as of AGP 9.3) from the one every workflow installs. Leaving it
    // implicit means the build tools arrive by auto-download instead of the
    // sdkmanager line, and verifyReleaseDexKeeps resolves dexdump out of this
    // directory. Keep in step with the sdkmanager lines in .github/workflows.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "es.jjrh.bikeradar"
        minSdk = 31
        targetSdk = 36
        versionCode = 23
        versionName = "1.2.0"

        // Empty by default so no HA bearer token is ever baked into a
        // release (or any non-debug) APK's DEX. The debug buildType below
        // re-seeds from local.properties for local dev convenience only.
        buildConfigField("String", "HA_BASE_URL", "\"\"")
        buildConfigField("String", "HA_TOKEN", "\"\"")

        // Empty by default so a release APK embeds no SHA: it would be a
        // non-reproducible byte in the DEX, working against the F-Droid
        // build-from-source check that dependenciesInfo below exists to pass.
        // A released build is already pinned by its tag. The debug buildType
        // fills these in, and onbtest inherits them via initWith(debug).
        buildConfigField("String", "GIT_COMMIT", "\"\"")
        buildConfigField("boolean", "GIT_DIRTY", "false")

        vectorDrawables { useSupportLibrary = true }
    }

    // AGP otherwise embeds a Google-signed dependency-metadata blob in the
    // APK. It has no runtime purpose and can't be reproduced byte-for-byte, so
    // it breaks F-Droid's build-from-source reproducibility check. Strip it.
    // We ship APKs, not AABs; includeInBundle is set for completeness.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signing reads from env vars so CI can inject a keystore
        // without the paths or passwords ever appearing in committed files.
        // Local release builds work too if the same vars are set in the shell.
        val releaseKsPath = System.getenv("ANDROID_KEYSTORE_PATH")
        val releaseKsPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
        val releaseKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
        if (
            releaseKsPath != null &&
            releaseKsPass != null &&
            releaseKeyAlias != null &&
            releaseKeyPass != null
        ) {
            create("release") {
                storeFile = file(releaseKsPath)
                storePassword = releaseKsPass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 in shrink-only mode (see proguard-rules.pro): removes dead
            // code - chiefly the unused material-icons-extended dex (~5 MB) -
            // without renaming or optimizing, so the reflection-free string
            // lookups (org.json, enum valueOf, prefs keys) and the BLE
            // callbacks keep working. verifyReleaseDexKeeps below holds the
            // enum-name part of that list against the packaged APK; the
            // string literals and the callbacks are not checked, because no
            // R8 configuration rewrites them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val release = signingConfigs.findByName("release")
            // If the release signing config is wired up (env vars present),
            // use it. Otherwise fall back to debug signing so a local
            // developer can still produce a release-variant APK for
            // inspection without needing the production keystore.
            signingConfig = release ?: signingConfigs.getByName("debug")
        }
        getByName("debug") {
            // Local dev convenience only: seed HA creds from local.properties
            // so a fresh debug install needn't re-enter them. Debug APKs are
            // never distributed. Configured before onbtest so its
            // initWith(debug) inherits these, then re-zeroes them below.
            buildConfigField("String", "HA_BASE_URL", "\"${localProps.getProperty("ha.base.url", "")}\"")
            buildConfigField("String", "HA_TOKEN", "\"${localProps.getProperty("ha.token", "")}\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
            // Pseudolocales (en-XA / en-XB) ship only in debug builds: switch
            // the device to "English (XA)" to eyeball string overflow and spot
            // any still-hardcoded text (it renders un-accented while everything
            // externalised shows [Ḩéllo Wörld]). They cannot be set via
            // Robolectric @Config qualifiers, so they are a manual on-device
            // check, not a snapshot gate.
            isPseudoLocalesEnabled = true
        }
        // Throwaway variant for walking through Onboarding without
        // touching the production install's prefs / paired devices.
        // applicationIdSuffix lets it install side-by-side; the strings
        // override at src/onbtest/res relabels the launcher.
        create("onbtest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".onbtest"
            versionNameSuffix = "-onbtest"
            // The point of onbtest is to walk Onboarding from genuine
            // fresh-install state. Wipe the local.properties HA seed
            // so the variant doesn't pre-fill real HA creds and
            // can't accidentally hit real HA via "Test connection".
            buildConfigField("String", "HA_BASE_URL", "\"\"")
            buildConfigField("String", "HA_TOKEN", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        // Translation correctness is the #1 gate for contributed locale PRs.
        // These are error-severity by default, but pin them explicitly so a
        // dropped string or a mangled/auto-translated format arg (a dropped
        // %1$s crashes the app at runtime) fails lintDebug in CI rather than
        // shipping. abortOnError keeps lintDebug a hard gate.
        error +=
            listOf(
                "MissingTranslation",
                "ExtraTranslation",
                "StringFormatInvalid",
                "StringFormatMatches",
                "ImpliedQuantity",
            )
        abortOnError = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // JVM unit tests under app/src/test resolve against the merged Android
    // resources/manifest (so Robolectric can inflate views, and Compose
    // tests can find the activity in AndroidManifest.xml). isReturnDefaultValues
    // makes unmocked Android stubs return defaults instead of throwing -
    // useful for smoke tests that touch APIs we don't shadow.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Split test classes across forked JVMs; without this the
                // whole suite (incl. every Robolectric sandbox) runs in ONE
                // JVM regardless of host cores. Capped: each Robolectric
                // fork costs ~1 GB heap and its own sandbox warm-up, so
                // more forks than this wastes memory for no wall-clock win.
                test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 3).coerceIn(1, 8)
                test.maxHeapSize = "1g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

ktlint {
    // The baseline is empty: the codebase is fully formatted, so every
    // violation fails the build. Regenerate with `:app:ktlintGenerateBaseline`
    // only after a deliberate, reviewed sweep, not to silence fresh issues.
    baseline.set(file("config/ktlint/baseline.xml"))
}

jacoco {
    toolVersion = "0.8.13"
}

// On-the-fly JaCoCo agent on the unit-test task. Robolectric loads classes
// through its own sandbox classloader, which AGP's offline instrumentation
// (enableUnitTestCoverage) never records - so Robolectric-tested classes
// reported 0% (AlertBeeper, the receivers, the smoke tests). The on-the-fly
// agent instruments at load time across every classloader, and
// includeNoLocationClasses lets it count Robolectric's no-location classes.
// excludes drops the JDK internals the agent can't instrument on Java 9+.
// See robolectric#3023 / robolectric#5575.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
    // Corpus-replay gate plumbing: -Pbikeradar.corpusDir=<dir> reaches the
    // forked test JVM as a system property (env vars do not survive a warm
    // Gradle daemon). CorpusReplayGate assume-skips when unset, so CI and
    // corpus-less checkouts are unaffected. corpusRecord=true rewrites the
    // baseline instead of comparing.
    (project.findProperty("bikeradar.corpusDir") as String?)?.let {
        systemProperty("bikeradar.corpusDir", it)
        // The gate's summary lines (captures replayed, fresh-capture count)
        // are its product; Gradle hides test stdout by default. Scoped to
        // corpus runs so the ordinary suite stays quiet.
        testLogging { showStandardStreams = true }
    }
    (project.findProperty("bikeradar.corpusRecord") as String?)?.let {
        systemProperty("bikeradar.corpusRecord", it)
    }
    // Cue-ledger golden regeneration: -Pbikeradar.cueLedgerRecord=true makes
    // CueLedgerReplayTest print the freshly-computed ledger to stdout (paste
    // it back into the test's GOLDEN constant) instead of asserting. Scoped
    // so the ordinary suite stays quiet and the test still verifies in CI.
    (project.findProperty("bikeradar.cueLedgerRecord") as String?)?.let {
        systemProperty("bikeradar.cueLedgerRecord", it)
        testLogging { showStandardStreams = true }
    }
}

/**
 * Runs the release DEX keep gate over the packaged APK.
 *
 * Typed task with ProcessBuilder for the same reasons as
 * [EnsureDebugKeystore] above: no capture of the script's Project reference,
 * and no Gradle 9-removed exec APIs.
 */
abstract class VerifyReleaseDexKeeps : DefaultTask() {
    @get:InputDirectory
    abstract val apkDir: DirectoryProperty

    @get:InputFile
    abstract val gateScript: RegularFileProperty

    @get:InputFile
    abstract val dexdump: RegularFileProperty

    // Not inheritIO(): a Gradle task's ProcessBuilder inherits the DAEMON's
    // stdio, so anything the gate printed would land in the daemon log rather
    // than the console the failure is read on.
    private fun runGate(vararg args: String): Pair<Int, String> {
        val process = try {
            ProcessBuilder(
                "python3",
                gateScript.get().asFile.absolutePath,
                *args,
            ).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw RuntimeException(
                "verifyReleaseDexKeeps needs python3 on PATH. It is not wired to " +
                    "assembleRelease, so building the APK does not require it.",
                e,
            )
        }
        // Drained before waitFor(), or a full pipe deadlocks the wait.
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return process.waitFor() to output
    }

    @TaskAction
    fun run() {
        // The parser's own check, before it is trusted against the APK. If
        // section tracking ever regresses it returns a SUPERSET of the static
        // fields, which makes the real check pass unconditionally - a green
        // gate over a broken one, the failure this whole task exists to avoid.
        val (selfTestCode, selfTestOutput) = runGate("--self-test")
        if (selfTestCode != 0) {
            throw RuntimeException("release DEX keep gate self-test failed:\n$selfTestOutput")
        }

        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        val apk = apks.singleOrNull()
            ?: throw RuntimeException(
                "expected exactly one APK in ${apkDir.get().asFile}, found ${apks.size}",
            )
        val (code, output) = runGate(
            "--apk",
            apk.absolutePath,
            "--dexdump",
            dexdump.get().asFile.absolutePath,
        )
        if (code != 0) {
            throw RuntimeException("release DEX keep gate failed:\n$output")
        }
        logger.lifecycle(output)
    }
}

// Rationale and scope: AGENTS.md, "Release DEX keep gate".
//
// Deliberately NOT wired to assembleRelease. F-Droid builds this from source
// to verify the published APK reproduces, and a finalizer would put python3
// and a matching build-tools dexdump inside THEIR build. The workflows name
// this task alongside assembleRelease instead, so the requirement stays ours.
tasks.register<VerifyReleaseDexKeeps>("verifyReleaseDexKeeps") {
    // apkDir is packageRelease's output directory, so without this Gradle
    // rejects the task graph as having an implicit dependency.
    dependsOn("packageRelease")
    apkDir.set(layout.buildDirectory.dir("outputs/apk/release"))
    gateScript.set(
        rootProject.layout.projectDirectory.file("scripts/check-release-dex-keeps.py"),
    )
    val buildTools = android.buildToolsVersion
    dexdump.set(
        androidComponents.sdkComponents.sdkDirectory.map {
            it.file("build-tools/$buildTools/dexdump")
        },
    )
}

// Classes kept out of the coverage figure: Compose UI (covered by Roborazzi
// snapshots, not JaCoCo) and framework-bound services. Without this the raw
// number reflects mostly untestable UI/service code rather than the logic the
// unit suite targets. Shared by the report and the verification gate below.
val coverageExcludes = listOf(
    "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*ComposableSingletons*.*",
    "es/jjrh/bikeradar/ui/**", // Compose UI - Roborazzi covers this
    "**/RadarOverlayView*.*", // Canvas view - Roborazzi-rendered, not line-coverable
    "**/DebugOverlayService*.*", // dev/test-only foreground services
    "**/ReplayService*.*",
    "**/SyntheticScenarioService*.*",
    "**/ScreenshotCaptureService*.*",
)
// The diff-coverage gate's WIDER scope. It is per-diff, so there is nothing to
// dilute: a new inline `when` over app state in a Composable body lands as
// uncovered changed lines and fails, so derivation written there is not
// exempt from the gate. Only genuine leaf/render files stay out, which is
// Roborazzi's actual remit.
//
// Do not claim this would have caught the two-state Home Assistant row: it
// would not. That was `haHealthy = !haErrorRecent && (...)`, a single line
// already executed with both values across nine goldens - a wrong predicate,
// not an uncovered branch. Coverage measures execution, not correctness.
//
// Deliberately NOT the ratchet's list. The two gates behave oppositely under
// narrowing: the ratchet is an aggregate, so pulling Compose rendering in
// drops the ratio and the only way back to green is lowering the floors,
// which dresses a weakening up as a tightening. Keep them separate.
val diffCoverageExcludes = listOf(
    "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*ComposableSingletons*.*",
    "**/RadarOverlayView*.*", // Canvas view - Roborazzi-rendered, not line-coverable
    "**/DebugOverlayService*.*",
    "**/ReplayService*.*",
    "**/SyntheticScenarioService*.*",
    "**/ScreenshotCaptureService*.*",
)

// AGP 9.3 emits Kotlin classes under built_in_kotlinc; if a future AGP moves
// this path the report/verification go empty (not silently wrong) - re-point.
val coverageClassDir = "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
// The on-the-fly agent writes build/jacoco/testDebugUnitTest.exec.
val coverageExecDir = "jacoco"

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "JaCoCo coverage scoped to testable logic (excludes Compose UI + framework services)."
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir(coverageClassDir)) { exclude(coverageExcludes) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir(coverageExecDir)) { include("**/*.exec") },
    )
}

// The report the diff-coverage gate reads. Wider than the ratchet's on
// purpose: Compose UI stays IN, so derivation written inline in a Composable
// body is gated rather than exempt. Per-diff there is nothing to dilute.
//
// Do NOT narrow the ratchet's report to match. The two behave oppositely
// under narrowing: the ratchet is an aggregate, so pulling Compose rendering
// into it drops the ratio and the only route back to green is lowering the
// floors, which is a weakening dressed up as a tightening.
//
// The dependency below is the load-bearing part. Roborazzi composes only when
// its own task property is set, so depending on the plain unit-test task
// overwrites the exec data with a run in which no golden rendered - and every
// Composable exercised solely by a snapshot test then reads as uncovered.
// That artefact put Compose UI at 52 percent and BatteryChip at zero while
// goldens plainly rendered it; against the rendering task the same code is 78
// percent. If this number ever collapses, check this dependency first.
tasks.register<JacocoReport>("jacocoDiffReport") {
    // verifyRoborazziDebug, NOT testDebugUnitTest. Roborazzi only composes
    // when its task property is set, and a bare testDebugUnitTest run
    // overwrites the exec data with one where no golden rendered - so every
    // Composable exercised solely by a snapshot test reads as uncovered.
    dependsOn("verifyRoborazziDebug")
    group = "verification"
    description = "JaCoCo coverage including Compose UI, read by the diff-coverage gate."
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir(coverageClassDir)) { exclude(diffCoverageExcludes) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir(coverageExecDir)) { include("**/*.exec") },
    )
}

// Coverage ratchet. Project floors on the whole testable layer catch gross
// regressions (a disabled test class), and a tighter branch floor on the
// safety-critical deciders holds the line where a regression is most
// dangerous. Report-only stays the default; this gate is opt-in via CI/QC.
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Fails if testable-logic coverage drops below the ratchet floor."
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir(coverageClassDir)) { exclude(coverageExcludes) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir(coverageExecDir)) { include("**/*.exec") },
    )
    // Guard against the silent-zero mode: with an empty class tree or no exec
    // data (a broken class-output path, or the agent falling back to offline
    // mode where Robolectric's sandbox classloader hides everything) the ratio
    // rules pass vacuously and the gate would wave untested code through. Fail
    // loudly instead of reporting a hollow pass.
    doFirst {
        val classCount = classDirectories.asFileTree.matching { include("**/*.class") }.files.size
        val execCount = executionData.files.count { it.exists() }
        if (classCount == 0 || execCount == 0) {
            throw GradleException(
                "JaCoCo measured nothing (classes=$classCount, exec=$execCount) - coverage " +
                    "cannot be verified. Check the on-the-fly agent and the class-output path.",
            )
        }
    }
    violationRules {
        // Project floors on the testable layer, each ratcheted a few points
        // below the current figure (LINE ~88%, INSTRUCTION ~87%, BRANCH ~78%)
        // so legitimately hard-to-test new code doesn't trip them while a mass
        // regression still fails the build. The per-PR diff-coverage gate
        // guards new code; these guard against wholesale drops. Raise as
        // coverage grows.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.78".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.68".toBigDecimal()
            }
        }
        // Branch coverage on the safety-critical decision classes. Wildcards
        // instead of an enumerated list so every NEW *Decider / *Deriver is
        // born inside the gate - the old list silently exempted five deciders
        // added after it was written (all measured >= 100% when widened).
        // EBikeStatusDecoder is intentionally not matched ("Decoder", lower
        // branch coverage; held by the line floor above); RadarV2Decoder is
        // opted in by name.
        rule {
            element = "CLASS"
            includes = listOf(
                "es.jjrh.bikeradar.*Decider",
                "es.jjrh.bikeradar.*Deriver",
                "es.jjrh.bikeradar.RadarV2Decoder",
            )
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.93".toBigDecimal()
            }
        }
    }
}

// Roborazzi renders via Robolectric Native Graphics, so the screenshot tests
// are ordinary Robolectric unit tests - they run in plain `testDebugUnitTest`
// and in CI with no layoutlib loader and no exclusion hack. captureRoboImage()
// is a no-op unless a roborazzi.test.* property is set, so plain test runs just
// exercise the composition; `verifyRoborazziDebug` is the pixel gate (and
// `recordRoborazziDebug` regenerates goldens). Keep the goldens under
// src/test/snapshots/images (their historical home, paired with the
// roborazzi.record.filePathStrategy in gradle.properties) rather than the
// default build/outputs.
roborazzi {
    outputDir.set(file("src/test/snapshots/images"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.72.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.72.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.72.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
