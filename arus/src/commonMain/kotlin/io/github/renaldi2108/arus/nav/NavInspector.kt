package io.github.renaldi2108.arus.nav

/**
 * What a [Navigator] did.
 *
 * The name matches the method that produced it, so a log line reads like the
 * call that caused it.
 *
 * Named `NavAction` rather than `NavOperation` because `arus-testing` already
 * has a `NavOperation` for what [FakeNavigator][io.github.renaldi2108.arus.testing] records,
 * and a test that used both would have to qualify one of them.
 */
public enum class NavAction {
    /** [Navigator.push]. */
    Push,

    /** [Navigator.replace]. */
    Replace,

    /** [Navigator.resetTo]. */
    ResetTo,

    /** [Navigator.pop], including the pop inside [Navigator.popWithResult]. */
    Pop,

    /** [Navigator.popTo]. */
    PopTo,

    /** [Navigator.popToRoot]. */
    PopToRoot,
}

/**
 * One navigation event: what happened, to which destination, and the stack it
 * left behind.
 *
 * @param action the call that produced this event.
 * @param route the destination the call named, or `null` for operations that
 *   name none — [NavAction.Pop] and [NavAction.PopToRoot].
 * @param stack the back stack **after** the operation, oldest first.
 */
public class NavEvent(
    public val action: NavAction,
    public val route: Route?,
    public val stack: List<Route>,
) {
    /** The destination the user is now looking at. */
    public val currentRoute: Route? get() = stack.lastOrNull()

    /** Renders as the call that caused it, followed by the resulting stack. */
    override fun toString(): String =
        buildString {
            append(action.name.replaceFirstChar { it.lowercase() })
            append('(')
            route?.let { append(it) }
            append(") -> ")
            append(stack.map { it::class.simpleName })
        }
}

/**
 * Records navigation events, for debugging and for analytics.
 *
 * Attach one to a navigator and every stack mutation is captured in order:
 *
 * ```kotlin
 * val inspector = NavInspector()
 * graph.navigator.inspector = inspector
 *
 * // later, when something looks wrong
 * inspector.events.forEach(::println)
 * ```
 *
 * **Debug and analytics only.** The log is held in memory, so it is capped at
 * [capacity] events and discards the oldest first — an app left running for a
 * day cannot grow it without bound. Nothing attaches an inspector by default,
 * and a navigator without one allocates nothing per navigation.
 *
 * For analytics, prefer [onEvent] over polling [events]: it fires as navigation
 * happens, on whichever thread navigated.
 *
 * This class is **not** thread-safe. Navigation is a main-thread operation; an
 * inspector read from another thread needs its own synchronization.
 *
 * @param capacity how many events to keep. The default is generous enough to
 *   cover a whole user journey and small enough to be free.
 */
public class NavInspector(
    public val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "NavInspector capacity must be positive, got $capacity." }
    }

    private val recorded = ArrayDeque<NavEvent>(capacity)

    /**
     * Every event still held, oldest first.
     *
     * A snapshot: it does not change under you as navigation continues.
     */
    public val events: List<NavEvent> get() = recorded.toList()

    /**
     * Called as each event is recorded, before it is added to [events].
     *
     * Where an analytics screen-view call belongs. Keep it cheap — it runs
     * inside the navigation call.
     */
    public var onEvent: ((NavEvent) -> Unit)? = null

    /** Records [event], dropping the oldest if [capacity] is reached. */
    internal fun record(event: NavEvent) {
        onEvent?.invoke(event)
        if (recorded.size == capacity) {
            recorded.removeFirst()
        }
        recorded.addLast(event)
    }

    /** Forgets every event. */
    public fun clear() {
        recorded.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
