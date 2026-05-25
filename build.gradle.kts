plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Roborazzi renders screenshot tests through Robolectric Native Graphics,
    // so they run in cold-cache CI (unlike Paparazzi's layoutlib JNI loader,
    // which forced the old test-exclusion hack). 1.63.0 is built on this exact
    // Kotlin 2.3.21 / Gradle 9 toolchain.
    id("io.github.takahirom.roborazzi") version "1.63.0" apply false
    // Static analysis. ktlint does no type resolution, so unlike detekt it
    // is tolerant of the exact Kotlin compiler and safe on Kotlin 2.3.21.
    // detekt is held until a stable release targets Kotlin 2.3 (only
    // 2.0.0-alpha does today); not adopting an alpha into a public build.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}
