package io.github.renaldi2108.arus.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The navigator driving the enclosing [ArusHost].
 *
 * A screen body gets the navigator from [ArusScreenScope] instead — shorter,
 * and impossible to read in the wrong place. This exists for composables nested
 * deeper than the screen body, where threading it down would be noise.
 *
 * Reading it outside an [ArusHost] throws, rather than handing back a navigator
 * that points nowhere.
 */
public val LocalArusNavigator: ProvidableCompositionLocal<Navigator> =
    staticCompositionLocalOf {
        error("No Navigator here. LocalArusNavigator is only valid inside an ArusHost.")
    }

/**
 * A navigator and the screens it can render, built from one declaration.
 *
 * Only needed when something **outside** the host observes navigation — an app
 * bar showing the current destination, say. When nothing does, use the
 * [ArusHost] overload that takes the builder directly and skip this entirely.
 */
@Stable
public class ArusGraph internal constructor(
    /**
     * The stack this graph renders.
     *
     * Internal because [navigator] is what callers should hold: with guards
     * installed, this one bypasses them.
     */
    internal val stack: ArusNavigator,
    /**
     * Drives navigation, and is also what app chrome observes.
     *
     * The same object every screen receives, guards included — so a rule
     * cannot be sidestepped by navigating from a top bar instead of a screen.
     */
    public val navigator: Navigator,
    /**
     * The decorated entries, built and held by the **graph** rather than by
     * the host.
     *
     * The decorators hold every entry's `ViewModelStore` and saveable state.
     * Decorating inside [ArusHost] was fine for a single host and wrong for
     * tabs: an unselected tab's host leaves composition, so its entries stop
     * being decorated and their stores are cleared. The back stack survived,
     * so the user returned to the right screen with everything on it reset.
     * `rememberDecoratedNavEntries` is the API meant for exactly this — see
     * composition.
     *
     * A provider, not a list: `rememberDecoratedNavEntries` returns a fresh
     * list on every recomposition, and keying the graph on it would rebuild
     * the graph every frame.
     */
    private val currentEntries: () -> List<NavEntry<Route>>,
    /** Route types declared with `hidesTabBar = true`. */
    internal val hidingRoutes: Set<kotlin.reflect.KClass<out Route>> = emptySet(),
) {
    /** The decorated entries as of the latest composition. */
    internal val decoratedEntries: List<NavEntry<Route>> get() = currentEntries()

    /**
     * Records navigation for debugging or analytics.
     *
     * ```kotlin
     * val graph = rememberArusGraph(start = ListRoute) { ... }
     * remember(graph) { graph.inspector = NavInspector() }
     * ```
     */
    public var inspector: NavInspector?
        get() = stack.inspector
        set(value) {
            stack.inspector = value
        }
}

/**
 * Builds the navigation graph from a single declaration of screens.
 *
 * ```kotlin
 * val graph = rememberArusGraph(start = ListRoute) {
 *     catalogEntries(repository)
 *     checkoutEntries()
 *     settingsEntries()
 * }
 *
 * ArusHost(graph)
 * ```
 *
 * Each `screen<R>` inside the builder does two jobs at once: it binds the
 * composable **and** registers the route for saved-state serialization. There
 * is no second list of routes to keep in step, which is the boilerplate that
 * otherwise grows with every destination in the product.
 *
 * Saved state uses explicit polymorphic serialization rather than reflection,
 * so the back stack survives process death on every platform including iOS
 * platform.
 *
 * **Why [start] is here rather than only on [ArusHost].** The navigator's very
 * first value *is* the start destination, so the two cannot be separated
 * without making `currentRoute` nullable for every caller — a worse trade than
 * one argument on the less common overload. The builder-taking [ArusHost] does
 * put `start` on the host, and is the form to prefer.
 *
 * @param start the route the stack begins at.
 * @param configuration overrides serialization entirely; supply one only when
 *   the derived configuration is not enough.
 * @param navigatorKey distinguishes navigators when several hosts coexist, as
 *   with tabs. Leave it alone for a single host.
 * @param guards consulted before every forward navigation. A rule installed
 *   here applies however the destination is reached — a button, a deep link, a
 *   restored stack — which is the reason guards are not written inside
 *   controllers.
 * @param builder declares every destination, usually one call per feature.
 */
