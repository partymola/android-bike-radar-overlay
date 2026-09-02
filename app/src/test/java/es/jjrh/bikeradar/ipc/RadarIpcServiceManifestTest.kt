// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exported surface, as another app actually finds it.
 *
 * Every fact here lives in two files that no compiler relates: the action a
 * consumer sends against the one the manifest answers, and the permission the
 * service demands against the one this app declares. A consumer copies both as
 * strings rather than importing them.
 */
@RunWith(RobolectricTestRunner::class)
class RadarIpcServiceManifestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDeclaredActionIsTheOneAConsumerBindsTo() {
        val resolved = context.packageManager.queryIntentServices(Intent(RadarContract.ACTION), 0)
        assertEquals(
            "exactly one service must answer ${RadarContract.ACTION}",
            1,
            resolved.size,
        )
        assertEquals(RadarIpcService::class.java.name, resolved.single().serviceInfo.name)
    }

    @Test
    fun theServiceIsExportedAndPermissionGuarded() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, RadarIpcService::class.java),
            0,
        )
        assertTrue("an unexported service cannot be reached by any consumer", info.exported)
        assertEquals(
            "the coarse filter must be the permission this app declares",
            RadarContract.PERMISSION,
            info.permission,
        )
    }

    @Test
    fun thePermissionThisAppDeclaresIsTheOneTheServiceDemands() {
        // A service guarded by a permission nothing declares is a service no
        // app can bind to at all: the platform refuses the bind rather than
        // reporting a missing declaration.
        val declared = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).permissions.orEmpty()

        val radar = declared.firstOrNull { it.name == RadarContract.PERMISSION }
        assertNotNull("the service demands a permission this app never declares", radar)
        assertEquals(
            "signature level would need every consumer's certificate named here " +
                "before that app exists, and dangerous would put a system prompt " +
                "in front of an app that still gets nothing without the rider's " +
                "own grant",
            PermissionInfo.PROTECTION_NORMAL,
            radar!!.getProtection(),
        )
    }

    @Test
    fun thePermissionExplainsItselfToTheRider() {
        // It appears in the system's app-permissions list, so it needs words a
        // rider can read rather than a bare identifier.
        val radar = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).permissions.orEmpty().single { it.name == RadarContract.PERMISSION }

        assertTrue("no label", radar.labelRes != 0)
        assertTrue("no description", radar.descriptionRes != 0)
    }
}
