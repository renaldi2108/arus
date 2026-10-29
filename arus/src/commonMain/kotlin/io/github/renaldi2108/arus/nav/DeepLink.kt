package io.github.renaldi2108.arus.nav

/**
 * Turns an incoming link into the stack the user should land on.
 *
 * A deep link rarely means "show one screen". Opening a product from a
 * notification should leave the user able to press back into the catalogue, not
 * out of the app — so a resolver returns a **list**: the synthetic history
 * leading to the destination.
 *
 * ```kotlin
 * val resolver = ArusDeepLinks {
 *     // arus://catalog/item/42
 *     path("catalog/item/{id}") { args -> listOf(ListRoute, DetailRoute(args.int("id"))) }
 *     path("settings") { listOf(ListRoute, SettingsRoute) }
 * }
 * ```
 *
 * Unmatched links return an empty list, which callers treat as "ignore" rather
 * than navigating somewhere arbitrary.
 */
public class ArusDeepLinks
    @PublishedApi
    internal constructor(
        @PublishedApi internal val patterns: List<DeepLinkPattern>,
        @PublishedApi internal val hosts: Set<String> = emptySet(),
    ) {
        /**
         * Resolves [uri] to a back stack.
         *
         * @return the entries to install, oldest first, or an empty list when
         *   nothing matches.
         */
        public fun resolve(uri: String): List<Route> {
            val link = ParsedLink.of(uri, hosts)
            patterns.forEach { pattern ->
                val args = pattern.match(link)
                if (args != null) return pattern.build(args)
            }
            return emptyList()
        }
    }

/** Builds an [ArusDeepLinks] resolver. */
public fun ArusDeepLinks(builder: DeepLinkBuilder.() -> Unit): ArusDeepLinks {
    val declared = DeepLinkBuilder().apply(builder)
    return ArusDeepLinks(declared.patterns, declared.hosts)
}

/** Collects deep-link patterns. */
public class DeepLinkBuilder
    @PublishedApi
    internal constructor() {
        @PublishedApi
        internal val patterns: MutableList<DeepLinkPattern> = mutableListOf()

        @PublishedApi
        internal val hosts: MutableSet<String> = mutableSetOf()

        /**
         * Declares a web host this app answers for, as in an App Link.
         *
         * ```kotlin
         * ArusDeepLinks {
         *     host("shop.example.com")
         *     path("catalog/item/{id}") { args -> … }
         * }
         * ```
         *
         * `https://shop.example.com/catalog/item/3` then matches the pattern
         * `catalog/item/{id}` — the host is recognised and dropped rather
         * than counted as a path segment.
         *
         * **A host you did not declare is not stripped**, so a link from
         * somewhere else simply fails to match instead of resolving as if it
         * were yours. That is also what keeps a custom scheme working:
         * `arus://catalog/item/3` has `catalog` where a host would sit, and
         * since `catalog` is not a declared host it stays part of the path.
         *
         * Matched case-insensitively, because host names are.
         *
         * @param name the authority, without scheme or path. A port, if the
         *   URI carries one, is ignored when matching.
         */
        public fun host(name: String) {
            hosts += name.trim().lowercase()
        }

        /**
         * Maps a path to the back stack it should produce.
         *
         * Segments in braces are captured: `"catalog/item/{id}"` matches
         * `catalog/item/42` and passes `id` to [build].
         *
         * Patterns are tried in declaration order, so declare the specific ones
         * before the general.
         *
         * @param pattern the path to match, without scheme or host.
         * @param build turns captured arguments into a back stack.
         */
        public fun path(
            pattern: String,
            build: (DeepLinkArgs) -> List<Route>,
        ) {
            patterns += DeepLinkPattern(pattern.trim('/'), build)
        }
    }

/** Arguments captured from a deep-link path. */
public class DeepLinkArgs internal constructor(
    private val values: Map<String, String>,
) {
    /** The raw value of [name], or `null` if the pattern did not capture it. */
    public fun stringOrNull(name: String): String? = values[name]

    /**
     * The value of [name].
     *
     * @throws IllegalArgumentException if the pattern did not capture it, which
     *   is a mistake in the pattern rather than bad input.
     */
    public fun string(name: String): String =
        requireNotNull(values[name]) {
            "Deep link pattern captured ${values.keys}, not '$name'."
        }

    /**
     * The value of [name] as an `Int`.
     *
     * @throws IllegalArgumentException if it is absent or not a number.
     */
    public fun int(name: String): Int =
        requireNotNull(string(name).toIntOrNull()) {
            "Deep link argument '$name' was '${string(name)}', which is not a number."
        }
}

