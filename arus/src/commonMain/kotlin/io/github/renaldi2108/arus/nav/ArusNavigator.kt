package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import io.github.renaldi2108.arus.core.Arus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

/**
 * Default [Navigator], backed by a Navigation 3 [NavBackStack].
 *
 * The back stack is a snapshot-state list, so mutating it is observable by
 * composition and survives configuration change. [currentRoute] and
 * [canGoBack] are plain [StateFlow]s kept in step with every mutation, which
 * lets non-Compose code — a controller, a test — observe navigation without
 * depending on the Compose runtime.
 *
 * Holds no Context, View or Activity reference of any kind, so it cannot leak
 * an Activity and can be constructed in a JVM test.
 *
 * @param backStack the stack to own; normally supplied by
 *   [rememberArusNavigator] so it is saved and restored for you.
 */
@Suppress("TooManyFunctions") // Implements the full Navigator contract; the count is the contract's.
public class ArusNavigator(
    backStack: NavBackStack<Route>,
) : Navigator {
    /**
     * The stack being driven.
     *
     * A `var` because a configuration change rebuilds the composition and
     * `rememberNavBackStack` restores into a **new** instance. The navigator
     * itself outlives that (it is held in a ViewModel), so it is re-pointed at
     * the restored stack rather than replaced — otherwise every controller that
     * survived the rotation would hold a navigator driving an orphaned stack,
     * and its navigation calls would silently do nothing.
     */
    private var backStack: NavBackStack<Route> = backStack

    init {
        require(backStack.isNotEmpty()) {
            "ArusNavigator needs a non-empty back stack. Pass a start route to rememberArusNavigator()."
        }
    }

    private val _currentRoute = MutableStateFlow(backStack.last())
    override val currentRoute: StateFlow<Route> = _currentRoute.asStateFlow()

    private val _canPop = MutableStateFlow(backStack.size > 1)
    override val canPop: StateFlow<Boolean> = _canPop.asStateFlow()

    /**
     * Points this navigator at [restored] after a configuration change.
     *
     * Pending result requests are deliberately kept: the coroutine awaiting one
     * lives in a controller, which survives the same rotation.
     */
    internal fun rebind(restored: NavBackStack<Route>) {
        if (restored === backStack) return
        backStack = restored
        _currentRoute.value = restored.last()
        _canPop.value = restored.size > 1
    }

    /**
     * Awaiting result requests, keyed the same way entries are identified.
     *
     * A map rather than a single slot because nothing stops two nested
     * destinations from each awaiting a result at once.
     */
    private val pendingResults = mutableMapOf<String, CompletableDeferred<Any?>>()

    /**
     * Records every navigation, for debugging or analytics.
     *
     * `null` by default, and while it is null a navigation allocates nothing
     * extra — the event is not built unless somebody is listening. Attach one
     * from debug code:
     *
     * ```kotlin
     * graph.navigator.inspector = NavInspector()
     * ```
     */
    public var inspector: NavInspector? = null

    /**
     * Screen-to-screen messages, created on first use.
     *
     * Null until somebody subscribes, so an app that never publishes carries
     * one null field and nothing else. [publish] returns immediately while it
     * is null, which is what makes publishing on a hot path free.
     *
     * `DROP_OLDEST` rather than suspending: a navigator must never block the
     * caller, and a message nobody drained in time is less harmful than a
     * stalled click handler.
     */
    private var messageBus: MutableSharedFlow<Any>? = null

    /**
     * The live stack, for the host to render. Read-only to callers; mutate it
     * through the [Navigator] methods so the observable state stays in step.
     *
     * **Live, not a snapshot.** The host needs this so `NavDisplay` observes
     * every mutation, but it means a value held across a navigation call will
     * have changed underneath you. Call `toList()` to capture the stack as it
     * is now — a test asserting on the stack at a particular moment almost
     * certainly wants that.
     */
    public val entries: List<Route> get() = backStack

    override fun push(route: Route) {
        backStack.add(route)
        sync(NavAction.Push, route)
    }

    override fun replace(route: Route) {
        if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
        }
        backStack.add(route)
        sync(NavAction.Replace, route)
    }

    override fun resetTo(route: Route) {
        backStack.clear()
        backStack.add(route)
        sync(NavAction.ResetTo, route)
    }

    override fun pop(): Boolean {
        if (backStack.size <= 1) {
            ArusLog.debug { "pop() at root - letting the platform handle it" }
            return false
        }
        backStack.removeAt(backStack.lastIndex)
        sync(NavAction.Pop, null)
        return true
    }

    override fun popTo(
        route: Route,
        inclusive: Boolean,
    ): Boolean {
        val keepCount = keepCountForPopTo(route, inclusive)
        if (keepCount == null || keepCount == backStack.size) return false

        while (backStack.size > keepCount) {
            backStack.removeAt(backStack.lastIndex)
        }
        sync(NavAction.PopTo, route)
        return true
    }

    /**
     * How many entries [popTo] should keep, or `null` when the request cannot
     * be honoured: the route is absent, or popping it would empty the stack.
     */
    private fun keepCountForPopTo(
        route: Route,
        inclusive: Boolean,
    ): Int? {
        val index = backStack.indexOfLast { it == route }
        if (index < 0) {
            ArusLog.debug { "popTo($route) ignored - not in the stack" }
            return null
        }

        val keepCount = if (inclusive) index else index + 1
        return if (keepCount <= 0) {
            ArusLog.debug { "popTo($route, inclusive = true) ignored - would empty the stack" }
            null
        } else {
            keepCount
        }
    }

    override suspend fun <R : Any> pushForResult(route: ResultRoute<R>): NavResult<R> {
        val key = route.toString()
        val awaiting = CompletableDeferred<Any?>()
        pendingResults[key] = awaiting

        push(route)

        return try {
            @Suppress("UNCHECKED_CAST")
            awaiting.await()?.let { NavResult.Completed(it as R) } ?: NavResult.Cancelled
        } finally {
            pendingResults.remove(key)
        }
    }

    override fun <R : Any> popWithResult(result: R) {
        val key = backStack.lastOrNull()?.toString()
        // Taken out of the map before popping so that `pop`'s orphan sweep does
        // not cancel it first.
        val awaiting = key?.let { pendingResults.remove(it) }
        if (awaiting == null) {
            ArusLog.debug {
                "popWithResult($result) had nobody awaiting - the request did not survive, " +
                    "most likely process death. Popping anyway."
            }
        }

        // Pop *before* handing the value over. Completing the request resumes
        // the awaiting coroutine immediately, and that coroutine usually
        // navigates — so if the destination were still on the stack, a
        // `replace` in the continuation would land on the destination being
        // dismissed rather than the screen beneath it.
        pop()
        awaiting?.complete(result)
    }

    override fun publish(message: Any) {
        // Nobody is listening — nothing was ever allocated, so there is
        // nothing to emit into.
        val bus = messageBus ?: return
        bus.tryEmit(message)
    }

    override fun <T : Any> messages(type: KClass<T>): Flow<T> {
        val bus =
            messageBus ?: MutableSharedFlow<Any>(
                extraBufferCapacity = MESSAGE_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ).also { messageBus = it }

        @Suppress("UNCHECKED_CAST")
        return bus.filter { type.isInstance(it) }.map { it as T }
    }

    override fun popToRoot() {
        if (backStack.size <= 1) return
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        sync(NavAction.PopToRoot, null)
    }

    /**
     * Cancels result requests for entries that are no longer on the stack.
     *
     * Called after every mutation, so a destination removed by [popTo] or
     * [resetTo] cancels its awaiter exactly like [pop] does — otherwise a
     * caller would suspend forever on a screen the user can no longer see.
     */
    private fun cancelOrphanedResults() {
        if (pendingResults.isEmpty()) return
        val present = backStack.mapTo(mutableSetOf()) { it.toString() }
        val orphaned = pendingResults.keys.filterNot { it in present }
        orphaned.forEach { key -> pendingResults.remove(key)?.complete(null) }
    }

    /**
     * Republishes the observable state after a mutation, and publishes the
     * event to whoever is listening.
     *
     * The single funnel every stack mutation passes through, which is what
     * makes the inspector complete by construction rather than by remembering
     * to add a call.
     */
    private fun sync(
        action: NavAction,
        route: Route?,
    ) {
        cancelOrphanedResults()
        _currentRoute.value = backStack.last()
        _canPop.value = backStack.size > 1
        publish(action, route)
    }

    /**
     * Builds the event only if somebody wants it.
     *
     * With no inspector attached and logging off — the release case — this is
     * one null check and one boolean read, and allocates nothing. That matters
     * because it runs on every navigation.
     */
    private fun publish(
        action: NavAction,
        route: Route?,
    ) {
        val listener = inspector
        if (listener == null && !Arus.debugLogging) return

        val event = NavEvent(action, route, backStack.toList())
        listener?.record(event)
        ArusLog.debug { event.toString() }
    }
}

/**
 * Internal logging mirror of `arus-core`'s logger, gated on the same flag.
 */
private object ArusLog {
    inline fun debug(message: () -> String) {
        if (Arus.debugLogging) {
            println("[Arus/nav] ${message()}")
        }
    }
}

/**
 * How many undrained messages a navigator holds before dropping the oldest.
 *
 * Generous for a queue that is normally empty: a collector on a live
 * controller drains within a frame.
 */
private const val MESSAGE_BUFFER: Int = 32
