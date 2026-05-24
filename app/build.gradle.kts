import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val debugKeystoreFile = rootProject.file("debug.keystore")

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
    compileSdk = 36

    defaultConfig {
        applicationId = "es.jjrh.bikeradar"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.7.1-alpha"

        buildConfigField("String", "HA_BASE_URL", "\"${localProps.getProperty("ha.base.url", "")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${localProps.getProperty("ha.token", "")}\"")

        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isMinifyEnabled = false
            val release = signingConfigs.findByName("release")
            // If the release signing config is wired up (env vars present),
            // use it. Otherwise fall back to debug signing so a local
            // developer can still produce a release-variant APK for
            // inspection without needing the production keystore.
            signingConfig = release ?: signingConfigs.getByName("debug")
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
            // so the variant doesn't pre-fill JJ's real creds and
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // JVM unit tests under app/src/test resolve against the merged Android
    // resources/manifest (so Robolectric can inflate views, and Compose
    // tests can find the activity in AndroidManifest.xml). isReturnDefaultValues
    // makes unmocked Android stubs return defaults instead of throwing —
    // useful for smoke tests that touch APIs we don't shadow.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

ktlint {
    // Grandfather the existing findings so only NEW violations fail the
    // build. Regenerate with `:app:ktlintGenerateBaseline` after a
    // deliberate, reviewed sweep, not as a way to silence fresh issues.
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
}

// Classes kept out of the coverage figure: Compose UI (covered by Paparazzi
// snapshots, not JaCoCo) and framework-bound services. Without this the raw
// number reflects mostly untestable UI/service code rather than the logic the
// unit suite targets. Shared by the report and the verification gate below.
val coverageExcludes = listOf(
    "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*ComposableSingletons*.*",
    "es/jjrh/bikeradar/ui/**", // Compose UI - Paparazzi covers this
    "**/RadarOverlayView*.*", // Canvas view, excluded from unit tests
    "**/DebugOverlayService*.*", // dev/test-only foreground services
    "**/ReplayService*.*",
    "**/SyntheticScenarioService*.*",
    "**/ScreenshotCaptureService*.*",
)
// AGP 9.2 emits Kotlin classes under built_in_kotlinc; if a future AGP moves
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

// Coverage ratchet. A coarse line floor on the whole testable layer catches
// gross regressions (a disabled test class), and a branch floor on the
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
    violationRules {
        // Overall line coverage holds at the baseline, with slack below the
        // current figure so legitimately hard-to-test new code doesn't trip
        // it. Raise as coverage grows.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.45".toBigDecimal()
            }
        }
        // Branch coverage on the safety-critical deciders. LiveDataDecoder is
        // intentionally not listed (lower branch coverage; held by the line
        // floor above).
        rule {
            element = "CLASS"
            includes = listOf(
                "es.jjrh.bikeradar.AlertDecider",
                "es.jjrh.bikeradar.WalkAwayDecider",
                "es.jjrh.bikeradar.CriticalBatteryDecider",
                "es.jjrh.bikeradar.RadarV2Decoder",
            )
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// Exclude Paparazzi screenshot tests from the standard `testDebugUnitTest`
// task UNLESS the run was invoked through a Paparazzi gate task
// (`recordPaparazziDebug` / `verifyPaparazziDebug`), which drive this very
// same `testDebugUnitTest` task. Reason: Paparazzi 2.0.0-SNAPSHOT ships a
// layoutlib whose JNI loader fails in cold-cache JVMs (e.g. CI), even
// though direct dlopen of the same .so works, so plain `gradle test` and
// CI must skip the snapshot tests. But the Paparazzi record/verify tasks
// run warm and locally and ARE the gate - they must actually execute the
// snapshot tests, not silently skip them. An unconditional exclusion left
// the gate dormant: it verified zero snapshots. Drop this block once
// Paparazzi alpha05 ships.
//
// Discriminator: the requested task names. The Paparazzi gate tasks carry
// "Paparazzi" in their name and appear in startParameter on a gate run
// (see AGENTS.md); CI and `gradle test` do not. Caveat: an invocation
// that requests BOTH a Paparazzi task and plain tests in one go would run
// the snapshot tests in testDebugUnitTest too - no such combined/aggregate
// task exists in this repo (CI calls testDebugUnitTest and assembleDebug
// directly). Reading startParameter at configuration time is
// configuration-cache safe; the requested-task set is part of the cache
// key, so a plain-test invocation and a gate invocation resolve to
// separate entries.
//
// `withType<Test>().matching` defers until AGP registers the unit-test task,
// so the configuration doesn't fail with "task not found".
val runningPaparazziGate = gradle.startParameter.taskNames.any {
    it.contains("Paparazzi", ignoreCase = true)
}
tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
    filter {
        // Snapshot tests hit the layoutlib loader; skip them on plain
        // test / CI runs, but let the Paparazzi gate run them. Pattern
        // match so newly-added snapshot tests need no list maintenance.
        if (!runningPaparazziGate) {
            excludeTestsMatching("*SnapshotTest")
        }
        // Custom-View tests rely on android.graphics.Canvas which the
        // Robolectric+layoutlib stack can't load in cold-cache JVMs. Not
        // a snapshot test, so keep it out of this task in every mode.
        excludeTestsMatching("es.jjrh.bikeradar.RadarOverlayViewTest")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
