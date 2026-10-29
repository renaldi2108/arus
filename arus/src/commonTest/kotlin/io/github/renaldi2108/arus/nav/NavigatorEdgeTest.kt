package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import io.github.renaldi2108.arus.core.Arus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data object EdgeHomeRoute : Route

@Serializable
private data object EdgeNextRoute : Route

@Serializable
private data object EdgePickRoute : ResultRoute<String>

/**
 * The paths a happy-path test never reaches: bad construction, re-pointing at
 * a restored stack, and the branches that only run with logging on.
 */
class NavigatorEdgeTest {
    @AfterTest
    fun quiet() {
        Arus.debugLogging = false
    }

    @Test
    fun anEmptyBackStackIsRejectedByName() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ArusNavigator(NavBackStack<Route>())
            }

        // A navigator with nothing on it has no current route, so every
        // reader would get an exception later and further from the cause.
        assertTrue(failure.message.orEmpty().contains("non-empty"), failure.message.orEmpty())
    }

    @Test
    fun rebindPointsAtTheRestoredStackAndRepublishes() {
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute))

        // What a configuration change produces: the same navigator, a new
        // stack instance carrying the restored entries.
        nav.rebind(NavBackStack(EdgeHomeRoute, EdgeNextRoute))

        assertEquals(EdgeNextRoute, nav.currentRoute.value)
        assertTrue(nav.canPop.value)
        assertEquals(listOf(EdgeHomeRoute, EdgeNextRoute), nav.entries)
    }

    @Test
    fun rebindingToTheSameStackChangesNothing() {
        val stack = NavBackStack<Route>(EdgeHomeRoute)
        val nav = ArusNavigator(stack)
        nav.push(EdgeNextRoute)

        nav.rebind(stack)

        // Recomposition hands back the same instance constantly; treating
        // that as a restore would republish on every frame.
        assertEquals(EdgeNextRoute, nav.currentRoute.value)
        assertEquals(2, nav.entries.size)
    }

    @Test
    fun rebindingToASingleEntryStackClearsCanPop() {
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute, EdgeNextRoute))
        assertTrue(nav.canPop.value)

        nav.rebind(NavBackStack(EdgeHomeRoute))

        assertFalse(nav.canPop.value)
        assertEquals(EdgeHomeRoute, nav.currentRoute.value)
    }

    @Test
    fun navigatingWithLoggingOnTakesTheLoggingBranch() {
        Arus.debugLogging = true
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute))

        // No inspector attached: this is the path where the event is built
        // for the log alone, which the release path skips entirely.
        nav.push(EdgeNextRoute)
        nav.pop()
        nav.popToRoot()

        assertEquals(listOf(EdgeHomeRoute), nav.entries)
    }

    @Test
    fun aRefusedPopWithLoggingOnStillReturnsFalse() {
        Arus.debugLogging = true
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute))

        assertFalse(nav.pop())
    }

    @Test
    fun popWithResultWithNobodyAwaitingLogsAndStillPops() {
        Arus.debugLogging = true
        // What a restored stack looks like: the destination is back, the
        // coroutine that asked for its answer is not.
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute, EdgePickRoute))

        nav.popWithResult("ID")

        assertEquals(listOf(EdgeHomeRoute), nav.entries)
    }

    @Test
    fun aRefusedPopToWithLoggingOnIsReported() {
        Arus.debugLogging = true
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute))

        assertFalse(nav.popTo(EdgeNextRoute))
        assertFalse(nav.popTo(EdgeHomeRoute, inclusive = true))
    }

    @Test
    fun resetToCancelsAnOrphanedRequest() {
        val nav = ArusNavigator(NavBackStack<Route>(EdgeHomeRoute))
        var answered: NavResult<String>? = null

        CoroutineScope(Dispatchers.Unconfined).async {
            answered = nav.pushForResult(EdgePickRoute)
        }

        // resetTo removes the destination without popping it, so the sweep is
        // the only thing that can release the caller.
        nav.resetTo(EdgeHomeRoute)

        assertEquals(NavResult.Cancelled, answered)
    }
}
