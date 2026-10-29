package io.github.renaldi2108.arus.core

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class CounterState(
    val count: Int = 0,
    val label: String = "",
)

private sealed interface CounterEffect : UiEffect {
    data class Notify(
        val value: Int,
    ) : CounterEffect
}

/** Exposes the protected API so tests can drive the controller directly. */
private class TestController(
    initial: CounterState = CounterState(),
    capacity: Int = DEFAULT_EFFECT_CAPACITY,
) : ArusController<CounterState, CounterEffect>(initial, capacity) {
    var lastError: Throwable? = null

    fun increment() = setState { copy(count = count + 1) }

    fun setLabel(value: String) = setState { copy(label = value) }

    fun send(effect: CounterEffect) = emit(effect)

    fun run(block: suspend () -> Unit) = launchCatching { block() }

    fun runWithHandler(
        handler: (Throwable) -> Unit,
        block: suspend () -> Unit,
    ) = launchCatching(onFailure = handler) { block() }

    override fun onError(error: Throwable) {
        lastError = error
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArusControllerTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exposesInitialStateBeforeAnyUpdate() {
        val controller = TestController(CounterState(count = 7, label = "start"))

        assertEquals(CounterState(count = 7, label = "start"), controller.state.value)
    }

    @Test
    fun setStateAppliesReducerToCurrentValue() {
        val controller = TestController()

        controller.increment()
        controller.increment()
        controller.setLabel("done")

        assertEquals(CounterState(count = 2, label = "done"), controller.state.value)
    }

    @Test
    fun setStateIsAtomicUnderConcurrentUpdates() =
        runTest {
            val controller = TestController()
            val updatesPerWorker = 500
            val workers = 8

            List(workers) {
                async(Dispatchers.Default) {
                    repeat(updatesPerWorker) { controller.increment() }
                }
            }.awaitAll()

            // A non-atomic read-modify-write would lose updates here.
            assertEquals(workers * updatesPerWorker, controller.state.value.count)
        }

    @Test
    fun stateEmitsEveryDistinctUpdateToCollectors() =
        runTest {
            val controller = TestController()

            controller.state.test {
                assertEquals(0, awaitItem().count)
                controller.increment()
                assertEquals(1, awaitItem().count)
                controller.increment()
                assertEquals(2, awaitItem().count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun effectsReachAnActiveCollector() =
        runTest {
            val controller = TestController()

            controller.effects.test {
                controller.send(CounterEffect.Notify(1))
                assertEquals(CounterEffect.Notify(1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun effectsEmittedWithoutCollectorAreRetainedAndReplayedOnce() =
        runTest {
            val controller = TestController()

            // Nothing is collecting yet — this is the backgrounded-app case.
            controller.send(CounterEffect.Notify(1))
            controller.send(CounterEffect.Notify(2))

            controller.effects.test {
                assertEquals(CounterEffect.Notify(1), awaitItem())
                assertEquals(CounterEffect.Notify(2), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun effectBufferDropsOldestWhenCapacityIsExceeded() =
        runTest {
            val capacity = 4
            val controller = TestController(capacity = capacity)

            repeat(capacity + 2) { controller.send(CounterEffect.Notify(it)) }

            controller.effects.test {
                // The two oldest (0 and 1) were dropped to make room.
                val received = List(capacity) { (awaitItem() as CounterEffect.Notify).value }
                assertEquals(listOf(2, 3, 4, 5), received)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun launchCatchingRoutesFailuresToOnError() =
        runTest {
            val controller = TestController()
            val boom = IllegalStateException("boom")

            controller.run { throw boom }

            assertEquals(boom, controller.lastError)
        }

    @Test
    fun launchCatchingUsesExplicitHandlerWhenGiven() =
        runTest {
            val controller = TestController()
            var handled: Throwable? = null
            val boom = IllegalStateException("boom")

            controller.runWithHandler(handler = { handled = it }) { throw boom }

            assertEquals(boom, handled)
            assertNull(controller.lastError, "default handler must not also run")
        }

    @Test
    fun launchCatchingDoesNotReportSuccessfulWork() =
        runTest {
            val controller = TestController()

            controller.run { controller.increment() }

            assertEquals(1, controller.state.value.count)
            assertNull(controller.lastError)
        }

    @Test
    fun launchCatchingPropagatesCancellationInsteadOfReportingIt() =
        runTest {
            val controller = TestController()

            val job = controller.run { kotlinx.coroutines.awaitCancellation() }
            job.cancel()

            assertTrue(job.isCancelled)
            assertNull(controller.lastError, "cancellation is not a failure")
        }

    @Test
    fun arsDelegateReadsAndWritesThroughItsFlow() =
        runTest {
            val query = Ars("")

            assertEquals("", query.value)

            query.asFlow.test {
                assertEquals("", awaitItem())
                query.value = "arus"
                assertEquals("arus", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals("arus", query.value)
        }

    @Test
    fun derivedRecomputesOnlyWhenTheMappedValueChanges() =
        runTest {
            val controller = TestController()
            val counts = controller.state.derived(backgroundScope) { it.count }

            counts.test {
                assertEquals(0, awaitItem())
                // Changing an unrelated field must not produce a new derived value.
                controller.setLabel("ignored")
                controller.increment()
                assertEquals(1, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
