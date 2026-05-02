// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleGateTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { kotlinx.coroutines.Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    private class TestOwner : LifecycleOwner {
        // enforceMainThread = false avoids Looper.getMainLooper() stub crash in JVM unit tests.
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    }

    @Test
    fun collectPausesWhenLifecycleStopped() = runTest(testDispatcher) {
        val owner = TestOwner().apply {
            moveTo(Lifecycle.Event.ON_CREATE)
            moveTo(Lifecycle.Event.ON_START)
            moveTo(Lifecycle.Event.ON_RESUME)
        }
        val flow = MutableStateFlow(0)
        val collected = mutableListOf<Int>()

        val job = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { collected.add(it) }
            }
        }
        testScheduler.advanceUntilIdle() // start collecting (emits current value 0)

        flow.value = 1
        testScheduler.advanceUntilIdle()
        assertTrue("expected 1 while RESUMED; collected=$collected", 1 in collected)

        // Move below STARTED — collection must pause
        owner.moveTo(Lifecycle.Event.ON_STOP)
        testScheduler.advanceUntilIdle()
        val sizeAtStop = collected.size

        flow.value = 2
        testScheduler.advanceUntilIdle()
        assertEquals(
            "collection must pause below STARTED; collected=$collected",
            sizeAtStop,
            collected.size,
        )

        // Back to STARTED — collection must resume (StateFlow re-emits current value 2)
        owner.moveTo(Lifecycle.Event.ON_START)
        testScheduler.advanceUntilIdle()
        flow.value = 3
        testScheduler.advanceUntilIdle()
        assertTrue("expected 3 after ON_START; collected=$collected", 3 in collected)

        job.cancel()
    }

    @Test
    fun collectContinuesAtResumed() = runTest(testDispatcher) {
        // STARTED is the gate threshold — advancing further to RESUMED must keep collection running.
        val owner = TestOwner().apply {
            moveTo(Lifecycle.Event.ON_CREATE)
            moveTo(Lifecycle.Event.ON_START)
        }
        val flow = MutableStateFlow(0)
        val collected = mutableListOf<Int>()

        val job = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { collected.add(it) }
            }
        }
        testScheduler.advanceUntilIdle()

        flow.value = 1
        testScheduler.advanceUntilIdle()
        assertTrue("expected collection at STARTED; collected=$collected", 1 in collected)

        owner.moveTo(Lifecycle.Event.ON_RESUME)
        testScheduler.advanceUntilIdle()
        flow.value = 2
        testScheduler.advanceUntilIdle()
        assertTrue(
            "expected collection continues at RESUMED; collected=$collected",
            2 in collected,
        )

        job.cancel()
    }
}
