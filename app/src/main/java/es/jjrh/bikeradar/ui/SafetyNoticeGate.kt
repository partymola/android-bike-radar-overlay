// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

/**
 * Where a rider belongs on launch, given what they have already done.
 *
 * Pure so the ordering can be pinned: the notice has to come before
 * onboarding for a new install AND before the home screen for an existing
 * one, and the second of those is the case no manual test on a set-up phone
 * would ever reach.
 */
fun startDestination(safetyNoticeAcknowledged: Boolean, firstRunComplete: Boolean): String = when {
    !safetyNoticeAcknowledged -> "safety-notice"
    firstRunComplete -> "main"
    else -> "onboarding"
}
