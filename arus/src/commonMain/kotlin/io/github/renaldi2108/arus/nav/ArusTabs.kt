package io.github.renaldi2108.arus.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.scene.SceneStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One tab of a bottom navigation bar, with its own back stack.
 *
 * @param id stable identity, used to tell tabs apart and to persist which one
 *   is selected. Keep it constant across releases.
 * @param start the route this tab begins at.
 */
@Stable
public class ArusTab(
    public val id: String,
    public val start: Route,
)

/**
 * Several independent back stacks, one per tab.
 *
 * Each tab keeps its own history, so switching away and back returns the user
 * exactly where they were rather than to the tab's root — the behaviour people
 * expect from a bottom navigation bar, and the thing a single shared stack
 * cannot give you.
 *
 * [navigator] always points at the selected tab's stack, so a controller
 * injected with a [Navigator] needs no knowledge that tabs exist at all.
 */
@Stable
public class ArusTabs internal constructor(
    private val tabs: List<ArusTab>,
    internal val graphs: Map<String, ArusGraph>,
    initialTab: String,
) {
    private val _selectedTab = MutableStateFlow(initialTab)

    /** Id of the tab currently showing. */
    public val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    /** The selected tab's navigator. */
    public val navigator: Navigator
        get() = graphs.getValue(_selectedTab.value).navigator

    /** Every tab, in the order they were declared. */
    public val all: List<ArusTab> get() = tabs

    /**
     * Whether [route] was declared with `hidesTabBar = true`.
     *
     * The predicate behind [tabBarVisible], exposed separately because it is
     * a plain function: it can be asserted in a unit test without a device.
     */
    public fun hidesTabBar(route: Route): Boolean = graphs.values.any { route::class in it.hidingRoutes }

    /**
     * Shows [tabId].
     *
     * Selecting the tab that is already showing resets it to its root, which is
     * the convention users expect from tapping the active tab.
     */
    public fun select(tabId: String) {
        require(graphs.containsKey(tabId)) {
            "No tab with id '$tabId'. Declared tabs: ${tabs.joinToString { it.id }}."
        }
        if (_selectedTab.value == tabId) {
            graphs.getValue(tabId).navigator.popToRoot()
        } else {
            _selectedTab.value = tabId
        }
    }

    /**
     * Handles a back press across tabs.
     *
     * Pops within the current tab first. At a non-first tab's root it returns
     * to the first tab, so back never strands the user. At the first tab's root
     * it returns `false` and the platform finishes the Activity.
     */
    public fun pop(): Boolean {
        if (navigator.pop()) return true

        val first = tabs.first().id
        val onSecondaryTab = _selectedTab.value != first
        if (onSecondaryTab) {
            _selectedTab.value = first
        }
        return onSecondaryTab
    }
}

/**
 * Creates one back stack per tab and remembers which is selected.
 *
 * ```kotlin
 * val tabs = rememberArusTabs(
 *     ArusTab("home", HomeRoute),
 *     ArusTab("orders", OrdersRoute),
 * ) {
 *     homeScreens()
 *     orderScreens()
 * }
 * ```
 *
 * Every tab shares one declaration of screens, because a destination reachable
 * from two tabs should not be declared twice. Each tab gets its own stack, not
 * its own set of routes.
 *
 * @param tabs the tabs, in bar order; the first is the back-press destination.
 * @param builder declares every destination across all tabs.
 */
@Composable
public fun rememberArusTabs(
    vararg tabs: ArusTab,
    builder: ArusEntries.() -> Unit,
): ArusTabs {
    require(tabs.isNotEmpty()) { "rememberArusTabs needs at least one tab." }
    require(tabs.distinctBy { it.id }.size == tabs.size) {
        "Tab ids must be unique, got: ${tabs.joinToString { it.id }}."
    }

    // One graph per tab, each with its own back stack. Built in a plain loop
    // because `rememberArusGraph` is composable and the number of tabs is
    // fixed for the lifetime of the composition.
    val graphs = LinkedHashMap<String, ArusGraph>(tabs.size)
    tabs.forEach { tab ->
        graphs[tab.id] = rememberArusGraph(tab.start, navigatorKey = tab.id, builder = builder)
    }

    // Saveable, not just remembered. The selected tab has to outlive the
    // composition for two reasons: process death, and — less obviously — a
    // dashboard route that leaves composition because something was pushed
    // over it on an outer host. Without this the user comes back to tab one.
    var savedTab by rememberSaveable { mutableStateOf(tabs.first().id) }

    val arusTabs =
        remember(graphs) {
            ArusTabs(
                tabs = tabs.toList(),
                graphs = graphs,
                initialTab = savedTab,
            )
        }

    LaunchedEffect(arusTabs) {
        arusTabs.selectedTab.collect { savedTab = it }
    }

    return arusTabs
}

/**
 * Renders the selected tab's stack.
 *
 * Only the selected tab composes; the others keep their state without doing
 * work, which is what makes switching cheap on a slow device.
 *
 * @param tabs produced by [rememberArusTabs].
 * @param modifier applied to the underlying host.
 * @param sceneStrategies extra strategies, as on [ArusHost].
 */
@Composable
public fun ArusTabHost(
    tabs: ArusTabs,
    modifier: Modifier = Modifier,
    sceneStrategies: List<SceneStrategy<Route>> = emptyList(),
) {
    val selected by tabs.selectedTab.collectAsStateWithLifecycle()
    val graph = tabs.graphs.getValue(selected)

    CompositionLocalProvider(LocalArusNavigator provides graph.navigator) {
        ArusHost(graph, modifier, sceneStrategies)
    }
}

/**
 * Whether the bottom navigation bar should be showing.
 *
 * `false` while the selected tab is on a destination declared with
 * `hidesTabBar = true` — a full-screen editor reached from Settings, say.
 * Arus answers the question; the app still draws its own bar:
 *
 * ```kotlin
 * Scaffold(
 *     bottomBar = {
 *         if (tabs.tabBarVisible()) {
 *             NavigationBar { tabs.all.forEach { ... } }
 *         }
 *     },
 * ) { padding -> ArusTabHost(tabs, Modifier.padding(padding)) }
 * ```
 *
 * Observes both the selected tab and that tab's current destination, so it
 * updates on a tab switch and on navigation within a tab.
 */
@Composable
public fun ArusTabs.tabBarVisible(): Boolean {
    val selected by selectedTab.collectAsStateWithLifecycle()
    val current by graphs
        .getValue(selected)
        .navigator.currentRoute
        .collectAsStateWithLifecycle()
    return !hidesTabBar(current)
}
