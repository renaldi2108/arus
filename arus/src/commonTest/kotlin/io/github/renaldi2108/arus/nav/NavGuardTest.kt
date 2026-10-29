package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data object GuardHome : Route

@Serializable
private data object GuardCheckout : Route

@Serializable
private data object GuardSignIn : Route

@Serializable
private data object GuardOther : Route

private fun navigatorOf(vararg routes: Route) = ArusNavigator(NavBackStack(*routes))

class NavGuardTest {
    @Test
    fun anAllowingGuardChangesNothing() {
        val nav = navigatorOf(GuardHome).guardedBy(NavGuard { _, _ -> GuardDecision.Allow })

        nav.push(GuardCheckout)

        assertEquals(GuardCheckout, nav.currentRoute.value)
    }

    @Test
    fun aBlockingGuardLeavesTheUserWhereTheyWere() {
        val nav =
            navigatorOf(GuardHome).guardedBy(
                NavGuard { _, to -> if (to == GuardCheckout) GuardDecision.Block else GuardDecision.Allow },
            )

        nav.push(GuardCheckout)

        assertEquals(GuardHome, nav.currentRoute.value)
    }

    @Test
    fun aRedirectSendsThemSomewhereElse() {
        val nav =
            navigatorOf(GuardHome).guardedBy(
                NavGuard { _, to ->
                    if (to == GuardCheckout) GuardDecision.Redirect(GuardSignIn) else GuardDecision.Allow
                },
            )

        nav.push(GuardCheckout)

        assertEquals(GuardSignIn, nav.currentRoute.value)
    }

    @Test
    fun guardsAlsoCoverReplace() {
        val nav =
            navigatorOf(GuardHome, GuardOther).guardedBy(
                NavGuard { _, to -> if (to == GuardCheckout) GuardDecision.Block else GuardDecision.Allow },
            )

        nav.replace(GuardCheckout)

        assertEquals(GuardOther, nav.currentRoute.value, "a blocked replace must not swap the top")
    }

    @Test
    fun guardsDoNotApplyToGoingBack() {
        // A guard that could block `pop` would be able to trap a user in a
        // screen with no way out. Backward navigation is deliberately exempt.
        val nav =
            navigatorOf(GuardHome, GuardCheckout).guardedBy(
                NavGuard { _, _ -> GuardDecision.Block },
            )

        nav.pop()
        assertEquals(GuardHome, nav.currentRoute.value)

        nav.push(GuardOther)
        nav.popToRoot()
        assertEquals(GuardHome, nav.currentRoute.value)
    }

    @Test
    fun theFirstNonAllowingGuardDecides() {
        val nav =
            navigatorOf(GuardHome).guardedBy(
                NavGuard { _, _ -> GuardDecision.Redirect(GuardSignIn) },
                NavGuard { _, _ -> GuardDecision.Block },
            )

        nav.push(GuardCheckout)

        assertEquals(GuardSignIn, nav.currentRoute.value, "the first guard's decision wins")
    }

    @Test
    fun aRedirectIsItselfGuarded() {
        val nav =
            navigatorOf(GuardHome).guardedBy(
                NavGuard { _, to ->
                    when (to) {
                        GuardCheckout -> GuardDecision.Redirect(GuardSignIn)
                        GuardSignIn -> GuardDecision.Block
                        else -> GuardDecision.Allow
                    }
                },
            )

        nav.push(GuardCheckout)

        assertEquals(GuardHome, nav.currentRoute.value, "a blocked redirect must not navigate")
    }

    @Test
    fun aSecondRedirectIsNotFollowed() {
        // Two guards redirecting at each other would loop forever. The second
        // redirect is taken as final instead of hanging the app.
        val nav =
            navigatorOf(GuardHome).guardedBy(
                NavGuard { _, to ->
                    when (to) {
                        GuardCheckout -> GuardDecision.Redirect(GuardSignIn)
                        GuardSignIn -> GuardDecision.Redirect(GuardCheckout)
                        else -> GuardDecision.Allow
                    }
                },
            )

        nav.push(GuardCheckout)

        assertEquals(GuardSignIn, nav.currentRoute.value)
    }
}
