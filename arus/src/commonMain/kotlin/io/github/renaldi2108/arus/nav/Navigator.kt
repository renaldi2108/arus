package io.github.renaldi2108.arus.nav

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * The only navigation API that logic layers see.
 *
 * A `Navigator` is a **state holder, not a view holder**: it owns a list of
 * typed route keys and nothing else. It never references an Activity, a
 * Context, a View or a NavController, which is what makes it safe to inject
 * into a controller, to call from a background thread, and to fake in a JVM
 * test.
 *
 * Every method mutates the back stack synchronously, independently of whether
 * any UI is currently attached. A navigation issued while the app is in the
 * background takes effect immediately and the UI catches up when it resumes —
 * commands are never queued and never dropped.
 *
 * The vocabulary is the stack's own — [push], [pop], [popTo], [replace],
 * [resetTo] — so a reader can tell what happens to the back stack from the call
 * alone, without remembering a framework's shorthand.
 */
public interface Navigator {
    /** The destination currently on top of the stack. */
    public val currentRoute: StateFlow<Route>

    /** Whether [pop] would remove an entry rather than do nothing. */
    public val canPop: StateFlow<Boolean>

    /**
     * Pushes [route] on top of the stack.
     *
     * The stack grows by one; the current destination stays below and is
     * returned to on [pop].
     */
    public fun push(route: Route)

    /**
     * Swaps the top entry for [route], leaving the rest of the stack alone.
     *
     * Use this when a screen reconfigures itself — a filter changing, a
     * different payment method chosen — so repeated changes do not pile up
     * copies the user has to press back through.
     */
    public fun replace(route: Route)

    /**
     * Clears the whole stack and starts again at [route].
     *
     * Use this after a flow completes, so back does not walk the user into a
     * checkout they already finished.
     */
    public fun resetTo(route: Route)

    /**
     * Removes the top entry.
     *
     * @return `true` if an entry was removed, `false` if already at the root —
     *   in which case the host lets the platform handle back, usually by
     *   finishing the Activity.
     */
    public fun pop(): Boolean

    /**
     * Removes entries until [route] is on top.
     *
     * @param inclusive when `true`, removes [route] as well, landing on
     *   whatever was below it.
     * @return `true` if [route] was found and the stack changed.
     */
    public fun popTo(
        route: Route,
        inclusive: Boolean = false,
    ): Boolean

    /** Removes everything above the first entry. */
    public fun popToRoot()

    /**
     * Pushes [route] and suspends until it closes, returning what it produced.
     *
     * ```kotlin
     * fun onPickCountry() = launchCatching {
     *     when (val result = navigator.pushForResult(PickCountryRoute(current))) {
     *         is NavResult.Completed -> setState { copy(country = result.value) }
     *         NavResult.Cancelled -> Unit
     *     }
     * }
     * ```
     *
     * Returns [NavResult.Cancelled] if the entry is removed any other way —
     * back pressed, [popTo] skipping over it, [resetTo] clearing the stack.
     *
     * **The request does not survive process death.** The coroutine awaiting it
     * dies with the process, so after a restore the back stack still shows the
     * destination but nothing is listening. Completing it then is reported in
     * debug logs and otherwise ignored; a caller that must survive this should
     * persist its own state rather than rely on a pending result.
     *
     * Safe to call from any thread; the stack mutates synchronously and the
     * suspension happens afterwards.
     */
    public suspend fun <R : Any> pushForResult(route: ResultRoute<R>): NavResult<R>

    /**
     * Closes the current destination, handing [result] to whoever awaited it.
     *
     * Pops exactly as [pop] does. If nothing is awaiting — the common case
     * after process death — the value is dropped and the call still pops.
     */
    public fun <R : Any> popWithResult(result: R)

    /**
     * Sends [message] to every screen on this stack that is listening for its
     * type.
     *
     * For telling a screen several steps back that something happened.
     * [pushForResult] answers the screen that asked, and only that one; this
     * reaches any of them, at any depth, in either direction.
     *
     * ```kotlin
     * // five screens deep
     * navigator.publish(MethodPicked("TRANSFER"))
     *
     * // in a controller near the root, still alive because its entry is
     * // still on the stack
     * init {
     *     launchCatching {
     *         navigator.messages<MethodPicked>().collect { picked ->
     *             setState { copy(method = picked.value) }
     *         }
     *     }
     * }
     * ```
     *
     * **Reach for shared state first.** If what you are sending is domain
     * data — a cart, a profile, a setting — it belongs in a repository both
     * screens observe, not in a message. Use this for things that are
     * genuinely events between screens, where no repository would be honest.
     *
     * **It is not delivered across process death.** The message lives in
     * memory on the navigator, like a pending [pushForResult]. A killed and
     * restored app has no message queue.
     *
     * **Everyone listening receives it**, including the sender if it listens
     * for the same type. There is no addressing.
     *
     * Publishing when nothing is listening costs nothing and allocates
     * nothing, so a `publish` on a hot path is safe.
     */
    public fun publish(message: Any)

    /**
     * Messages of type [type] published on this stack, from now on.
     *
     * Collect it from a controller's scope; the subscription ends when the
     * controller is cleared, which is when its entry leaves the stack. Use
     * the reified [messages] extension in ordinary code.
     *
     * Nothing is replayed: a collector started after a message was published
     * does not see it. Screens behind you subscribed before you got here, so
     * this is the common case rather than a limitation.
     */
    public fun <T : Any> messages(type: KClass<T>): Flow<T>
}

/**
 * Messages of type [T] published on this stack.
 *
 * ```kotlin
 * navigator.messages<MethodPicked>().collect { … }
 * ```
 */
public inline fun <reified T : Any> Navigator.messages(): Flow<T> = messages(T::class)
