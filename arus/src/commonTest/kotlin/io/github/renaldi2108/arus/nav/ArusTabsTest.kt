package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data object TabHomeRoute : Route

@Serializable
private data object TabOrdersRoute : Route

@Serializable
private data class TabDetailRoute(
    val id: Int,
) : Route

/**
 * Builds [ArusTabs] without composition, so the stack behaviour can be tested
 * on the JVM. The composable factory only adds `remember` around this.
 */
private fun tabsOf(
    vararg tabs: ArusTab,
    hiding: Set<kotlin.reflect.KClass<out Route>> = emptySet(),
): ArusTabs {
    val graphs =
        tabs.associate { tab ->
            val stack = ArusNavigator(NavBackStack(tab.start))
            tab.id to ArusGraph(stack, stack, { emptyList() }, hiding)
        }
    return ArusTabs(tabs.toList(), graphs, tabs.first().id)
}

private fun homeAndOrders(hiding: Set<kotlin.reflect.KClass<out Route>> = emptySet()) =
    tabsOf(
        ArusTab("home", TabHomeRoute),
        ArusTab("orders", TabOrdersRoute),
        hiding = hiding,
    )

class ArusTabsTest {
    @Test
    fun startsOnTheFirstTab() {
        val tabs = homeAndOrders()

        assertEquals("home", tabs.selectedTab.value)
        assertEquals(TabHomeRoute, tabs.navigator.currentRoute.value)
    }

    @Test
    fun eachTabKeepsItsOwnHistory() {
        val tabs = homeAndOrders()

        tabs.navigator.push(TabDetailRoute(1))
        tabs.select("orders")

        // Orders is untouched by what happened in home.
        assertEquals(TabOrdersRoute, tabs.navigator.currentRoute.value)
        assertFalse(tabs.navigator.canPop.value)

        tabs.select("home")

        // Returning to home lands where the user left, not at its root.
        assertEquals(TabDetailRoute(1), tabs.navigator.currentRoute.value)
    }

    @Test
    fun reselectingTheCurrentTabResetsItToItsRoot() {
        val tabs = homeAndOrders()
        tabs.navigator.push(TabDetailRoute(1))
        tabs.navigator.push(TabDetailRoute(2))

        tabs.select("home")

        assertEquals(TabHomeRoute, tabs.navigator.currentRoute.value)
        assertFalse(tabs.navigator.canPop.value)
    }

    @Test
    fun popWorksWithinTheCurrentTabFirst() {
        val tabs = homeAndOrders()
        tabs.navigator.push(TabDetailRoute(1))

        assertTrue(tabs.pop())
        assertEquals(TabHomeRoute, tabs.navigator.currentRoute.value)
        assertEquals("home", tabs.selectedTab.value)
    }

    @Test
    fun popAtASecondaryTabRootReturnsToTheFirstTab() {
        val tabs = homeAndOrders()
        tabs.select("orders")

        assertTrue(tabs.pop())

        assertEquals("home", tabs.selectedTab.value)
    }

    @Test
    fun popAtTheFirstTabRootDefersToThePlatform() {
        val tabs = homeAndOrders()

        // Nothing left to pop anywhere: the host must let the Activity finish.
        assertFalse(tabs.pop())
    }

    @Test
    fun returningToTheFirstTabPreservesItsHistory() {
        val tabs = homeAndOrders()
        tabs.navigator.push(TabDetailRoute(9))
        tabs.select("orders")

        tabs.pop()

        assertEquals("home", tabs.selectedTab.value)
        assertEquals(TabDetailRoute(9), tabs.navigator.currentRoute.value)
    }

    @Test
    fun selectingAnUnknownTabFailsWithTheDeclaredIds() {
        val tabs = homeAndOrders()

        val failure = assertFailsWith<IllegalArgumentException> { tabs.select("profile") }

        assertTrue(failure.message.orEmpty().contains("home"))
        assertTrue(failure.message.orEmpty().contains("orders"))
    }

    @Test
    fun aDestinationDeclaredFullScreenHidesTheBar() {
        val tabs = homeAndOrders(hiding = setOf(TabDetailRoute::class))

        assertTrue(tabs.hidesTabBar(TabDetailRoute(1)))
        assertFalse(tabs.hidesTabBar(TabHomeRoute))
    }

    @Test
    fun byDefaultNothingHidesTheBar() {
        val tabs = homeAndOrders()

        // A pushed destination belongs to the tab it was reached from, so the
        // bar stays unless the declaration says otherwise.
        assertFalse(tabs.hidesTabBar(TabDetailRoute(1)))
        assertFalse(tabs.hidesTabBar(TabHomeRoute))
    }
}