@Composable
public fun rememberArusGraph(
    start: Route,
    configuration: SavedStateConfiguration? = null,
    navigatorKey: String = "default",
    guards: List<NavGuard> = emptyList(),
    builder: ArusEntries.() -> Unit,
): ArusGraph {
    val declared = remember(builder) { ArusEntries().apply(builder) }

    val resolvedConfiguration =
        remember(declared, configuration) {
            configuration ?: declared.toSavedStateConfiguration()
        }

    val backStack = rememberNavBackStack(resolvedConfiguration, start)

    // The navigator lives in a ViewModel, not in `remember`, because a
    // configuration change rebuilds the composition. A navigator recreated on
    // rotation would be a different object from the one every surviving
    // controller already holds, and their navigation calls would quietly stop
    // working.
    @Suppress("UNCHECKED_CAST")
    val holder = viewModel<NavigatorHolder>(key = "arus-navigator-$navigatorKey")
    val navigator =
        holder.navigator
            ?: ArusNavigator(backStack as NavBackStack<Route>).also { holder.navigator = it }

    // Re-point it at the stack this composition restored.
    navigator.rebind(backStack as NavBackStack<Route>)

    // Everything above the raw stack sees the guarded navigator, including
    // app chrome, so there is no unguarded path to a destination.
    //
    // Read through `rememberUpdatedState` rather than keyed on the list: a
    // caller writing `guards = listOf(MyGuard { ... })` inline allocates a new
    // list every recomposition, which would rebuild the graph each time and
    // throw away anything attached to it — an inspector, most visibly.
    val currentGuards by rememberUpdatedState(guards)
    val guarded = remember(navigator) { navigator.guardedBy { currentGuards } }

    // Created here, not in ArusHost: a graph outlives its host leaving
    // composition, which is exactly what happens to an unselected tab.
    val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<Route>()
    val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<Route>()
    val decorators =
        remember(saveableDecorator, viewModelDecorator) {
            // Order matters: saveable state is restored before the
            // ViewModelStore is attached, so a controller reading saved state
            // during construction sees it.
            listOf(saveableDecorator, viewModelDecorator)
        }

    val scope = remember(guarded) { ArusScreenScope(guarded) }

    val entries =
        remember(declared, scope) {
            entryProvider<Route> {
                declared.screens.forEach { recorded ->
                    @Suppress("UNCHECKED_CAST")
                    addEntryProvider(
                        recorded.kClass as kotlin.reflect.KClass<Route>,
                        // The content key has to be storable in a Bundle, so it
                        // cannot be the route object itself. `toString()` on a
                        // data-class route is value-based, which matches the
                        // identity rule documented on `Route`: two routes that
                        // compare equal are the same destination.
                        { route: Route -> route.toString() },
                        recorded.metadata,
                    ) { route: Route -> with(recorded) { scope.content(route) } }
                }
            }
        }

    // Decorated here, where the graph lives, so an unselected tab's entries
    // keep their ViewModelStores and saveable state.
    val decorated =
        rememberDecoratedNavEntries(
            backStack = backStack as NavBackStack<Route>,
            entryDecorators = decorators,
            entryProvider = entries,
        )

    val currentDecorated by rememberUpdatedState(decorated)

    val hiding =
        remember(declared) {
            declared.screens.filter { it.hidesTabBar }.mapTo(mutableSetOf()) { it.kClass }
        }

    return remember(navigator, guarded, hiding) {
        ArusGraph(navigator, guarded, { currentDecorated }, hiding)
    }
}

/**
 * Declares the whole navigation graph and renders it.
 *
 * The form to reach for: nothing to hoist, no second list of routes, and the
 * start destination sits with the host that navigates it.
 *
 * ```kotlin
 * ArusHost(start = ListRoute, modifier = Modifier.padding(padding)) {
 *     catalogScreens(repository, snackbarHostState)
 *     checkoutScreens()
 *     settingsScreens()
 * }
 * ```
 *
 * Each `screen<R>` inside binds a composable **and** registers its route for
 * saved state, so adding a destination touches only the feature that owns it.
 *
 * Use the [ArusGraph] overload when something outside the host — an app bar, a
 * bottom navigation bar — needs to observe the navigator.
 *
 * @param start the route the stack begins at.
 * @param modifier applied to the underlying `NavDisplay`.
 * @param configuration overrides serialization entirely; rarely needed.
 * @param sceneStrategies extra strategies, tried before the built-in dialog
 *   one. See [ArusEntries.scene].
 * @param guards consulted before every forward navigation.
 * @param builder declares every destination, usually one call per feature.
 */
