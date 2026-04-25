// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ui.AboutScreen
import es.jjrh.bikeradar.ui.BikeRadarTheme
import es.jjrh.bikeradar.ui.DebugScreen
import es.jjrh.bikeradar.ui.DevModeState
import es.jjrh.bikeradar.ui.MainScreen
import es.jjrh.bikeradar.ui.MainScreenNext
import es.jjrh.bikeradar.ui.OnboardingScreen
import es.jjrh.bikeradar.ui.OnboardingScreenNext
import es.jjrh.bikeradar.ui.SettingsScreen
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
            BikeRadarTheme {
                val navController = rememberNavController()
                val startDest = if (prefs.firstRunComplete) "main" else "onboarding"
                // The next-UX toggles in Debug write to Prefs; collecting
                // the flow here lets each route swap V1 <-> next live, so
                // returning to a screen after flipping the toggle picks up
                // the new variant without an app restart.
                val snap by prefs.flow.collectAsState(initial = prefs.snapshot())
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
                        if (snap.nextUxOnboarding) {
                            OnboardingScreenNext(prefs = prefs, onFinished = onFinished)
                        } else {
                            OnboardingScreen(prefs = prefs, onFinished = onFinished)
                        }
                    }
                    composable("main") {
                        if (snap.nextUxMain) {
                            MainScreenNext(navController = navController, prefs = prefs)
                        } else {
                            MainScreen(navController = navController, prefs = prefs)
                        }
                    }
                    composable("settings") {
                        if (snap.nextUxSettings) {
                            SettingsScreenNext(navController = navController, prefs = prefs)
                        } else {
                            SettingsScreen(navController = navController, prefs = prefs)
                        }
                    }
                    composable("debug") {
                        DebugScreen(navController = navController, prefs = prefs)
                    }
                    composable("about") {
                        AboutScreen(navController = navController)
                    }
                }
            }
        }
    }
}
