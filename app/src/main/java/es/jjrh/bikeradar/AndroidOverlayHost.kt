// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/**
 * Production [OverlayHost] backed by the Android [WindowManager]. Owns the
 * window-level state previously held as `@Volatile` fields on
 * [BikeRadarService]: a reference to the attached view + a function that
 * builds the per-attach [WindowManager.LayoutParams]. Re-applies the params
 * on rotation via [onConfigurationChanged].
 */
internal class AndroidOverlayHost(
    private val context: Context,
    private val buildParams: (WindowManager) -> WindowManager.LayoutParams,
) : OverlayHost {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile private var attachedView: RadarOverlayView? = null

    override fun createView(): RadarOverlayView = RadarOverlayView(context)

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun attach(view: RadarOverlayView): Throwable? = try {
        wm.addView(view, buildParams(wm))
        attachedView = view
        null
    } catch (t: Throwable) {
        Log.w(TAG, "overlay addView failed (permission TOCTOU?): $t")
        t
    }

    override fun detach(view: RadarOverlayView) {
        try {
            wm.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed: $t")
        } finally {
            // Only clear the tracked reference if it still pointed to this
            // view; a fast detach->attach cycle could otherwise null out the
            // newer attached view.
            if (attachedView === view) attachedView = null
        }
    }

    override fun onConfigurationChanged() {
        val view = attachedView ?: return
        try {
            wm.updateViewLayout(view, buildParams(wm))
        } catch (_: Throwable) {
            // Layout-update can throw if the view was detached between the
            // null check and the call; safe to ignore (the next attach will
            // re-issue layout params).
        }
    }

    private companion object {
        private const val TAG = "BikeRadar.OverlayHost"
    }
}

/**
 * Production [PhoneBatterySource] backed by the sticky
 * `ACTION_BATTERY_CHANGED` broadcast. Each call re-reads the broadcast (the
 * OS keeps the most-recent intent cached) so the snapshot is always fresh.
 * Returns null if the OS has no cached broadcast yet.
 */
internal class AndroidPhoneBatterySource(
    private val context: Context,
) : PhoneBatterySource {
    override fun readSnapshot(): PhoneBatteryReading? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return PhoneBatteryReading(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
            tempDc = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
    }
}
