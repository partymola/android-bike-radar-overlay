// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Maps an [AttentionItem] to the string resource that renders it, so the
 * post-ride notification and the home-screen attention card show identical
 * wording without either owning the copy. Kept out of the deriver so the
 * derivation stays Android-free; kept out of both surfaces so they can't
 * drift apart.
 *
 * A [Line] is either a [Line.Simple] (one string, optional numeric arg) or a
 * [Line.Plural] (count-agreeing). The caller resolves it with the API it has
 * - `stringResource` / `pluralStringResource` in Compose, `getString` /
 * `getQuantityString` on a notification's `Resources`.
 */
object AttentionCopy {

    sealed interface Line {
        data class Simple(@StringRes val res: Int, val arg: Int?) : Line
        data class Plural(@PluralsRes val res: Int, val count: Int) : Line
    }

    fun lineFor(item: AttentionItem): Line = when (item.kind) {
        AttentionKind.RADAR_BATTERY -> Line.Simple(R.string.attention_radar_battery, item.value)
        AttentionKind.DASHCAM_BATTERY -> Line.Simple(R.string.attention_dashcam_battery, item.value)
        AttentionKind.EBIKE_BATTERY -> Line.Simple(R.string.attention_ebike_battery, item.value)
        AttentionKind.AUDIO_FAILURES -> Line.Plural(R.plurals.attention_audio_failures, item.value ?: 0)
        AttentionKind.UNCLEAN_RESTART -> Line.Simple(R.string.attention_unclean_restart, null)
    }
}
