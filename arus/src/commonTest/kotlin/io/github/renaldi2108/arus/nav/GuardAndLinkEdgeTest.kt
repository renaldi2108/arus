package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data object EdgeListRoute : Route

@Serializable
private data object EdgeLockedRoute : Route

@Serializable
private data object EdgeSignInRoute : Route

/** A guard written as an app writes one: a class holding a dependency. */
private class LockUnless(
    private val unlocked: () -> Boolean,
) : NavGuard {
    override fun check(
        from: Route,
        to: Route,
    ): GuardDecision =
        when {
            to != EdgeLockedRoute -> GuardDecision.Allow
            unlocked() -> GuardDecision.Allow
            else -> GuardDecision.Redirect(EdgeSignInRoute)
        }
}

class GuardAndLinkEdgeTest {
    @Test
    fun aGuardAppliesToReplaceAsWellAsPush() {
        val raw = ArusNavigator(NavBackStack<Route>(EdgeListRoute))
        val guarded = raw.guardedBy(LockUnless { false })

        guarded.replace(EdgeLockedRoute)

        // `replace` is forward navigation too. A guard that only saw `push`
        // would be sidestepped by swapping the top entry instead.
        assertEquals(listOf(EdgeSignInRoute), raw.entries)
    }

    @Test
    fun aBlockedReplaceLeavesTheStackAlone() {
        val raw = ArusNavigator(NavBackStack<Route>(EdgeListRoute))
        val guarded = raw.guardedBy(NavGuard { _, _ -> GuardDecision.Block })

        guarded.replace(EdgeLockedRoute)

        assertEquals(listOf(EdgeListRoute), raw.entries)
    }

    @Test
    fun theProviderFormConsultsTheListAtCallTime() {
        val raw = ArusNavigator(NavBackStack<Route>(EdgeListRoute))
        var guards = listOf<NavGuard>(LockUnless { false })
        val guarded = raw.guardedBy { guards }

        guarded.push(EdgeLockedRoute)
        assertEquals(EdgeSignInRoute, raw.currentRoute.value)

        // The same navigator, a different rule: this is what lets the graph
        // stay a stable object while a caller rebuilds its guard list.
        guards = emptyList()
        guarded.push(EdgeLockedRoute)
        assertEquals(EdgeLockedRoute, raw.currentRoute.value)
    }

    @Test
    fun anEmptyUriResolvesToNothing() {
        val links =
            ArusDeepLinks {
                path("catalog") { _ -> listOf(EdgeListRoute) }
            }

        // A pattern with one segment must not match a path with none.
        assertEquals(emptyList(), links.resolve(""))
        assertEquals(emptyList(), links.resolve("arus://"))
        assertEquals(emptyList(), links.resolve("/"))
    }

    @Test
    fun aTrailingSeparatorDoesNotAddASegment() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog") { _ -> listOf(EdgeListRoute) }
            }

        assertEquals(listOf(EdgeListRoute), links.resolve("https://shop.example.com/catalog/"))
        assertEquals(listOf(EdgeListRoute), links.resolve("arus://catalog//"))
    }

    @Test
    fun anEmptyQueryStringIsNotAnArgument() {
        var captured: String? = "unset"
        val links =
            ArusDeepLinks {
                path("catalog") { args ->
                    captured = args.stringOrNull("ref")
                    listOf(EdgeListRoute)
                }
            }

        links.resolve("arus://catalog?")

        assertEquals(null, captured)
    }

    @Test
    fun aQueryPairWithNoValueReadsAsEmpty() {
        var captured: String? = null
        val links =
            ArusDeepLinks {
                path("catalog") { args ->
                    captured = args.stringOrNull("ref")
                    listOf(EdgeListRoute)
                }
            }

        links.resolve("arus://catalog?ref=&utm=x")

        // Present but empty is different from absent, and a caller may act on
        // the difference.
        assertEquals("", captured)
    }

    @Test
    fun aHostOnlyUriWithNoPathMatchesNothing() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog") { _ -> listOf(EdgeListRoute) }
            }

        assertTrue(links.resolve("https://shop.example.com").isEmpty())
        assertTrue(links.resolve("https://shop.example.com/").isEmpty())
    }

    @Test
    fun aPatternWithADoubleSeparatorIsNormalised() {
        val links =
            ArusDeepLinks {
                path("catalog//item/{id}") { args -> listOf(EdgeListRoute, EdgeLockedRoute) }
            }

        // Empty segments are dropped on both sides, so a slip in the pattern
        // does not silently stop matching every URI.
        assertEquals(listOf(EdgeListRoute, EdgeLockedRoute), links.resolve("arus://catalog/item/3"))
    }

    @Test
    fun anEmptyQueryPairIsSkipped() {
        var ref: String? = null
        val links =
            ArusDeepLinks {
                path("catalog") { args ->
                    ref = args.stringOrNull("ref")
                    listOf(EdgeListRoute)
                }
            }

        links.resolve("arus://catalog?&ref=email&&")

        assertEquals("email", ref)
    }
}
