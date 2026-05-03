plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Tracks Paparazzi master. alpha04 (last tagged release) is incompatible
    // with Gradle 9; alpha05 milestone has the fix merged but isn't released.
    // Drop the SNAPSHOT once alpha05 ships (see Maven Central snapshots line
    // in settings.gradle.kts).
    id("app.cash.paparazzi") version "2.0.0-SNAPSHOT" apply false
}
