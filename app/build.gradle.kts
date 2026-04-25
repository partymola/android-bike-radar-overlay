import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val debugKeystoreFile = rootProject.file("debug.keystore")

tasks.register("ensureDebugKeystore") {
    outputs.file(debugKeystoreFile)
    doLast {
        if (!debugKeystoreFile.exists()) {
            // Use ProcessBuilder rather than Gradle's exec{}/commandLine{}
            // because those Project-level APIs were removed in Gradle 9.
            // ProcessBuilder is JDK-native and stable across versions.
            val process = ProcessBuilder(
                "keytool", "-genkeypair", "-v",
                "-keystore", debugKeystoreFile.absolutePath,
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
                throw GradleException("keytool exited with status $code while generating debug.keystore")
            }
        }
    }
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
        // targetSdk stays at 35 (Android 15) for now - bumping
        // compileSdk to 36 only satisfies the new AndroidX deps;
        // bumping targetSdk to 36 would opt the app into Android 16
        // runtime behaviour changes, which is a separate decision.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "HA_BASE_URL", "\"${localProps.getProperty("ha.base.url", "")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${localProps.getProperty("ha.token", "")}\"")

        vectorDrawables { useSupportLibrary = true }
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

    implementation("androidx.security:security-crypto:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
