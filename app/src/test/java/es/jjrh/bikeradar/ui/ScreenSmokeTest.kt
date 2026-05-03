// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose render smoke tests. Each top-level screen is composed once and
 * the test passes if synchronous composition does not throw. Catches
 * synchronous crashes only: NPE inside `remember { }`, missing Material
 * theme colours, null Prefs reads, layout-time crashes.
 *
 * Does NOT catch: flow collection failures, LaunchedEffect bodies, or
 * anything that runs after the first composition (coroutines are queued,
 * not executed immediately, by the v2 test rule). Does NOT exercise
 * interaction; that needs androidTest + an emulator.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenSmokeTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
    }

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @Test
    fun mainScreenComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            MainScreen(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun onboardingScreenComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            OnboardingScreen(navController = nav, prefs = prefs, onFinished = {})
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsScreenComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsScreen(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun debugScreenComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            DebugScreen(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsRadarComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsRadar(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsHaComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsHa(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsDashcamComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsDashcam(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsPermissionsComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsPermissions(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsExperimentalComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsExperimental(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsAboutComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsAbout(navController = nav)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsLicensesComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsLicenses(navController = nav)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsPrivacyComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsPrivacy(navController = nav)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun dashcamPickerSheetComposes() {
        composeRule.setContent {
            val nav = rememberNavController()
            DashcamPickerSheet(navController = nav, prefs = prefs, fromOnboarding = false)
        }
        composeRule.waitForIdle()
    }
}
