package io.github.renaldi2108.arus.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class NoisyController : ArusController<Int, TestEffect>(0) {
    var handled: Throwable? = null

    fun shout(n: Int) = repeat(n) { emit(TestEffect(it)) }

    fun fail(error: Throwable) = onError(error)

    fun clear() = onCleared()
}

private data class TestEffect(
    val index: Int,
) : UiEffect

private class ReportingController : ArusController<Int, TestEffect>(0) {
    val seen = mutableListOf<Throwable>()

    override fun onError(error: Throwable) {
        seen += error
    }

    fun boom() = launchCatching { throw IllegalStateException("boom") }
}

/**
 * Covers the paths that only run with [Arus.debugLogging] on.
 *
 * They are one-line logging branches, but leaving them unexecuted means a typo
 * inside one would only ever surface in a debug build of somebody's app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugLoggingTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Arus.debugLogging = false
        Dispatchers.resetMain()
    }

    @Test
    fun theDefaultIsOff() {
        assertFalse(Arus.debugLogging)
    }

    @Test
    fun overflowingTheEffectBufferWithLoggingOnDoesNotThrow() {
        Arus.debugLogging = true
        val controller = NoisyController()

        // Far more than the buffer holds, with nobody collecting.
        controller.shout(200)

        assertTrue(Arus.debugLogging)
    }

    @Test
    fun theDefaultOnErrorSwallowsWithLoggingOn() {
        Arus.debugLogging = true
        val controller = NoisyController()

        controller.fail(IllegalStateException("ignored"))

        assertEquals(null, controller.handled)
    }

    @Test
    fun anOverriddenOnErrorReceivesTheFailure() =
        runTest {
            val controller = ReportingController()

            controller.boom()

            assertEquals("boom", controller.seen.single().message)
        }

    @Test
    fun clearingClosesTheEffectChannel() =
        runTest {
            val controller = NoisyController()
            controller.shout(1)

            controller.clear()

            // Closed, not cancelled: already-buffered effects are still
            // delivered, and the stream then ends instead of hanging.
            val drained = mutableListOf<TestEffect>()
            controller.effects.collect { drained += it }
            assertEquals(listOf(TestEffect(0)), drained)
        }

    @Test
    fun emittingAfterTheControllerIsClearedIsReportedNotThrown() {
        Arus.debugLogging = true
        val controller = NoisyController()
        controller.clear()

        // The buffer drops the oldest rather than failing, so the only way
        // `trySend` reports a failure is a closed channel — which is exactly
        // what a cleared controller has. It must log, not throw.
        controller.shout(3)

        assertTrue(Arus.debugLogging)
    }
}
