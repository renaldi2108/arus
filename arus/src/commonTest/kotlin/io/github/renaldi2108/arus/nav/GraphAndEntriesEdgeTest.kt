package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import io.github.renaldi2108.arus.core.Arus
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Serializable
private data object GraphHomeRoute : Route

@Serializable
private data object GraphEditorRoute : Route

/**
 * The parts of `ArusGraph` that are plain object members rather than
 * composition, and the `screen` overload that carries a tab-bar flag.
 */
class GraphAndEntriesEdgeTest {
    @AfterTest
    fun quiet() {
        Arus.debugLogging = false
    }

    private fun graphOf(vararg routes: Route): Pair<ArusGraph, ArusNavigator> {
        val stack = ArusNavigator(NavBackStack(*routes))
        return ArusGraph(stack, stack, { emptyList() }) to stack
    }

    @Test
    fun theGraphExposesWhateverItsProviderReturns() {
        val stack = ArusNavigator(NavBackStack<Route>(GraphHomeRoute))
        var handed: List<androidx.navigation3.runtime.NavEntry<Route>> = emptyList()
        val graph = ArusGraph(stack, stack, { handed })

        assertEquals(0, graph.decoratedEntries.size)

        // A provider rather than a stored list, so the host always renders
        // what the latest composition decorated.
        handed = emptyList()
        assertEquals(0, graph.decoratedEntries.size)
    }

    @Test
    fun theGraphsInspectorIsTheStacksInspector() {
        val (graph, stack) = graphOf(GraphHomeRoute)
        assertNull(graph.inspector)

        val inspector = NavInspector()
        graph.inspector = inspector

        // Set through the graph, read on the navigator: the graph is a
        // pass-through so an app never has to reach for the raw stack.
        assertSame(inspector, stack.inspector)
        assertSame(inspector, graph.inspector)

        stack.push(GraphEditorRoute)
        assertEquals(1, inspector.events.size)
    }

    @Test
    fun clearingTheGraphsInspectorStopsRecording() {
        val (graph, stack) = graphOf(GraphHomeRoute)
        val inspector = NavInspector()
        graph.inspector = inspector
        stack.push(GraphEditorRoute)

        graph.inspector = null
        stack.push(GraphEditorRoute)

        assertNull(stack.inspector)
        assertEquals(1, inspector.events.size)
    }

    @Test
    fun aFullScreenDeclarationRecordsTheFlagAndItsTransitions() {
        val entries =
            ArusEntries().apply {
                screen<GraphHomeRoute> { }
                screen<GraphEditorRoute>(
                    hidesTabBar = true,
                    transition = { null },
                    popTransition = { null },
                ) { }
            }

        val editor = entries.screens.first { it.kClass == GraphEditorRoute::class }
        assertTrue(editor.hidesTabBar)
        // The transitions travel as entry metadata, exactly as they do from
        // the overload without the flag.
        assertEquals(
            androidx.navigation3.ui.NavDisplay
                .transitionSpec { null }
                .keys +
                androidx.navigation3.ui.NavDisplay
                    .popTransitionSpec { null }
                    .keys,
            editor.metadata.keys,
        )
        assertFalse(entries.screens.first { it.kClass == GraphHomeRoute::class }.hidesTabBar)
    }

    @Test
    fun aFullScreenDeclarationWithoutTransitionsCarriesNoMetadata() {
        val entries =
            ArusEntries().apply {
                screen<GraphEditorRoute>(hidesTabBar = true) { }
            }

        // Nothing to override, so the host keeps its own animation.
        assertTrue(
            entries.screens
                .single()
                .metadata
                .isEmpty(),
        )
        assertTrue(entries.screens.single().hidesTabBar)
    }

    @Test
    fun anEnterTransitionWithoutAPopTransitionRecordsOnlyTheOne() {
        val entries =
            ArusEntries().apply {
                screen<GraphEditorRoute>(hidesTabBar = false, transition = { null }) { }
            }

        assertEquals(
            androidx.navigation3.ui.NavDisplay
                .transitionSpec { null }
                .keys,
            entries.screens
                .single()
                .metadata.keys,
        )
    }

    @Test
    fun tabsExposeTheirDeclarationOrder() {
        val a = ArusTab("home", GraphHomeRoute)
        val b = ArusTab("editor", GraphEditorRoute)
        val graphs =
            listOf(a, b).associate { tab ->
                val stack = ArusNavigator(NavBackStack(tab.start))
                tab.id to ArusGraph(stack, stack, { emptyList() })
            }

        val tabs = ArusTabs(listOf(a, b), graphs, a.id)

        // Bar order is declaration order, and the first tab is where back
        // lands from any other, so the order is load-bearing.
        assertEquals(listOf("home", "editor"), tabs.all.map { it.id })
    }
}
