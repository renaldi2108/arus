package io.github.renaldi2108.arus.testing

import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class Loaded(
    val items: List<String> = emptyList(),
    val loading: Boolean = true,
)

private data class Toast(
    val message: String,
) : UiEffect

private class LoaderController : ArusController<Loaded, Toast>(Loaded()) {
    fun load() {
        setState { copy(items = listOf("a", "b"), loading = false) }
        emit(Toast("loaded"))
        emit(Toast("twice"))
    }

    fun sayNothing() = Unit
}

/**
 * The helpers in `arus-testing` are what every consumer's controller test is
 * built on, so a bug here is a bug in everybody's suite.
 */
class ControllerTestingTest {
    @Test
    fun runControllerTestInstallsAndRestoresTheMainDispatcher() =
        runTest {
            val before = Dispatchers.Main

            runControllerTest {
                // Inside, Main is the test dispatcher, so a controller that
                // launches on viewModelScope runs eagerly rather than hanging.
                LoaderController().load()
            }

            assertEquals(before, Dispatchers.Main)
        }

    @Test
    fun theMainDispatcherIsRestoredEvenWhenTheBlockThrows() =
        runTest {
            assertFailsWith<IllegalStateException> {
                runControllerTest { error("blow up") }
            }

            // Not leaking a test dispatcher into the next test is the whole
            // point of the `finally`.
            LoaderController().load()
        }

    @Test
    fun awaitEffectsCollectsExactlyTheRequestedCount() =
        runTest {
            runControllerTest {
                val controller = LoaderController()

                val effects = controller.awaitEffects(count = 2) { controller.load() }

                assertEquals(listOf(Toast("loaded"), Toast("twice")), effects)
            }
        }

    @Test
    fun awaitEffectsRejectsANonPositiveCount() =
        runTest {
            runControllerTest {
                val controller = LoaderController()

                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        controller.awaitEffects(count = 0) { }
                    }

                assertTrue(failure.message.orEmpty().contains("positive count"))
            }
        }

    @Test
    fun awaitEffectsTimesOutRatherThanHanging() =
        runTest {
            runControllerTest {
                val controller = LoaderController()

                // The failure mode that matters: a test that asserts on an
                // effect never emitted should fail in a second, not block CI.
                assertFailsWith<TimeoutCancellationException> {
                    controller.awaitEffects(timeoutMillis = 50) { controller.sayNothing() }
                }
            }
        }

    @Test
    fun awaitStateReturnsTheFirstMatchingValue() =
        runTest {
            runControllerTest {
                val controller = LoaderController()
                controller.load()

                val settled = controller.awaitState { !it.loading }

                assertEquals(listOf("a", "b"), settled.items)
            }
        }

    @Test
    fun awaitStateTimesOutWhenThePredicateNeverHolds() =
        runTest {
            runControllerTest {
                val controller = LoaderController()

                assertFailsWith<TimeoutCancellationException> {
                    controller.awaitState(timeoutMillis = 50) { it.items.size > 99 }
                }
            }
        }
}
