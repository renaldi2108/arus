package io.github.renaldi2108.arus.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Observes the controller's [state][ArusController.state], pausing collection
 * while the UI is not at least STARTED.
 *
 * ```kotlin
 * val state by controller.collectState()
 * ```
 */
@Composable
public fun <S : Any> ArusController<S, *>.collectState(): State<S> = state.collectAsStateWithLifecycle()

/**
 * Observes a single derived value instead of the whole state.
 *
 * Recomposition happens only when [selector]'s result actually changes, so a
 * screen that reads one field is not recomposed by unrelated state updates.
 * Prefer this over `collectState()` when a composable reads a narrow slice of a
 * large state object.
 *
 * ```kotlin
 * val userName by controller.select { it.user.name }
 * ```
 *
 * [selector] must be a pure function of the state. It may close over changing
 * values: the latest lambda is always the one applied, without rebuilding the
 * underlying collection.
 */
@Composable
public fun <S : Any, R> ArusController<S, *>.select(selector: (S) -> R): State<R> {
    // Read through the latest lambda rather than the one captured first.
    // Keying the `remember` on `selector` instead would rebuild the flow — and
    // restart its collector — on every recomposition whenever the caller's
    // lambda is not memoised, which is far more expensive than an indirection.
    val currentSelector by rememberUpdatedState(selector)

    val flow =
        remember(this) {
            state.map { currentSelector(it) }.distinctUntilChanged()
        }

    // `initialValue` is only consumed on first composition, but a plain
    // argument would be evaluated on every one — running the selector each time
    // for a value that is then discarded.
    val initial = remember(this) { currentSelector(state.value) }

    return flow.collectAsStateWithLifecycle(initialValue = initial)
}