@Composable
public fun ArusHost(
    start: Route,
    modifier: Modifier = Modifier,
    configuration: SavedStateConfiguration? = null,
    sceneStrategies: List<SceneStrategy<Route>> = emptyList(),
    guards: List<NavGuard> = emptyList(),
    builder: ArusEntries.() -> Unit,
) {
    ArusHost(
        graph = rememberArusGraph(start, configuration, guards = guards, builder = builder),
        modifier = modifier,
        sceneStrategies = sceneStrategies,
    )
}

/**
 * Renders the graph's back stack.
 *
 * The only part of Arus navigation that touches UI. It wires system and
 * predictive back to [Navigator.pop], falls through to the platform when the
 * stack is at its root so the Activity can finish normally, and publishes the
 * navigator as [LocalArusNavigator] for the screens it renders.
 *
 * @param graph produced by [rememberArusGraph].
 * @param modifier applied to the underlying `NavDisplay`.
 * @param sceneStrategies extra strategies, tried **before** the built-in
 *   dialog one so an app can override how any entry is presented. Pair with
 *   [ArusEntries.scene], which is how an entry carries the metadata a strategy
 *   looks for. Needed only for a presentation Arus does not ship, such as a
 *   two-pane layout or a side sheet.
 */
@Composable
public fun ArusHost(
    graph: ArusGraph,
    modifier: Modifier = Modifier,
    sceneStrategies: List<SceneStrategy<Route>> = emptyList(),
) {
    // These two decorators are what make a controller *entry-scoped* rather
    // than Activity-scoped. Without them a `viewModel {}` inside a screen falls
    // back to the Activity's ViewModelStore, so the controller is created once
    // and never cleared when its entry is popped.
    //
    // Order matters: saveable state is restored before the ViewModelStore is
    // attached, so a controller reading saved state during construction sees it.
    val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<Route>()
    val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<Route>()
    val decorators =
        remember(saveableDecorator, viewModelDecorator) {
            listOf(saveableDecorator, viewModelDecorator)
        }

    // App strategies first, so an app can claim an entry Arus would otherwise
    // present itself. The two built-ins then pick up `dialog<R>` and
    // `bottomSheetDialog<R>`, and anything unclaimed is a full screen.
    val strategies =
        remember(sceneStrategies) {
            sceneStrategies + DialogSceneStrategy() + ArusBottomSheetStrategy()
        }

    CompositionLocalProvider(LocalArusNavigator provides graph.navigator) {
        NavDisplay(
            // Entries the graph already decorated, not a raw back stack: this
            // is the overload meant for several coexisting stacks.
            entries = graph.decoratedEntries,
            modifier = modifier,
            // Back acts on the raw stack: rendering and system back must
            // reflect what is actually there, and back is never guarded.
            onBack = { graph.stack.pop() },
            // App strategies first so they can claim an entry; the built-in
            // dialog strategy then picks up anything declared with `dialog<R>`,
            // and whatever is left falls through to a full screen.
            sceneStrategies = strategies,
        )
    }
}

/**
 * Derives the saved-state configuration from the declared screens.
 *
 * Every route registers itself, so a destination can never be rendered but
 * forgotten on process death — a mismatch that is otherwise only discovered
 * when a user comes back to a killed app.
 */
internal fun ArusEntries.toSavedStateConfiguration(): SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                // Navigation 3 resolves back-stack entries polymorphically
                // against NavKey, so that is the base they register under.
                polymorphic(NavKey::class) {
                    screens.forEach { recorded ->
                        @Suppress("UNCHECKED_CAST")
                        subclass(
                            recorded.kClass as kotlin.reflect.KClass<NavKey>,
                            recorded.serializer as KSerializer<NavKey>,
                        )
                    }
                }
            }
    }

/**
 * Keeps the navigator alive across configuration change.
 *
 * A `ViewModel` because that is the only thing in the Compose world with
 * exactly the lifetime wanted here: survives rotation, cleared when the host is
 * genuinely gone.
 */
internal class NavigatorHolder : ViewModel() {
    var navigator: ArusNavigator? = null
}
