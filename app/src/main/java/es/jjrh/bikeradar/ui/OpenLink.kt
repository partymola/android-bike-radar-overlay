// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import es.jjrh.bikeradar.R

/**
 * Open a web address, or say so when nothing on the phone can.
 *
 * Every caller is a legal or source link the rider chose to follow, and a
 * phone with no browser is a real configuration rather than a broken one.
 * Left to a bare `startActivity` that throws
 * [ActivityNotFoundException] and takes the app down from a screen the rider
 * opened to read a licence.
 *
 * Deliberately no `resolveActivity` pre-check: on Android 11 and up that
 * answers null without a `<queries>` entry even though the launch itself
 * resolves, so the check would report every phone as browserless.
 */
fun openLink(ctx: Context, url: String) {
    try {
        // NEW_TASK because the caller's context is not always an Activity:
        // without it Android refuses the launch outright, which is a different
        // exception and one this would not have caught.
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, ctx.getString(R.string.link_no_browser), Toast.LENGTH_LONG).show()
    }
}
