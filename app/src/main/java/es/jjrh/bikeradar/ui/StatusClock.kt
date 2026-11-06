// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Wall-clock time for a status screen, re-read every [periodMs] while it is
 * resumed. It reads on resume before the first wait, so a screen back from
 * standby never compares two old values. Screens use the tick to re-read state
 * nothing else invalidates, most of it keyed on the value, so a tick never
 * repeats the previous one: a tick that reads the previous value is set 1 ms
 * past it, which also recomposes the readers, and a clock set back is followed
 * (`StatusClockTest`).
 */
@Composable
internal fun rememberStatusClock(periodMs: Long = 5_000, now: () -> Long = System::currentTimeMillis): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(now()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t = now()
                nowMs = if (t == nowMs) t + 1 else t
                delay(periodMs)
            }
        }
    }
    return nowMs
}
