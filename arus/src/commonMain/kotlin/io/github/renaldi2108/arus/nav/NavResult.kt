package io.github.renaldi2108.arus.nav

/**
 * A destination that hands a value back when it closes.
 *
 * The type parameter is what makes the handoff checked: a picker that yields a
 * `String` cannot be awaited as if it yielded an `Int`.
 *
 * ```kotlin
 * @Serializable
 * data class PickCountryRoute(val preselected: String?) : ResultRoute<String>
 * ```
 *
 * @param R what this destination produces.
 */
public interface ResultRoute<out R : Any> : Route

/**
 * How a [ResultRoute] ended.
 *
 * @param R the value the destination produces.
 */
public sealed interface NavResult<out R : Any> {
    /** The destination produced [value]. */
    public data class Completed<out R : Any>(
        val value: R,
    ) : NavResult<R>

    /**
     * The destination closed without producing anything — back pressed, the
     * flow abandoned, or the entry removed by [Navigator.popTo] or
     * [Navigator.resetTo].
     */
    public data object Cancelled : NavResult<Nothing>
}

/** The value if the destination completed, or `null` if it was cancelled. */
public fun <R : Any> NavResult<R>.valueOrNull(): R? = (this as? NavResult.Completed)?.value
