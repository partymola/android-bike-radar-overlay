// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Wall-clock time for a status screen, re-read every [periodMs] while it is
 * resumed. It reads on resume before the first wait, so a screen back from
 * standby never compares two old values; and every tick recomposes its readers
 * even when the clock reads the same, because screens use the tick to re-read
 * state nothing else invalidates (`StatusClockTest`).
 */
@Composable
internal fun rememberStatusClock(periodMs: Long = 5_000, now: () -> Long = System::currentTimeMillis): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableStateOf(now(), neverEqualPolicy()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMs = now()
                delay(periodMs)
            }
        }
    }
    return nowMs
}
