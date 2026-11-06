// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The status clock ticks readers even on an unchanged clock, never repeats the
 * previous value, follows a clock set back, and reads on resume before waiting.
 */
@RunWith(AndroidJUnit4::class)
class StatusClockTest {

    @get:Rule val composeRule = createComposeRule()

    private class FakeOwner(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun everyTickRecomposesEvenWhenTheClockReadsTheSame() {
        var reads = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val t = rememberStatusClock(now = { 42L })
            SideEffect { reads += 1 }
            Text("$t")
        }
        composeRule.mainClock.advanceTimeByFrame()
        // The first tick read the same 42 as the start, so it is set 1 ms past.
        composeRule.onNodeWithText("43").assertExists()
        val before = reads
        repeat(3) {
            composeRule.mainClock.advanceTimeBy(5_000L)
            composeRule.waitForIdle()
        }
        assertTrue("reads $before -> $reads", reads - before >= 3)
    }

    @Test
    fun aReadKeyedOnTheTickRerunsEvenWhenTheClockReadsTheSame() {
        // Screens key re-reads on the tick's value, so a tick that reads the
        // previous value must still change it.
        val reads = mutableSetOf<Any>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val t = rememberStatusClock(now = { 42L })
            val read = remember(t) { Any() }
            SideEffect { reads += read }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val before = reads.size
        repeat(3) {
            composeRule.mainClock.advanceTimeBy(5_000L)
            composeRule.waitForIdle()
        }
        assertTrue("reads $before -> ${reads.size}", reads.size - before >= 3)
    }

    @Test
    fun aClockSetBackIsFollowed() {
        var clock = 100_000L
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { Text("${rememberStatusClock(now = { clock })}") }
        composeRule.mainClock.advanceTimeByFrame()
        clock = 40_000L
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("40000").assertExists()
    }

    @Test
    fun aResumedScreenReadsTheClockBeforeItsFirstWait() {
        var clock = 100L
        val owner = FakeOwner(Lifecycle.State.CREATED)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text("${rememberStatusClock(now = { clock })}")
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("100").assertExists()
        clock = 200L
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("200").assertExists()
    }
}
