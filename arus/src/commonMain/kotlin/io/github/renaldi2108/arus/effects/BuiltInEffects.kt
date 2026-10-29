package io.github.renaldi2108.arus.effects

import io.github.renaldi2108.arus.core.UiEffect

/**
 * How long a snackbar stays on screen.
 */
public enum class SnackbarDuration {
    /** Dismissed after a few seconds. */
    Short,

    /** Dismissed after a longer interval. */
    Long,

    /** Stays until the user dismisses it or an action is taken. */
    Indefinite,
}

/**
 * Ready-made effects covering what most screens need, so applications do not
 * have to redeclare a snackbar type on every controller.
 *
 * A controller is free to ignore these entirely and declare its own sealed
 * effect hierarchy; these exist for convenience, not as a required vocabulary.
 */
public sealed interface ArusEffect : UiEffect {
    /**
     * Shows a short message, optionally with a single action.
     *
     * @param message the text to display.
     * @param actionLabel label of the optional action button.
     * @param duration how long the message stays on screen.
     */
    public data class Snackbar(
        val message: String,
        val actionLabel: String? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short,
    ) : ArusEffect

    /**
     * Asks the host to show a simple confirmation dialog.
     *
     * @param title dialog title.
     * @param message dialog body.
     * @param confirmLabel label of the confirming button.
     * @param dismissLabel label of the dismissing button, or `null` for none.
     */
    public data class Dialog(
        val title: String,
        val message: String,
        val confirmLabel: String = "OK",
        val dismissLabel: String? = null,
    ) : ArusEffect
}
