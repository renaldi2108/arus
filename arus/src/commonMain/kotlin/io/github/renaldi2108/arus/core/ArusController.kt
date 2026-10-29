package io.github.renaldi2108.arus.core

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Default capacity of a controller's effect buffer.
 *
 * Effects emitted while no collector is attached are retained up to this many,
 * after which the **oldest** is dropped.
 */
public const val DEFAULT_EFFECT_CAPACITY: Int = 64

/**
 * Base class for Arus screen logic: one immutable [UiState][S] plus a stream of
 * one-shot [effects][E].
 *
 * It is an ordinary `androidx.lifecycle.ViewModel`, so it survives configuration
 * change and is cleared exactly once when its owner is permanently gone. Nothing
 * here touches Android APIs, which is why the class compiles for every Arus
 * target including iOS.
 *
 * ```kotlin
 * data class ProfileState(val loading: Boolean = false, val name: String = "")
 *
 * sealed interface ProfileEffect : UiEffect {
 *     data class ShowError(val message: String) : ProfileEffect
 * }
 *
 * class ProfileController(
 *     private val repo: ProfileRepository,
 * ) : ArusController<ProfileState, ProfileEffect>(ProfileState()) {
 *
 *     fun onRefresh() = launchCatching {
 *         setState { copy(loading = true) }
 *         val name = repo.load()
 *         setState { copy(loading = false, name = name) }
 *     }
 *
 *     override fun onError(error: Throwable) {
 *         setState { copy(loading = false) }
 *         emit(ProfileEffect.ShowError(error.message ?: "Unknown error"))
 *     }
 * }
 * ```
 *
 * @param S the immutable state type; one per controller.
 * @param E the effect type; use [NoEffect] when the controller emits nothing.
 * @param initialState the state exposed before any [setState] call.
 * @param effectCapacity how many effects are retained while no collector is
 *   attached; see [DEFAULT_EFFECT_CAPACITY].
 */
public abstract class ArusController<S : Any, E : UiEffect>(
    initialState: S,
    effectCapacity: Int = DEFAULT_EFFECT_CAPACITY,
    private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)

    /**
     * The single source of truth for this controller's screen.
     *
     * Always has a value, and every update is an atomic replacement rather than
     * a mutation, so a collector never observes a half-applied change.
     */
    public val state: StateFlow<S> = _state.asStateFlow()

    private val effectChannel: Channel<E> =
        Channel(
            capacity = effectCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * One-shot effects, delivered exactly once to a **single** collector.
     *
     * Effects emitted while nobody is collecting are retained (up to the
     * configured capacity) and delivered as soon as a collector attaches, so an
     * effect emitted while the app is backgrounded is not silently lost.
     *
     * Collecting this from two places at once splits the stream between them
     * rather than duplicating it — attach exactly one collector per controller.
     */
    public val effects: Flow<E> = effectChannel.receiveAsFlow()

    /**
     * Atomically replaces the state by applying [reducer] to the current value.
     *
     * [reducer] may run more than once under contention, so it must be pure:
     * derive the new state from the receiver only, and do not perform side
     * effects inside it.
     */
    protected fun setState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    /**
     * Queues [effect] for the UI.
     *
     * Never suspends and never throws: if the buffer is full the oldest pending
     * effect is dropped to make room.
     */
    protected fun emit(effect: E) {
        val result = effectChannel.trySend(effect)
        if (!result.isSuccess) {
            ArusLog.debug { "Effect dropped on ${this::class.simpleName}: $effect" }
        }
    }

    /**
     * Runs [block] in [viewModelScope], cancelled when the controller is
     * cleared.
     *
     * Failures propagate as they would from any coroutine. Use
     * [launchCatching] when the work can fail in a way the screen should
     * survive — which is most of the time, and is why that one takes the
     * shorter path through [onError].
     */
    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(block = block)

    /**
     * Launches [block] in `viewModelScope`, routing any failure to [onFailure].
     *
     * Cancellation is rethrown rather than reported, so cancelling the scope
     * does not look like an error.
     *
     * @param onFailure where uncaught failures go; defaults to [onError].
     */

    @Suppress("TooGenericExceptionCaught") // Catching broadly is this function's entire purpose.
    protected fun launchCatching(
        onFailure: (Throwable) -> Unit = ::onError,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onFailure(error)
            }
        }

    /**
     * Handles failures from [launchCatching] that were not given an explicit
     * handler.
     *
     * The default logs when [Arus.debugLogging] is on and otherwise does
     * nothing. Override to surface the failure in state or as an effect.
     */
    protected open fun onError(error: Throwable) {
        ArusLog.debug { "Unhandled error in ${this::class.simpleName}: $error" }
    }

    /**
     * A single value persisted across process death.
     *
     * For the small pieces of screen state the system should restore when it
     * kills a backgrounded app and the user comes back: a selected tab, a
     * search query, a scroll anchor. Everything else belongs in [state],
     * which is rebuilt from the route and the repository.
     *
     * ```kotlin
     * class SearchController(
     *     handle: SavedStateHandle,
     * ) : ArusController<SearchState, Nothing>(SearchState(), savedStateHandle = handle) {
     *     private var query: String by savedState("query", "")
     * }
     * ```
     *
     * Supply the handle with `createSavedStateHandle()`:
     *
     * ```kotlin
     * screen<SearchRoute> {
     *     SearchScreen(viewModel { SearchController(createSavedStateHandle()) })
     * }
     * ```
     *
     * [T] must be something the platform can write to saved state — a
     * primitive, a String, or a type the savedstate library can serialize.
     *
     * @param key unique within this controller; it is the name under which
     *   the value is stored.
     * @param default returned until something is written, and after a restore
     *   that found nothing.
     * @throws IllegalStateException at declaration time if the controller was
     *   built without a [SavedStateHandle], so the mistake surfaces when the
     *   controller is constructed rather than on a user's phone after a kill.
     */
    protected fun <T : Any> savedState(
        key: String,
        default: T,
    ): ReadWriteProperty<Any?, T> {
        val handle =
            checkNotNull(savedStateHandle) {
                "savedState(\"$key\") needs a SavedStateHandle. Take one in the constructor of " +
                    "${this::class.simpleName} and pass it as `savedStateHandle`, supplying it " +
                    "with `createSavedStateHandle()` from the screen declaration."
            }
        return SavedStateProperty(handle, key, default)
    }

    override fun onCleared() {
        effectChannel.close()
        super.onCleared()
    }
}

/**
 * Backs [ArusController.savedState].
 *
 * Reads and writes straight through to the handle rather than caching: the
 * handle is the source of truth, and a cached copy would diverge from it
 * after a restore.
 */
private class SavedStateProperty<T : Any>(
    private val handle: SavedStateHandle,
    private val key: String,
    private val default: T,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = handle.get<T>(key) ?: default

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T,
    ) {
        handle[key] = value
    }
}
