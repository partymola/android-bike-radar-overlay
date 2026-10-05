// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which components another app can reach, read from the merged manifest. The
 * developer receivers and services (replay, synthetic scenarios, the debug
 * overlay, screenshots, the radar-light write) act on the rider's radar and
 * screen, so any app able to send them an intent could drive them. Allowed by
 * name rather than denied by name, so a new exported receiver, service,
 * provider or activity of the app's own fails here until someone decides it
 * should be reachable. An activity merged in from a library is not checked.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestExportsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
    )

    private fun exported(components: Array<out ComponentInfo>?): Set<String> = components.orEmpty().filter { it.exported }.mapTo(sortedSetOf()) { it.name }

    @Test
    fun onlyTheLauncherAndTheConsentScreenAreExportedActivities() {
        // Only the app's own classes: this manifest is the debug-test one, which
        // merges in exported tooling and test activities the release build
        // does not carry.
        assertEquals(
            setOf("es.jjrh.bikeradar.MainActivity", "es.jjrh.bikeradar.access.RadarConsentActivity"),
            exported(info.activities).filterTo(sortedSetOf()) { it.startsWith("es.jjrh.bikeradar.") },
        )
    }

    @Test
    fun onlyTheBootAndProfileReceiversAreExported() {
        // Boot and package-replaced broadcasts come from the system, which
        // reaches only an exported receiver.
        assertEquals(
            setOf("androidx.profileinstaller.ProfileInstallReceiver", "es.jjrh.bikeradar.BootReceiver"),
            exported(info.receivers),
        )
    }

    @Test
    fun theProfileReceiverNeedsAPermissionOnlyTheShellHolds() {
        val receiver = info.receivers.orEmpty().single { it.name == "androidx.profileinstaller.ProfileInstallReceiver" }
        assertEquals("android.permission.DUMP", receiver.permission)
    }

    @Test
    fun onlyTheRadarContractServiceIsAnExportedService() {
        assertEquals(setOf("es.jjrh.bikeradar.ipc.RadarIpcService"), exported(info.services))
        val service = info.services.orEmpty().single { it.name == "es.jjrh.bikeradar.ipc.RadarIpcService" }
        assertEquals("es.jjrh.bikeradar.permission.RADAR", service.permission)
    }

    @Test
    fun noProviderIsExported() {
        assertEquals(emptySet<String>(), exported(info.providers))
    }

    @Test
    fun theCheckedComponentsAreAllDeclared() {
        // Guards the checks above against passing on a manifest that lost
        // these components rather than kept them unexported.
        val declared = listOf<Array<out ComponentInfo>?>(info.activities, info.receivers, info.services, info.providers)
            .flatMap { it.orEmpty().toList() }
            .map { it.name }
        val expected = listOf(
            "es.jjrh.bikeradar.MainActivity",
            "es.jjrh.bikeradar.RemoteControlReceiver",
            "es.jjrh.bikeradar.InternalControlReceiver",
            "es.jjrh.bikeradar.DebugOverlayService",
            "es.jjrh.bikeradar.ReplayService",
            "es.jjrh.bikeradar.SyntheticScenarioService",
            "es.jjrh.bikeradar.ScreenshotCaptureService",
            "androidx.core.content.FileProvider",
        )
        assertEquals(emptyList<String>(), expected - declared.toSet())
    }
}
