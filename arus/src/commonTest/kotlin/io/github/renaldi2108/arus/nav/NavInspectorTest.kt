package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data object InspectHomeRoute : Route

@Serializable
private data class InspectDetailRoute(
    val id: Int,
) : Route

@Serializable
private data object InspectOtherRoute : Route

private fun inspected(): Pair<ArusNavigator, NavInspector> {
    val nav = ArusNavigator(NavBackStack(InspectHomeRoute))
    val inspector = NavInspector()
    nav.inspector = inspector
    return nav to inspector
}

class NavInspectorTest {
    @Test
    fun everyOperationIsRecordedInOrder() {
        val (nav, inspector) = inspected()

        nav.push(InspectDetailRoute(1))
        nav.push(InspectOtherRoute)
        nav.replace(InspectDetailRoute(2))
        nav.pop()
        nav.push(InspectOtherRoute)
        nav.popTo(InspectHomeRoute)
        nav.push(InspectDetailRoute(3))
        nav.popToRoot()
        nav.resetTo(InspectOtherRoute)

        assertEquals(
            listOf(
                NavAction.Push,
                NavAction.Push,
                NavAction.Replace,
                NavAction.Pop,
                NavAction.Push,
                NavAction.PopTo,
                NavAction.Push,
                NavAction.PopToRoot,
                NavAction.ResetTo,
            ),
            inspector.events.map { it.action },
        )
    }

    @Test
    fun anEventCarriesTheRouteAndTheResultingStack() {
        val (nav, inspector) = inspected()

        nav.push(InspectDetailRoute(7))

        val event = inspector.events.single()
        assertEquals(InspectDetailRoute(7), event.route)
        assertEquals(listOf(InspectHomeRoute, InspectDetailRoute(7)), event.stack)
        assertEquals(InspectDetailRoute(7), event.currentRoute)
    }

    @Test
    fun operationsThatNameNoDestinationCarryNoRoute() {
        val (nav, inspector) = inspected()
        nav.push(InspectDetailRoute(1))

        nav.pop()

        assertNull(inspector.events.last().route)
    }

    @Test
    fun aRefusedOperationRecordsNothing() {
        val (nav, inspector) = inspected()

        // At the root: pop defers to the platform and the stack is untouched,
        // so there is no event to report.
        nav.pop()
        nav.popToRoot()
        nav.popTo(InspectOtherRoute)

        assertTrue(inspector.events.isEmpty())
    }

    @Test
    fun theStackInAnEventIsASnapshot() {
        val (nav, inspector) = inspected()

        nav.push(InspectDetailRoute(1))
        val captured = inspector.events.single().stack
        nav.push(InspectOtherRoute)

        // The navigator's own `entries` is a live view; a recorded event must
        // not be, or a log read after the fact would show the present rather
        // than the moment it describes.
        assertEquals(listOf(InspectHomeRoute, InspectDetailRoute(1)), captured)
    }

    @Test
    fun theLogIsCappedAndDropsTheOldest() {
        val nav = ArusNavigator(NavBackStack(InspectHomeRoute))
        val inspector = NavInspector(capacity = 3)
        nav.inspector = inspector

        repeat(10) { nav.push(InspectDetailRoute(it)) }

        assertEquals(3, inspector.events.size)
        assertEquals(
            listOf(InspectDetailRoute(7), InspectDetailRoute(8), InspectDetailRoute(9)),
            inspector.events.map { it.route },
        )
    }

    @Test
    fun onEventFiresAsNavigationHappens() {
        val (nav, inspector) = inspected()
        val seen = mutableListOf<String>()
        inspector.onEvent = { seen += it.action.name }

        nav.push(InspectDetailRoute(1))
        nav.pop()

        assertEquals(listOf("Push", "Pop"), seen)
    }

    @Test
    fun clearForgetsEverything() {
        val (nav, inspector) = inspected()
        nav.push(InspectDetailRoute(1))

        inspector.clear()

        assertTrue(inspector.events.isEmpty())
    }

    @Test
    fun detachingStopsRecording() {
        val (nav, inspector) = inspected()
        nav.push(InspectDetailRoute(1))

        nav.inspector = null
        nav.push(InspectOtherRoute)

        assertEquals(1, inspector.events.size)
    }

    @Test
    fun aNonPositiveCapacityIsRejectedByName() {
        val failure = assertFailsWith<IllegalArgumentException> { NavInspector(capacity = 0) }
        assertTrue(failure.message!!.contains("capacity must be positive"))
    }

    @Test
    fun anEventReadsLikeTheCallThatCausedIt() {
        val (nav, inspector) = inspected()

        nav.push(InspectDetailRoute(7))

        assertEquals(
            "push(InspectDetailRoute(id=7)) -> [InspectHomeRoute, InspectDetailRoute]",
            inspector.events.single().toString(),
        )
    }

    @Test
    fun anEventWithNoDestinationPrintsEmptyParentheses() {
        val (nav, inspector) = inspected()
        nav.push(InspectDetailRoute(1))

        nav.pop()

        // `pop` and `popToRoot` name no destination, so the rendering has to
        // cope with a null route rather than printing "null".
        assertEquals(
            "pop() -> [InspectHomeRoute]",
            inspector.events.last().toString(),
        )
    }

    @Test
    fun popToRootRendersTheSameWay() {
        val (nav, inspector) = inspected()
        nav.push(InspectDetailRoute(1))
        nav.push(InspectOtherRoute)

        nav.popToRoot()

        assertEquals(
            "popToRoot() -> [InspectHomeRoute]",
            inspector.events.last().toString(),
        )
    }
}
