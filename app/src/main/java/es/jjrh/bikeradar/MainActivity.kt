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
import es.jjrh.bikeradar.ui.DebugScreenNext
import es.jjrh.bikeradar.ui.DevModeState
import es.jjrh.bikeradar.ui.MainScreenNext
import es.jjrh.bikeradar.ui.NextTheme
import es.jjrh.bikeradar.ui.OnboardingScreenNext
import es.jjrh.bikeradar.ui.SettingsAboutNext
import es.jjrh.bikeradar.ui.SettingsDashcamNext
import es.jjrh.bikeradar.ui.SettingsExperimentalNext
import es.jjrh.bikeradar.ui.SettingsHaNext
import es.jjrh.bikeradar.ui.SettingsLicensesNext
import es.jjrh.bikeradar.ui.SettingsPermissionsNext
import es.jjrh.bikeradar.ui.SettingsPrivacyNext
import es.jjrh.bikeradar.ui.SettingsRadarNext
import es.jjrh.bikeradar.ui.SettingsScreenNext

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
            NextTheme {
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
                        OnboardingScreenNext(
                            navController = navController,
                            prefs = prefs,
                            onFinished = onFinished,
                        )
                    }
                    composable("main") {
                        MainScreenNext(navController = navController, prefs = prefs)
                    }
                    composable("settings") {
                        SettingsScreenNext(navController = navController, prefs = prefs)
                    }
                    composable("debug") {
                        DebugScreenNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/radar") {
                        SettingsRadarNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/dashcam") {
                        SettingsDashcamNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/ha") {
                        SettingsHaNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/permissions") {
                        SettingsPermissionsNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/experimental") {
                        SettingsExperimentalNext(navController = navController, prefs = prefs)
                    }
                    composable("settings/about") {
                        SettingsAboutNext(navController = navController)
                    }
                    composable("settings/licenses") {
                        SettingsLicensesNext(navController = navController)
                    }
                    composable("settings/privacy") {
                        SettingsPrivacyNext(navController = navController)
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
