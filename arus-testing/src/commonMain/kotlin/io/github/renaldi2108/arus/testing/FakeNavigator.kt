package io.github.renaldi2108.arus.testing

import io.github.renaldi2108.arus.nav.NavResult
import io.github.renaldi2108.arus.nav.Navigator
import io.github.renaldi2108.arus.nav.ResultRoute
import io.github.renaldi2108.arus.nav.Route
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
 * An in-memory [Navigator] for unit tests.
 *
 * Behaves like the real one — the same stack rules, the same return values — so
 * a controller test exercises the logic it will actually run against, not a
 * simplified stand-in. It simply has no UI behind it.
 *
 * ```kotlin
 * val navigator = FakeNavigator(HomeRoute)
 * val controller = ProfileController(repository, navigator)
 *
 * controller.onEditClicked()
 *
 * assertEquals(listOf(HomeRoute, EditRoute), navigator.backStack)
 * ```
 *
 * @param start the route the stack begins at.
 */
@Suppress("TooManyFunctions") // Implements the full Navigator contract; the count is the contract's.
public class FakeNavigator(
    start: Route,
) : Navigator {
    private val stack = mutableListOf(start)

    private val _currentRoute = MutableStateFlow(start)
    override val currentRoute: StateFlow<Route> = _currentRoute.asStateFlow()

    private val _canPop = MutableStateFlow(false)
    override val canPop: StateFlow<Boolean> = _canPop.asStateFlow()

    /** The stack as it stands, oldest first. */
    public val backStack: List<Route> get() = stack.toList()

    /** Every operation performed, in order, for asserting on intent. */
    public val operations: MutableList<NavOperation> = mutableListOf()

    /**
     * Result to hand back to the next [pushForResult].
     *
     * Leave it `null` and awaiting a result yields [NavResult.Cancelled], which
     * is what a user pressing back produces.
     */
    public var nextResult: Any? = null

    override fun push(route: Route) {
        stack += route
        operations += NavOperation.Push(route)
        sync()
    }

    override fun replace(route: Route) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack += route
        operations += NavOperation.Replace(route)
        sync()
    }

    override fun resetTo(route: Route) {
        stack.clear()
        stack += route
        operations += NavOperation.ResetTo(route)
        sync()
    }

    override fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        operations += NavOperation.Pop
        sync()
        return true
    }

    override fun popTo(
        route: Route,
        inclusive: Boolean,
    ): Boolean {
        val index = stack.indexOfLast { it == route }
        val keep = if (inclusive) index else index + 1
        val changes = index >= 0 && keep > 0 && keep != stack.size
        if (changes) {
            while (stack.size > keep) stack.removeAt(stack.lastIndex)
            operations += NavOperation.PopTo(route, inclusive)
            sync()
        }
        return changes
    }

    override fun popToRoot() {
        if (stack.size <= 1) return
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        operations += NavOperation.PopToRoot
        sync()
    }

    override suspend fun <R : Any> pushForResult(route: ResultRoute<R>): NavResult<R> {
        push(route)
        operations += NavOperation.PushForResult(route)
        // The destination resolves immediately: a unit test should not have to
        // drive a UI to get its answer.
        pop()
        @Suppress("UNCHECKED_CAST")
        return nextResult?.let { NavResult.Completed(it as R) } ?: NavResult.Cancelled
    }

    /**
     * Everything published, in order, for asserting without collecting.
     *
     * The flow is the production path; this is the convenience a test
     * usually wants.
     */
    public val published: MutableList<Any> = mutableListOf()

    private val messageBus =
        MutableSharedFlow<Any>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun <R : Any> popWithResult(result: R) {
        operations += NavOperation.PopWithResult(result)
        pop()
    }

    override fun publish(message: Any) {
        operations += NavOperation.Publish(message)
        published += message
        messageBus.tryEmit(message)
    }

    override fun <T : Any> messages(type: KClass<T>): Flow<T> {
        @Suppress("UNCHECKED_CAST")
        return messageBus.filter { type.isInstance(it) }.map { it as T }
    }

    private fun sync() {
        _currentRoute.value = stack.last()
        _canPop.value = stack.size > 1
    }
}

/** A single navigation call, for asserting on what a controller intended. */
public sealed interface NavOperation {
    /**
     * [Navigator.publish].
     *
     * @property message what was sent to the rest of the stack.
     */
    public data class Publish(
        val message: Any,
    ) : NavOperation

    /**
     * [Navigator.push].
     *
     * @property route the destination pushed.
     */
    public data class Push(
        val route: Route,
    ) : NavOperation

    /**
     * [Navigator.replace].
     *
     * @property route the destination that replaced the top entry.
     */
    public data class Replace(
        val route: Route,
    ) : NavOperation

    /**
     * [Navigator.resetTo].
     *
     * @property route the destination the stack restarted at.
     */
    public data class ResetTo(
        val route: Route,
    ) : NavOperation

    /** [Navigator.pop]. */
    public data object Pop : NavOperation

    /**
     * [Navigator.popTo].
     *
     * @property route the destination popped back to.
     * @property inclusive whether that destination was removed as well.
     */
    public data class PopTo(
        val route: Route,
        val inclusive: Boolean,
    ) : NavOperation

    /** [Navigator.popToRoot]. */
    public data object PopToRoot : NavOperation

    /**
     * [Navigator.pushForResult].
     *
     * @property route the destination awaited.
     */
    public data class PushForResult(
        val route: Route,
    ) : NavOperation

    /**
     * [Navigator.popWithResult].
     *
     * @property result the value handed back.
     */
    public data class PopWithResult(
        val result: Any,
    ) : NavOperation
}