/** One pattern and the stack it builds. */
@PublishedApi
internal class DeepLinkPattern(
    pattern: String,
    val build: (DeepLinkArgs) -> List<Route>,
) {
    private val segments = pattern.split('/').filter { it.isNotEmpty() }

    /** Captured arguments if [link] matches, or `null` if it does not. */
    fun match(link: ParsedLink): DeepLinkArgs? {
        if (link.segments.size != segments.size) return null

        val captured = mutableMapOf<String, String>()
        val matches =
            segments.withIndex().all { (index, segment) ->
                val part = link.segments[index]
                val isPlaceholder = segment.startsWith("{") && segment.endsWith("}")
                if (isPlaceholder) {
                    captured[segment.trim('{', '}')] = part
                }
                isPlaceholder || segment == part
            }

        // Query parameters are readable by name too, but a path placeholder of
        // the same name wins: it is the more specific of the two, and letting
        // a query string override a path would be a way to forge a link.
        return if (matches) DeepLinkArgs(link.query + captured) else null
    }
}

/**
 * A URI split into the parts a pattern cares about, percent-decoded.
 *
 * A pattern sees `Jalan Merdeka`, never `Jalan%20Merdeka`.
 */
internal class ParsedLink(
    val segments: List<String>,
    val query: Map<String, String>,
) {
    companion object {
        fun of(
            uri: String,
            hosts: Set<String>,
        ): ParsedLink {
            val withoutFragment = uri.substringBefore('#')
            val afterScheme = withoutFragment.substringAfter("://", withoutFragment)
            val rawPath = afterScheme.substringBefore('?')
            val rawQuery = afterScheme.substringAfter('?', "")

            var parts = rawPath.split('/').filter { it.isNotEmpty() }
            // The authority is only dropped when the app said it owns it.
            // That is what lets `arus://catalog/item/3` keep `catalog` as a
            // path segment while `https://shop.example.com/catalog/item/3`
            // loses the host.
            val authority = parts.firstOrNull()?.substringBefore(':')?.lowercase()
            if (authority != null && authority in hosts) {
                parts = parts.drop(1)
            }

            return ParsedLink(
                segments = parts.map { percentDecode(it, plusIsSpace = false) },
                query = parseQuery(rawQuery),
            )
        }

        private fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            return raw
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    percentDecode(pair.substringBefore('='), plusIsSpace = true) to
                        percentDecode(pair.substringAfter('=', ""), plusIsSpace = true)
                }
        }

        /**
         * Decodes `%XX` escapes, collecting bytes before decoding them so a
         * multi-byte character survives rather than becoming two mojibake
         * ones.
         *
         * @param plusIsSpace `+` means a space in a query string and a literal
         *   `+` in a path. Getting this backwards silently corrupts one or the
         *   other.
         */
        @Suppress("MagicNumber")
        private fun percentDecode(
            value: String,
            plusIsSpace: Boolean,
        ): String {
            if ('%' !in value && !(plusIsSpace && '+' in value)) return value

            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                val hex =
                    if (char == '%' && index + 2 < value.length) {
                        value.substring(index + 1, index + 3).toIntOrNull(16)
                    } else {
                        null
                    }
                when {
                    hex != null -> {
                        bytes += hex.toByte()
                        index += 3
                    }

                    plusIsSpace && char == '+' -> {
                        bytes += ' '.code.toByte()
                        index += 1
                    }

                    else -> {
                        char.toString().encodeToByteArray().forEach { bytes += it }
                        index += 1
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

/**
 * Replaces the back stack with [stack].
 *
 * The usual way to act on a resolved deep link:
 *
 * ```kotlin
 * val stack = deepLinks.resolve(intent.dataString.orEmpty())
 * if (stack.isNotEmpty()) navigator.openStack(stack)
 * ```
 *
 * An empty [stack] is ignored, so an unrecognised link leaves the user where
 * they were rather than navigating somewhere arbitrary.
 *
 * Built from [Navigator.resetTo] and [Navigator.push], so it behaves identically
 * against `FakeNavigator` in a unit test.
 */
public fun Navigator.openStack(stack: List<Route>) {
    if (stack.isEmpty()) return
    resetTo(stack.first())
    stack.drop(1).forEach(::push)
}
