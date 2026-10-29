package io.github.renaldi2108.arus.testing

import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/**
 * Points `Dispatchers.Main` at a test dispatcher for the duration of [block].
 *
 * Controllers launch work in `viewModelScope`, which dispatches to Main. Without
 * this a controller test fails with "Module with the Main dispatcher had failed
 * to initialize" — an error that says nothing about what is wrong.
 *
 * ```kotlin
 * @Test
 * fun loadsOnStart() = runControllerTest {
 *     val controller = ProfileController(FakeRepository(), FakeNavigator(HomeRoute))
 *     assertEquals("Ada", controller.state.value.name)
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun runControllerTest(block: suspend () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}

/**
 * Collects the next [count] effects, failing rather than hanging if they do not
 * arrive.
 *
 * ```kotlin
 * val effects = controller.awaitEffects(1) { controller.onRefresh() }
 * assertEquals(ArusEffect.Snackbar("Refreshed"), effects.single())
 * ```
 *
 * @param count how many effects to wait for.
 * @param timeoutMillis how long to wait before failing.
 * @param trigger the action expected to produce them.
 */
public suspend fun <E : UiEffect> ArusController<*, E>.awaitEffects(
    count: Int = 1,
    timeoutMillis: Long = 1_000,
    trigger: () -> Unit,
): List<E> {
    require(count > 0) { "awaitEffects needs a positive count, got $count." }
    trigger()
    return withTimeout(timeoutMillis) {
        effects.take(count).toList()
    }
}

/**
 * Waits for the first state matching [predicate].
 *
 * Use this instead of reading `state.value` straight after triggering async
 * work, which races the coroutine that produces it.
 *
 * @param timeoutMillis how long to wait before failing.
 */
public suspend fun <S : Any> ArusController<S, *>.awaitState(
    timeoutMillis: Long = 1_000,
    predicate: (S) -> Boolean,
): S = withTimeout(timeoutMillis) { state.first(predicate) }
