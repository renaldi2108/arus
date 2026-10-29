package io.github.renaldi2108.arus.nav

/**
 * What a guard decided about a navigation.
 */
public sealed interface GuardDecision {
    /** Let it proceed. */
    public data object Allow : GuardDecision

    /**
     * Go somewhere else instead.
     *
     * @property route where to go.
     */
    public data class Redirect(
        val route: Route,
    ) : GuardDecision

    /** Do nothing; the user stays where they are. */
    public data object Block : GuardDecision
}

/**
 * Decides whether a navigation may happen.
 *
 * The usual case is an authentication wall:
 *
 * ```kotlin
 * val authGuard = NavGuard { _, to ->
 *     if (to is CheckoutRoute && !session.isSignedIn) {
 *         GuardDecision.Redirect(SignInRoute(then = to))
 *     } else {
 *         GuardDecision.Allow
 *     }
 * }
 * ```
 *
 * Guards see `push` and `replace` — the operations that move the user *forward*
 * into a destination. They deliberately do **not** see `pop`, `popTo`,
 * `popToRoot` or `resetTo`: a guard that can block going back can trap a user
 * in a screen with no way out, and that failure is far worse than the one it
 * would prevent.
 */
public fun interface NavGuard {
    /**
     * @param from where the user is now.
     * @param to where they are trying to go.
     */
    public fun check(
        from: Route,
        to: Route,
    ): GuardDecision
}

/**
 * Wraps a navigator so every forward navigation passes through [guards].
 *
 * ```kotlin
 * val navigator = rememberArusGraph(start = HomeRoute) { ... }
 *     .navigator
 *     .guardedBy(authGuard)
 * ```
 *
 * Guards run in order; the first that does not [GuardDecision.Allow] decides.
 * A [GuardDecision.Redirect] is itself guarded, so a redirect into another
 * guarded destination is caught — but a guard that redirects to a route it also
 * blocks would loop, so redirects are followed at most once and then allowed
 * through, with a debug log.
 */
public fun Navigator.guardedBy(vararg guards: NavGuard): Navigator {
    val fixed = guards.toList()
    return GuardedNavigator(this) { fixed }
}

/**
 * Guards this navigator with whatever [guards] returns at the time of the
 * call.
 *
 * The indirection exists so the guarded navigator is a **stable object** even
 * when the caller rebuilds its guard list — which a composable does on every
 * recomposition if it writes `guards = listOf(MyGuard { ... })` inline. Keying
 * the navigator on the list instead would rebuild the whole graph each time,
 * silently discarding anything attached to it.
 */
internal fun Navigator.guardedBy(guards: () -> List<NavGuard>): Navigator = GuardedNavigator(this, guards)

private class GuardedNavigator(
    private val delegate: Navigator,
    private val guards: () -> List<NavGuard>,
) : Navigator by delegate {
    override fun push(route: Route) {
        delegate.push(resolve(route) ?: return)
    }

    override fun replace(route: Route) {
        delegate.replace(resolve(route) ?: return)
    }

    /**
     * Applies the guards, returning where to actually go, or `null` to stay.
     *
     * Follows at most one redirect: a second pass that wants to redirect again
     * would mean two guards disagreeing, and looping between them would hang
     * the app rather than report the mistake.
     */
    private fun resolve(route: Route): Route? {
        val from = delegate.currentRoute.value

        return when (val first = decide(from, route)) {
            GuardDecision.Allow -> route
            GuardDecision.Block -> null
            is GuardDecision.Redirect ->
                when (decide(from, first.route)) {
                    GuardDecision.Block -> null
                    // Allow, or a second redirect that is deliberately not
                    // followed — see the doc on this function.
                    else -> first.route
                }
        }
    }

    private fun decide(
        from: Route,
        to: Route,
    ): GuardDecision {
        guards().forEach { guard ->
            val decision = guard.check(from, to)
            if (decision != GuardDecision.Allow) return decision
        }
        return GuardDecision.Allow
    }
}
