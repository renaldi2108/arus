package io.github.renaldi2108.arus.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import kotlinx.coroutines.flow.collect

/**
 * Collects a controller's one-shot effects while the UI is at least STARTED.
 *
 * Collection stops when the UI stops and resumes when it starts again. Because
 * the controller retains effects emitted with no collector attached, an effect
 * produced while the app was backgrounded is delivered on return rather than
 * lost.
 *
 * Attach this **once** per controller. Two simultaneous collectors split the
 * stream between them instead of each receiving every effect.
 *
 * [onEffect] may close over changing state: the latest lambda is always the one
 * invoked. Collection itself is started once and is not restarted when the
 * lambda changes, so an effect in flight is never dropped.
 *
 * ```kotlin
 * CollectEffects(controller) { effect ->
 *     when (effect) {
 *         is ProfileEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
 *     }
 * }
 * ```
 *
 * @param controller the controller whose effects to observe.
 * @param onEffect handler invoked once per effect; may suspend.
 */
@Composable
public fun <E : UiEffect> CollectEffects(
    controller: ArusController<*, E>,
    onEffect: suspend (E) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Without this the handler is frozen at first composition. A caller whose
    // lambda closes over changing state would keep running the stale one, and
    // the failure is silent: effects still arrive, they are just handled with
    // yesterday's values. Keying the LaunchedEffect on `onEffect` instead would
    // restart collection whenever the lambda changed, which can drop an effect
    // that is mid-delivery.
    val currentOnEffect by rememberUpdatedState(onEffect)

    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.effects.collect { effect -> currentOnEffect(effect) }
        }
    }
}
