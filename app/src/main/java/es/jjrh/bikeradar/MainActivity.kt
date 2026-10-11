// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ui.AlertVolumeScreen
import es.jjrh.bikeradar.ui.DashcamPickerSheet
import es.jjrh.bikeradar.ui.DebugScreen
import es.jjrh.bikeradar.ui.DevModeState
import es.jjrh.bikeradar.ui.MainScreen
import es.jjrh.bikeradar.ui.OnboardingScreen
import es.jjrh.bikeradar.ui.RideHistoryScreen
import es.jjrh.bikeradar.ui.SafetyNoticeScreen
import es.jjrh.bikeradar.ui.SettingsAbout
import es.jjrh.bikeradar.ui.SettingsAlertSounds
import es.jjrh.bikeradar.ui.SettingsDashcam
import es.jjrh.bikeradar.ui.SettingsEBike
import es.jjrh.bikeradar.ui.SettingsExperimental
import es.jjrh.bikeradar.ui.SettingsHa
import es.jjrh.bikeradar.ui.SettingsLicenses
import es.jjrh.bikeradar.ui.SettingsLights
import es.jjrh.bikeradar.ui.SettingsPermissions
import es.jjrh.bikeradar.ui.SettingsPrivacy
import es.jjrh.bikeradar.ui.SettingsRadar
import es.jjrh.bikeradar.ui.SettingsRadarAccessRoute
import es.jjrh.bikeradar.ui.SettingsRadarDevice
import es.jjrh.bikeradar.ui.SettingsScreen
import es.jjrh.bikeradar.ui.SoundDemoScreen
import es.jjrh.bikeradar.ui.UiTheme
import es.jjrh.bikeradar.ui.startDestination

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        DevModeState.loadFrom(prefs)
        HaCredentials(this).seedFromBuildConfigIfEmpty()

        // On every resume, the first included: re-launches the service after a
        // process kill (e.g. adb install -r), and a permission granted in the
        // system dialog or App info starts it without a relaunch. Boot is
        // covered by BootReceiver.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) ServiceAutoStart.startIfAllowed(this, prefs)
            },
        )

        setContent {
            UiTheme {
                val navController = rememberNavController()
                val startDest = startDestination(prefs.safetyNoticeAcknowledged, prefs.firstRunComplete)

                NavHost(navController = navController, startDestination = startDest) {
                    composable("safety-notice") {
                        SafetyNoticeScreen(
                            onAcknowledge = {
                                prefs.safetyNoticeAcknowledged = true
                                navController.navigate(startDestination(true, prefs.firstRunComplete)) {
                                    popUpTo("safety-notice") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("onboarding") {
                        val onFinished: () -> Unit = {
                            prefs.firstRunComplete = true
                            ServiceAutoStart.startIfAllowed(this@MainActivity, prefs)
                            navController.navigate("main") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                        OnboardingScreen(
                            navController = navController,
                            prefs = prefs,
                            onFinished = onFinished,
                        )
                    }
                    composable("main") {
                        MainScreen(navController = navController, prefs = prefs)
                    }
                    composable("settings") {
                        SettingsScreen(navController = navController, prefs = prefs)
                    }
                    composable("debug") {
                        DebugScreen(navController = navController, prefs = prefs)
                    }
                    composable("ride-history") {
                        RideHistoryScreen(navController = navController)
                    }
                    composable("settings/lights") {
                        SettingsLights(navController = navController, prefs = prefs)
                    }
                    composable("settings/radar") {
                        SettingsRadar(navController = navController, prefs = prefs)
                    }
                    composable("settings/alert-sounds") {
                        SettingsAlertSounds(navController = navController, prefs = prefs)
                    }
                    composable("settings/sound-demo") {
                        SoundDemoScreen(
                            prefs = prefs,
                            mark = R.string.alert_sounds_title,
                            onDone = { navController.popBackStack() },
                            doneLabel = R.string.common_done,
                        )
                    }
                    composable("settings/alert-volume") {
                        AlertVolumeScreen(
                            prefs = prefs,
                            mark = R.string.alert_sounds_title,
                            onDone = { navController.popBackStack() },
                            doneLabel = R.string.common_done,
                        )
                    }
                    composable("settings/radar-device") {
                        SettingsRadarDevice(navController = navController, prefs = prefs)
                    }
                    composable("settings/dashcam") {
                        SettingsDashcam(navController = navController, prefs = prefs)
                    }
                    composable("settings/ebike") {
                        SettingsEBike(navController = navController, prefs = prefs)
                    }
                    composable("settings/ha") {
                        SettingsHa(navController = navController, prefs = prefs)
                    }
                    composable("settings/radar-access") {
                        val store = remember {
                            PrefsRadarGrantStore(
                                getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, MODE_PRIVATE),
                            )
                        }
                        SettingsRadarAccessRoute(
                            store = store,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("settings/permissions") {
                        SettingsPermissions(navController = navController, prefs = prefs)
                    }
                    composable("settings/experimental") {
                        SettingsExperimental(navController = navController, prefs = prefs)
                    }
                    composable("settings/about") {
                        SettingsAbout(navController = navController)
                    }
                    composable("settings/licenses") {
                        SettingsLicenses(navController = navController)
                    }
                    composable("settings/privacy") {
                        SettingsPrivacy(navController = navController)
                    }
                    composable("settings/safety") {
                        // The SAME screen, button and all. Re-reading it from
                        // About should not be a trimmed variant of what the
                        // rider saw at first launch. Here the button only
                        // closes the screen: the flag is already set, and
                        // setting it again would be a no-op.
                        SafetyNoticeScreen(onAcknowledge = { navController.popBackStack() })
                    }
                    composable(
                        route = "dashcam-picker?fromOnboarding={fromOnboarding}",
                        arguments = listOf(
                            navArgument("fromOnboarding") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { entry ->
                        val fromOnboarding = entry.arguments?.getBoolean("fromOnboarding") ?: false
                        DashcamPickerSheet(
                            navController = navController,
                            prefs = prefs,
                            fromOnboarding = fromOnboarding,
                        )
                    }
                }
            }
        }
    }
}
