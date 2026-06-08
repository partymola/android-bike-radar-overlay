// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.delay

/**
 * Three jittered attempts at a light-mode GATT write, shared by the camera and
 * radar light paths. [write] returns true once the device ACKs the write; note
 * for the radar that confirms receipt, not that the light element actually
 * changed (the radar can't be read back).
 */
internal suspend fun applyWithRetry(write: suspend () -> Boolean): Boolean {
    if (write()) return true
    delay(500 + (100 * (Math.random() * 2 - 1)).toLong())
    if (write()) return true
    delay(1500 + (300 * (Math.random() * 2 - 1)).toLong())
    return write()
}
