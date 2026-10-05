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
 * Which receivers, services and providers another app can reach, read from the
 * merged manifest. The developer receivers and services (replay, synthetic
 * scenarios, the debug overlay, screenshots, the radar-light write) act on the
 * rider's radar and screen, so any app able to send them an intent could drive
 * them. Allowed by name rather than denied by name, so a new exported component
 * fails here until someone decides it should be reachable.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestExportsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
    )

    private fun exported(components: Array<out ComponentInfo>?): Set<String> = components.orEmpty().filter { it.exported }.mapTo(sortedSetOf()) { it.name }

    @Test
    fun onlyTheBootAndProfileReceiversAreExported() {
        // Boot and package-replaced broadcasts come from the system, which
        // reaches only an exported receiver. The profile installer is merged in
        // from androidx and guarded by the DUMP permission, which only the
        // shell and system hold.
        assertEquals(
            setOf("androidx.profileinstaller.ProfileInstallReceiver", "es.jjrh.bikeradar.BootReceiver"),
            exported(info.receivers),
        )
    }

    @Test
    fun onlyTheRadarContractServiceIsAnExportedService() {
        assertEquals(setOf("es.jjrh.bikeradar.ipc.RadarIpcService"), exported(info.services))
    }

    @Test
    fun noProviderIsExported() {
        assertEquals(emptySet<String>(), exported(info.providers))
    }

    @Test
    fun theDeveloperComponentsAreAllDeclared() {
        // Guards the three checks above against passing on a manifest that
        // lost these components rather than kept them unexported.
        val declared = (info.receivers.orEmpty().toList() + info.services.orEmpty()).map { it.name }
        val developer = listOf(
            "es.jjrh.bikeradar.RemoteControlReceiver",
            "es.jjrh.bikeradar.InternalControlReceiver",
            "es.jjrh.bikeradar.DebugOverlayService",
            "es.jjrh.bikeradar.ReplayService",
            "es.jjrh.bikeradar.SyntheticScenarioService",
            "es.jjrh.bikeradar.ScreenshotCaptureService",
        )
        assertEquals(emptyList<String>(), developer - declared.toSet())
    }
}
