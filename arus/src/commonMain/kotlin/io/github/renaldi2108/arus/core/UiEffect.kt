package io.github.renaldi2108.arus.core

/**
 * Marker for one-shot things a controller asks the UI to do: show a snackbar,
 * open a dialog, play a sound.
 *
 * Effects are **events, not state**. They are delivered
 * exactly once to a single active collector and are never replayed on
 * recomposition or configuration change. Anything that should survive rotation
 * belongs in the controller's `UiState` instead.
 *
 * This marker lives in `arus-core` rather than `arus-effects` because
 * [ArusController] is generic over it and the dependency direction is
 * `core <- effects`. The built-in effect types and their renderers live in
 * `arus-effects`.
 *
 * Applications normally declare one sealed hierarchy per controller:
 *
 * ```kotlin
 * sealed interface ProfileEffect : UiEffect {
 *     data class ShowError(val message: String) : ProfileEffect
 *     data object NavigateToSettings : ProfileEffect
 * }
 * ```
 */
public interface UiEffect

/**
 * Effect type for controllers that emit nothing.
 *
 * Use it as the effect parameter of a controller that only ever changes state:
 * `class CounterController : ArusController<CounterState, NoEffect>(...)`.
 * Because it has no instances, `emit` can never be called.
 */
public sealed interface NoEffect : UiEffect
