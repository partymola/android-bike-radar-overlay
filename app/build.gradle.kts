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
        versionCode = 15
        versionName = "0.10.0-alpha"

        // Empty by default so no HA bearer token is ever baked into a
        // release (or any non-debug) APK's DEX. The debug buildType below
        // re-seeds from local.properties for local dev convenience only.
        buildConfigField("String", "HA_BASE_URL", "\"\"")
        buildConfigField("String", "HA_TOKEN", "\"\"")

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
            // R8 in shrink-only mode (see proguard-rules.pro): removes dead
            // code - chiefly the unused material-icons-extended dex (~5 MB) -
            // without renaming or optimizing, so the reflection-free string
            // lookups (org.json, enum valueOf, prefs keys) and the BLE
            // callbacks keep working. A minified release MUST be ride-tested
            // before the next v* tag.
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
        // Overall line coverage holds at the baseline, with slack below the
        // current figure so legitimately hard-to-test new code doesn't trip
        // it. Raise as coverage grows.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.55".toBigDecimal()
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
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.63.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
