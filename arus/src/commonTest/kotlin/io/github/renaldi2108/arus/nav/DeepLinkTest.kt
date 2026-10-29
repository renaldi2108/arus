package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data object LinkListRoute : Route

@Serializable
private data class LinkDetailRoute(
    val itemId: Int,
) : Route

@Serializable
private data object LinkSettingsRoute : Route

private val links =
    ArusDeepLinks {
        path("catalog/item/{id}") { args -> listOf(LinkListRoute, LinkDetailRoute(args.int("id"))) }
        path("catalog") { listOf(LinkListRoute) }
        path("settings") { listOf(LinkListRoute, LinkSettingsRoute) }
    }

class DeepLinkTest {
    @Test
    fun resolvesASyntheticBackStackRatherThanASingleScreen() {
        // The point of deep links: back must lead into the app, not out of it.
        assertEquals(
            listOf(LinkListRoute, LinkDetailRoute(42)),
            links.resolve("arus://catalog/item/42"),
        )
    }

    @Test
    fun matchesWithoutASchemeToo() {
        assertEquals(listOf(LinkListRoute), links.resolve("catalog"))
    }

    @Test
    fun ignoresAQueryString() {
        assertEquals(
            listOf(LinkListRoute, LinkDetailRoute(7)),
            links.resolve("arus://catalog/item/7?utm_source=email"),
        )
    }

    @Test
    fun anUnmatchedLinkResolvesToNothing() {
        assertTrue(links.resolve("arus://unknown/path").isEmpty())
        assertTrue(links.resolve("arus://catalog/item").isEmpty())
        assertTrue(links.resolve("arus://catalog/item/1/extra").isEmpty())
    }

    @Test
    fun patternsAreTriedInDeclarationOrder() {
        val ordered =
            ArusDeepLinks {
                path("catalog/{id}") { listOf(LinkListRoute) }
                path("catalog/special") { listOf(LinkSettingsRoute) }
            }

        // The general pattern was declared first, so it wins — documented
        // behaviour, and the reason specific patterns go first.
        assertEquals(listOf(LinkListRoute), ordered.resolve("catalog/special"))
    }

