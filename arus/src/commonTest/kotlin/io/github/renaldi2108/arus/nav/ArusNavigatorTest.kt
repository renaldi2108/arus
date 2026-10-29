package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data object HomeRoute : Route

@Serializable
private data class ProfileRoute(
    val userId: String,
) : Route

@Serializable
private data object SettingsRoute : Route

private fun navigatorOf(vararg routes: Route): ArusNavigator = ArusNavigator(NavBackStack(*routes))

class ArusNavigatorTest {
    @Test
    fun startsAtTheGivenRoute() {
        val nav = navigatorOf(HomeRoute)

        assertEquals(HomeRoute, nav.currentRoute.value)
        assertFalse(nav.canPop.value, "a single-entry stack has nothing to pop")
    }

    @Test
    fun pushAddsOnTop() {
        val nav = navigatorOf(HomeRoute)

        nav.push(ProfileRoute("u1"))

        assertEquals(listOf(HomeRoute, ProfileRoute("u1")), nav.entries)
        assertEquals(ProfileRoute("u1"), nav.currentRoute.value)
        assertTrue(nav.canPop.value)
    }

    @Test
    fun popRemovesTopAndReportsTrue() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"))

        assertTrue(nav.pop())

        assertEquals(listOf(HomeRoute), nav.entries)
        assertEquals(HomeRoute, nav.currentRoute.value)
    }

    @Test
    fun popAtRootLeavesStackIntactAndReportsFalse() {
        val nav = navigatorOf(HomeRoute)

        assertFalse(nav.pop(), "the host must be able to defer to the platform")

        assertEquals(listOf(HomeRoute), nav.entries)
    }

    @Test
    fun replaceSwapsOnlyTheTopEntry() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"))

        nav.replace(SettingsRoute)

        assertEquals(listOf(HomeRoute, SettingsRoute), nav.entries)
    }

    @Test
    fun resetToClearsTheWholeStack() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"), SettingsRoute)

        nav.resetTo(HomeRoute)

        assertEquals(listOf(HomeRoute), nav.entries)
        assertFalse(nav.canPop.value)
    }

    @Test
    fun popToRemovesDownToTheTargetExclusively() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"), SettingsRoute)

        assertTrue(nav.popTo(ProfileRoute("u1")))

        assertEquals(listOf(HomeRoute, ProfileRoute("u1")), nav.entries)
    }

    @Test
    fun popToInclusiveRemovesTheTargetAsWell() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"), SettingsRoute)

        assertTrue(nav.popTo(ProfileRoute("u1"), inclusive = true))

        assertEquals(listOf(HomeRoute), nav.entries)
    }

    @Test
    fun popToRefusesToEmptyTheStack() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"))

        assertFalse(nav.popTo(HomeRoute, inclusive = true))

        assertEquals(listOf(HomeRoute, ProfileRoute("u1")), nav.entries)
    }

    @Test
    fun popToIgnoresAnAbsentRoute() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"))

        assertFalse(nav.popTo(SettingsRoute))

        assertEquals(listOf(HomeRoute, ProfileRoute("u1")), nav.entries)
    }

    @Test
    fun popToRootKeepsOnlyTheFirstEntry() {
        val nav = navigatorOf(HomeRoute, ProfileRoute("u1"), SettingsRoute)

        nav.popToRoot()

        assertEquals(listOf(HomeRoute), nav.entries)
        assertEquals(HomeRoute, nav.currentRoute.value)
    }

    @Test
    fun navigationAppliesWithoutAnyUiAttached() {
        // No composition exists in this test at all; the stack still mutates,
        // which is what makes navigation from a backgrounded controller safe.
        val nav = navigatorOf(HomeRoute)

        nav.push(ProfileRoute("u1"))
        nav.push(SettingsRoute)

        assertEquals(3, nav.entries.size)
        assertEquals(SettingsRoute, nav.currentRoute.value)
    }
}
