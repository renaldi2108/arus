package io.github.renaldi2108.arus.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.jvm.JvmName
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * How long a [derived] flow keeps running after its last collector goes away,
 * in milliseconds. Long enough to survive a configuration change without
 * recomputing.
 */
private const val DERIVED_STOP_TIMEOUT_MS = 5_000L

/**
 * A single observable value.
 *
 * Named for Arsenal, and short enough to read as a type beside the value it
 * holds — `Ars<String>` rather than something that needs a line of its own.
 *
 * `Ars` is the **secondary** reactivity tool in Arus. The primary
 * pattern is one immutable `UiState` per controller, which keeps updates atomic
 * and easy to log and test. Reach for `Ars` only for a genuinely independent
 * value — a text field's content, an animation toggle — where folding it into
 * the screen state would add more noise than it removes.
 *
 * ```kotlin
 * class SearchController : ArusController<SearchState, NoEffect>(SearchState()) {
 *     var query: String by ars("")
 * }
 * ```
 */
public class Ars<T>(
    initial: T,
) : ReadWriteProperty<Any?, T> {
    private val flow = MutableStateFlow(initial)

    /** This value as a [StateFlow], for collecting outside composition. */
    public val asFlow: StateFlow<T> = flow.asStateFlow()

    /** The current value. */
    public var value: T
        get() = flow.value
        set(newValue) {
            flow.value = newValue
        }

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = flow.value

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T,
    ) {
        flow.value = value
    }
}

/**
 * Creates an [Ars] property delegate holding [initial].
 *
 * See [Ars] for when this is the right tool and when a single `UiState` is
 * better.
 */
@JvmName("arsOf")
public fun <T> ars(initial: T): Ars<T> = Ars(initial)

/**
 * Wraps the receiver in an [Ars], as `"".ars()`.
 *
 * The same thing [ars] does, written the other way round when that reads
 * better at the declaration:
 *
 * ```kotlin
 * val draft = "".ars()
 * var page by 1.ars()
 * ```
 *
 * **A function rather than a property**, deliberately. As `val T.ars` it
 * would build a new [Ars] on every read, so two reads of the same expression
 * would be two independent containers and a write to one would be invisible
 * to the other — with nothing to signal the mistake. The parentheses make
 * the construction visible at the call site.
 *
 * It is an extension on every type, so it only appears in files that import
 * it. Prefer [ars] where there is no receiver to read from.
 */
public fun <T> T.ars(): Ars<T> = Ars(this)

/**
 * Observes an [Ars] value from composition, pausing while the UI is stopped.
 *
 * ```kotlin
 * val query by controller.query.watch()
 * ```
 */
@Composable
public fun <T> Ars<T>.watch(): State<T> = asFlow.collectAsStateWithLifecycle()

/**
 * Derives a hot [StateFlow] from this one by applying [transform].
 *
 * The derived flow is computed only while something collects it, and keeps
 * running for a few seconds afterwards so a configuration change does not
 * trigger a recomputation.
 *
 * Call this from a controller, passing `viewModelScope`:
 *
 * ```kotlin
 * val visibleItems: StateFlow<List<Item>> =
 *     state.derived(viewModelScope) { it.items.filter(Item::visible) }
 * ```
 *
 * @param scope the scope the derived flow runs in; normally `viewModelScope`.
 */
public fun <T, R> StateFlow<T>.derived(
    scope: CoroutineScope,
    transform: (T) -> R,
): StateFlow<R> =
    map(transform).stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(DERIVED_STOP_TIMEOUT_MS),
        initialValue = transform(value),
    )