    @Test
    fun aNonNumericArgumentFailsWithTheValue() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                links.resolve("arus://catalog/item/abc")
            }

        assertTrue(failure.message.orEmpty().contains("abc"))
    }

    @Test
    fun openStackInstallsTheWholeHistory() {
        val nav = ArusNavigator(NavBackStack(LinkSettingsRoute))

        nav.openStack(links.resolve("arus://catalog/item/9"))

        assertEquals(listOf(LinkListRoute, LinkDetailRoute(9)), nav.entries)
        assertTrue(nav.canPop.value, "back must lead into the app")
    }

    @Test
    fun openStackIgnoresAnEmptyResolution() {
        val nav = ArusNavigator(NavBackStack(LinkListRoute))

        nav.openStack(links.resolve("arus://nope"))

        assertEquals(listOf(LinkListRoute), nav.entries, "an unknown link must not move the user")
    }

    @Test
    fun stringOrNullIsTheNonThrowingRead() {
        val links =
            ArusDeepLinks {
                path("catalog/item/{id}") { args ->
                    assertNull(args.stringOrNull("missing"))
                    assertEquals("7", args.stringOrNull("id"))
                    listOf(LinkListRoute)
                }
            }

        links.resolve("arus://catalog/item/7")
    }

    @Test
    fun askingForAnArgumentThePatternNeverCapturedNamesIt() {
        val links =
            ArusDeepLinks {
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("itemId"))) }
            }

        val failure =
            assertFailsWith<IllegalArgumentException> { links.resolve("arus://catalog/item/7") }

        // Names both what was captured and what was asked for, because the
        // mistake is in the pattern and the author needs to see the mismatch.
        assertTrue(failure.message.orEmpty().contains("itemId"), failure.message.orEmpty())
        assertTrue(failure.message.orEmpty().contains("id"), failure.message.orEmpty())
    }

    @Test
    fun aNonNumericArgumentForIntIsRejectedByName() {
        val links =
            ArusDeepLinks {
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("id"))) }
            }

        val failure =
            assertFailsWith<IllegalArgumentException> { links.resolve("arus://catalog/item/abc") }

        assertTrue(failure.message.orEmpty().contains("abc"), failure.message.orEmpty())
    }

    @Test
    fun aWebUrlMatchesWhenItsHostIsDeclared() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args -> listOf(LinkListRoute, LinkDetailRoute(args.int("id"))) }
            }

        assertEquals(
            listOf(LinkListRoute, LinkDetailRoute(3)),
            links.resolve("https://shop.example.com/catalog/item/3"),
        )
    }

    @Test
    fun aHostIsMatchedIgnoringCaseAndPort() {
        val links =
            ArusDeepLinks {
                host("Shop.Example.COM")
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("id"))) }
            }

        assertEquals(listOf(LinkDetailRoute(3)), links.resolve("https://shop.example.com/catalog/item/3"))
        assertEquals(listOf(LinkDetailRoute(3)), links.resolve("http://SHOP.example.com:8080/catalog/item/3"))
    }

    @Test
    fun aHostYouDidNotDeclareDoesNotResolve() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("id"))) }
            }

        // Not stripped, so it stays a path segment and nothing matches. A
        // link from somebody else's domain must not open your app's screen.
        assertEquals(emptyList(), links.resolve("https://evil.example.net/catalog/item/3"))
    }

    @Test
    fun aCustomSchemeStillWorksAlongsideDeclaredHosts() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("id"))) }
            }

        // `catalog` sits where a host would, but it is not a declared host,
        // so it stays part of the path.
        assertEquals(listOf(LinkDetailRoute(3)), links.resolve("arus://catalog/item/3"))
    }

    @Test
    fun queryParametersAreReadableByName() {
        var seenRef: String? = null
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args ->
                    seenRef = args.stringOrNull("ref")
                    listOf(LinkDetailRoute(args.int("id")))
                }
            }

        links.resolve("https://shop.example.com/catalog/item/3?ref=email&utm=spring")

        assertEquals("email", seenRef)
    }

    @Test
    fun aPathPlaceholderBeatsAQueryParameterOfTheSameName() {
        var seenId: Int? = null
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args ->
                    seenId = args.int("id")
                    listOf(LinkListRoute)
                }
            }

        // Otherwise a crafted query string could redirect a legitimate link.
        links.resolve("https://shop.example.com/catalog/item/3?id=999")

        assertEquals(3, seenId)
    }

    @Test
    fun percentEscapesAreDecodedInPathAndQuery() {
        var slug: String? = null
        var note: String? = null
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/{slug}") { args ->
                    slug = args.string("slug")
                    note = args.stringOrNull("note")
                    listOf(LinkListRoute)
                }
            }

        links.resolve("https://shop.example.com/catalog/meja%20kayu?note=hari+ini%20jadi")

        // `+` is a space in a query and a literal plus in a path, so the two
        // are decoded by different rules.
        assertEquals("meja kayu", slug)
        assertEquals("hari ini jadi", note)
    }

    @Test
    fun aMultiByteCharacterSurvivesDecoding() {
        var slug: String? = null
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/{slug}") { args ->
                    slug = args.string("slug")
                    listOf(LinkListRoute)
                }
            }

        // é is two bytes in UTF-8; decoding byte by byte into chars would
        // produce mojibake rather than the letter.
        links.resolve("https://shop.example.com/catalog/caf%C3%A9")

        assertEquals("café", slug)
    }

    @Test
    fun aFragmentIsIgnored() {
        val links =
            ArusDeepLinks {
                host("shop.example.com")
                path("catalog/item/{id}") { args -> listOf(LinkDetailRoute(args.int("id"))) }
            }

        assertEquals(listOf(LinkDetailRoute(3)), links.resolve("https://shop.example.com/catalog/item/3#reviews"))
    }
}
