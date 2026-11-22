// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RideHistoryRecord
import es.jjrh.bikeradar.RideHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local ride history - the plain list over [RideHistoryStore]. One row
 * per finished ride: when, how far, how much traffic, and how close it
 * got. Works fully offline; nothing here needs Home Assistant.
 */
@Composable
fun RideHistoryScreen(navController: NavController) {
    UiTheme {
        RideHistoryBody(navController)
    }
}

@Composable
private fun RideHistoryBody(navController: NavController) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf<List<RideHistoryRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) {
            RideHistoryStore({ ctx.getExternalFilesDir(null) }).readAll()
        }
    }
    RideHistoryContent(records = records, onBack = { navController.popBackStack() })
}

/** Stateless leaf so snapshot tests can render fixed records without a
 *  store or a NavController. */
@Composable
internal fun RideHistoryContent(records: List<RideHistoryRecord>, onBack: () -> Unit) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsHeader(stringResource(R.string.ride_history_title), onBack = onBack)
            if (records.isEmpty()) {
                RideHistoryEmpty()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(records, key = { "${it.startedAtMs}-${it.endedAtMs}" }) { rec ->
                        RideHistoryRow(rec)
                    }
                }
            }
        }
    }
}

@Composable
private fun RideHistoryEmpty() {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text(
            text = stringResource(R.string.ride_history_empty_title),
            color = br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.ride_history_empty_body),
            color = br.fgMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

/**
 * A ride row's stats segments, which [rideHistoryStatsTexts] joins with
 * bullets: the vehicles detected behind, then the approximate count that passed
 * with the close passes nested in it, then the tightest clearance. Without a
 * passed figure (an older ride, or a radar with no lateral channel) the close
 * passes stand alone, and a ride that did not count close passes shows none
 * (`RideHistoryStatsLineTest`).
 */
internal fun rideHistoryStatsSegments(res: Resources, locale: Locale, rec: RideHistoryRecord): List<String> {
    val detected = res.getQuantityString(R.plurals.ride_history_detected, rec.overtakes, rec.overtakes)
    val closeCounted = rec.closePassesCounted != false
    val passed = rec.vehiclesPassed
    val passSegment = when {
        passed != null && closeCounted -> res.getQuantityString(
            R.plurals.ride_history_passed_close,
            passed,
            passed,
            res.getQuantityString(R.plurals.ride_history_close_in_passed, rec.closePasses, rec.closePasses),
        )
        passed != null -> res.getQuantityString(R.plurals.ride_history_passed, passed, passed)
        closeCounted -> res.getQuantityString(R.plurals.ride_history_close_passes, rec.closePasses, rec.closePasses)
        else -> null
    }
    val tightest = rec.tightestPassClearanceM?.let {
        res.getString(R.string.ride_history_tightest, String.format(locale, "%.1f", it))
    }
    return listOfNotNull(detected, passSegment, tightest)
}

private const val NO_BREAK_SPACE = '\u00A0'

/** What the row lays out, one text per segment: each but the last ends in a
 *  bullet, held to it by a no-break space (`RideHistoryStatsLineTest`). */
internal fun rideHistoryStatsTexts(segments: List<String>): List<String> = segments.mapIndexed { i, segment ->
    if (i < segments.lastIndex) "$segment$NO_BREAK_SPACE•" else segment
}

@Composable
private fun RideHistoryRow(rec: RideHistoryRecord) {
    val br = LocalBrColors.current
    // Observable locale read so rows reformat on a runtime locale switch.
    // The configuration locale list is never empty; ROOT is a type-level fallback.
    val locale = LocalConfiguration.current.locales.get(0) ?: Locale.ROOT
    val dayFmt = remember(locale) { SimpleDateFormat("EEE d MMM", locale) }
    val timeFmt = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val day = dayFmt.format(Date(rec.startedAtMs))
    val timeRange = "${timeFmt.format(Date(rec.startedAtMs))}-${timeFmt.format(Date(rec.endedAtMs))}"

    // Resources after the configuration read above, as Compose's own string
    // lookups do it, so the line follows a locale change.
    val segments = rideHistoryStatsSegments(LocalContext.current.resources, locale, rec)

    BrCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = day,
                        color = br.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeRange,
                        color = br.fgDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // One Text per segment, so a line breaks between segments and
                // inside one only where it alone is wider than the row
                // (`RideHistorySnapshotTest.populatedSpanishLargeFont`).
                // TalkBack reads the segments as one line without the bullets
                // (`RideHistoryStatsRowA11yTest`).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = segments.joinToString(", ") },
                ) {
                    rideHistoryStatsTexts(segments).forEach { text ->
                        Text(
                            text = text,
                            color = if (rec.closePasses > 0) br.fgMuted else br.fgDim,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(
                    R.string.ride_history_km_value,
                    String.format(locale, "%.1f", rec.distanceKm),
                ),
                color = br.fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
    }
}
