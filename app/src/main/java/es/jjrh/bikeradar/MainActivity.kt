// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ui.DashcamPickerSheet
import es.jjrh.bikeradar.ui.DebugScreen
import es.jjrh.bikeradar.ui.DevModeState
import es.jjrh.bikeradar.ui.MainScreen
import es.jjrh.bikeradar.ui.UiTheme
import es.jjrh.bikeradar.ui.OnboardingScreen
import es.jjrh.bikeradar.ui.SettingsAbout
import es.jjrh.bikeradar.ui.SettingsCameraLight
import es.jjrh.bikeradar.ui.SettingsDashcam
import es.jjrh.bikeradar.ui.SettingsExperimental
import es.jjrh.bikeradar.ui.SettingsHa
import es.jjrh.bikeradar.ui.SettingsLicenses
import es.jjrh.bikeradar.ui.SettingsPermissions
import es.jjrh.bikeradar.ui.SettingsPrivacy
import es.jjrh.bikeradar.ui.SettingsRadar
import es.jjrh.bikeradar.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        DevModeState.loadFrom(prefs)
        HaCredentials(this).seedFromBuildConfigIfEmpty()

        // Re-launch the service after process kill (e.g. adb install -r) if
        // the user is past onboarding and perms are still in place. Boot is
        // covered by BootReceiver; this covers app relaunch.
        if (prefs.firstRunComplete &&
            prefs.serviceEnabled &&
            Permissions.hasRequiredForService(this)
        ) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BikeRadarService::class.java),
            )
        }

        setContent {
            UiTheme {
                val navController = rememberNavController()
                val startDest = if (prefs.firstRunComplete) "main" else "onboarding"
                NavHost(navController = navController, startDestination = startDest) {
                    composable("onboarding") {
                        val onFinished: () -> Unit = {
                            prefs.firstRunComplete = true
                            if (Permissions.hasRequiredForService(this@MainActivity)) {
                                ContextCompat.startForegroundService(
                                    this@MainActivity,
                                    Intent(this@MainActivity, BikeRadarService::class.java),
                                )
                            }
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
                    composable("settings/camera-light") {
                        SettingsCameraLight(navController = navController, prefs = prefs)
                    }
                    composable("settings/radar") {
                        SettingsRadar(navController = navController, prefs = prefs)
                    }
                    composable("settings/dashcam") {
                        SettingsDashcam(navController = navController, prefs = prefs)
                    }
                    composable("settings/ha") {
                        SettingsHa(navController = navController, prefs = prefs)
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
