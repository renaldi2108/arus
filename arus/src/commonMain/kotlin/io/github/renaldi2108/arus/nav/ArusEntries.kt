package io.github.renaldi2108.arus.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * What a screen body can reach without being handed it.
 *
 * The receiver of every `screen<R>` body, so a screen gets the [navigator]
 * directly instead of repeating a lookup line:
 *
 * ```kotlin
 * screen<ProfileRoute> { route ->
 *     ProfileScreen(viewModel { ProfileController(route.userId, navigator) })
 * }
 * ```
 */
@Immutable
public class ArusScreenScope internal constructor(
    /** The navigator driving this host. */
    public val navigator: Navigator,
)

/**
 * One destination, as recorded by [ArusEntries.screen].
 *
 * Holds everything the host needs: the route type and its serializer for saved
 * state, and the composable for rendering. Keeping them together is what lets a
 * single `screen<R>` declaration serve both jobs.
 */
@PublishedApi
internal class RecordedScreen(
    val kClass: KClass<out Route>,
    val serializer: KSerializer<*>,
    val metadata: Map<String, Any>,
    val hidesTabBar: Boolean,
    val content: @Composable ArusScreenScope.(Route) -> Unit,
)

/**
 * Declares which composable renders which [Route].
 *
 * ```kotlin
 * ArusHost(start = HomeRoute) {
 *     screen<HomeRoute> { HomeScreen() }
 *     screen<ProfileRoute> { route -> ProfileScreen(route.userId) }
 * }
 * ```
 *
 * A `screen<R>` declaration is the **only** place a destination is named.
 * Registering it for saved-state serialization happens automatically from the
 * same declaration, so there is no second list of routes to keep in step as the
 * app grows.
 */
public class ArusEntries
    @PublishedApi
    internal constructor() {
        @PublishedApi
        internal val screens: MutableList<RecordedScreen> = mutableListOf()

        /**
         * Binds [content] as the screen for route type [R].
         *
         * [content] receives the route instance, so destination arguments arrive
         * typed rather than as a string bundle, and runs with [ArusScreenScope] as
         * its receiver so `navigator` is in scope. The route's serializer is
         * derived from [R]; use the overload taking one explicitly for a route with
         * a custom serializer.
         */
        public inline fun <reified R : Route> screen(noinline content: @Composable ArusScreenScope.(R) -> Unit) {
            screen(serializer<R>(), content)
        }

        /**
         * Binds [content] as the screen for route type [R], with its own
         * enter and exit animation.
         *
         * Overrides the host's default for this destination only — a modal-ish
         * screen sliding up while the rest of the app slides sideways, for
         * example.
         *
         * @param transition how this destination animates in.
         * @param popTransition how it animates out when popped; defaults to
         *   [transition] played in reverse by the host.
         */
        public inline fun <reified R : Route> screen(
            noinline transition: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform?,
            noinline popTransition: (AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform?)? = null,
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            val metadata =
                buildMap {
                    putAll(NavDisplay.transitionSpec(transition))
                    popTransition?.let { putAll(NavDisplay.popTransitionSpec(it)) }
                }
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer<R>(), metadata) { route -> content(route as R) }
        }

        /**
         * Binds [content] as a screen that takes over the whole window,
         * hiding the bottom navigation bar.
         *
         * Inside a tab, a pushed destination normally keeps the bar on screen
         * — a detail page still belongs to the tab you reached it from. Some
         * do not: a full-screen editor, a licence list, a photo viewer. Mark
         * those here and the bar goes away while they are showing.
         *
         * ```kotlin
         * // Settings tab: the list keeps the bar, the editor takes over.
         * screen<SettingsRoute> { SettingsScreen(...) }
         * screen<ProfileEditorRoute>(hidesTabBar = true) { ProfileEditor(...) }
         * ```
         *
         * The destination **stays on its tab's back stack**, so back returns
         * into the tab and the tab keeps its history. That is usually what you
         * want. If the destination should leave the tabs entirely — back
         * returning past the whole dashboard — nest [ArusTabHost] inside a
         * route of an outer [ArusHost] and push on the outer navigator
         * instead; `docs/ARCHITECTURE.md` shows the shape.
         *
         * The flag is inert outside tabs: a host without a bottom bar has
         * nothing to hide.
         *
         * @param hidesTabBar whether [ArusTabs.tabBarVisible] reports `false`
         *   while this destination is on top.
         * @param transition how this destination animates in; defaults to the
         *   host's.
         * @param popTransition how it animates out when popped.
         */
        public inline fun <reified R : Route> screen(
            hidesTabBar: Boolean,
            noinline transition: (AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform?)? = null,
            noinline popTransition: (AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform?)? = null,
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            val metadata =
                buildMap {
                    transition?.let { putAll(NavDisplay.transitionSpec(it)) }
                    popTransition?.let { putAll(NavDisplay.popTransitionSpec(it)) }
                }
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer<R>(), metadata, hidesTabBar) { route ->
                content(route as R)
            }
        }

        /**
         * Binds [content] as the screen for route type [R], with an explicitly
         * supplied [serializer].
         *
         * Needed only for a route with a custom serializer.
         */
        public inline fun <reified R : Route> screen(
            serializer: KSerializer<R>,
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer, emptyMap()) { route -> content(route as R) }
        }

        /**
         * Binds [content] as a **dialog** for route type [R].
         *
         * Identical to [screen] except that the destination floats over
         * whatever is below instead of replacing it, and the entry underneath
         * stays composed.
         *
         * It is still an ordinary back-stack entry: it has its own scoped
         * controller, survives configuration change, is restored after process
         * death, and [Navigator.pop] dismisses it. A dialog that hands a value
         * back through [Navigator.popWithResult] behaves exactly as a full
         * screen would.
         *
         * @param properties platform dialog behaviour, such as whether tapping
         *   outside dismisses.
         */
        public inline fun <reified R : Route> dialog(
            properties: DialogProperties = DialogProperties(),
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer<R>(), DialogSceneStrategy.dialog(properties)) { route ->
                content(route as R)
            }
        }

        /**
         * Binds [content] as a **modal bottom sheet** for route type [R].
         *
         * The sheet sibling of [dialog]: the destination slides up over what
         * is below, and the entry underneath stays composed so dismissing the
         * sheet does not reset it.
         *
         * It is an ordinary back-stack entry — own scoped controller, survives
         * configuration change, restored after process death, and
         * [Navigator.pop] dismisses it. A sheet that hands a value back
         * through [Navigator.popWithResult] behaves exactly as a full screen
         * would.
         *
         * ```kotlin
         * bottomSheetDialog<SortRoute> { route ->
         *     SortSheet(viewModel { SortController(route.current, navigator) })
         * }
         * ```
         *
         * Nothing in [content] needs to know it is inside a sheet. How a
         * destination is presented is decided here; what it does is decided by
         * the controller.
         *
         * @param properties height behaviour, drag handle, colours, dismissal.
         *   Every field defaults to the Material 3 default.
         */
        public inline fun <reified R : Route> bottomSheetDialog(
            properties: BottomSheetProperties = BottomSheetProperties(),
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer<R>(), bottomSheetMetadata(properties)) { route ->
                content(route as R)
            }
        }

        /**
         * Binds [content] for route type [R] with arbitrary entry [metadata].
         *
         * The escape hatch for a presentation Arus does not ship. Bottom
         * sheets used to be the example here and are now built in
         * ([bottomSheetDialog]); what remains are the genuinely bespoke cases
         * — a list/detail pane, a side sheet, a card stack. Supply the
         * strategy and the destination behaves like any other entry: own
         * scoped controller, survives process death, [Navigator.pop] dismisses
         * it.
         *
         * ```kotlin
         * ArusHost(
         *     start = ListRoute,
         *     sceneStrategies = listOf(TwoPaneSceneStrategy()),
         * ) {
         *     scene<DetailRoute>(TwoPaneSceneStrategy.detailPane()) { route ->
         *         DetailPane(viewModel { DetailController(route, navigator) })
         *     }
         * }
         * ```
         *
         * [screen], [dialog] and [bottomSheetDialog] are this function with
         * the metadata filled in; reach for them first.
         *
         * @param metadata passed through to the `NavEntry`, where a scene
         *   strategy reads it to decide whether the entry is its business.
         */
        public inline fun <reified R : Route> scene(
            metadata: Map<String, Any>,
            noinline content: @Composable ArusScreenScope.(R) -> Unit,
        ) {
            @Suppress("UNCHECKED_CAST")
            record(R::class, serializer<R>(), metadata) { route -> content(route as R) }
        }

        @PublishedApi
        internal fun record(
            kClass: KClass<out Route>,
            serializer: KSerializer<*>,
            metadata: Map<String, Any>,
            hidesTabBar: Boolean = false,
            content: @Composable ArusScreenScope.(Route) -> Unit,
        ) {
            require(screens.none { it.kClass == kClass }) {
                "Route ${kClass.simpleName} already has a screen. Each destination is declared " +
                    "exactly once; remove the duplicate `screen<${kClass.simpleName}>`."
            }
            screens += RecordedScreen(kClass, serializer, metadata, hidesTabBar, content)
        }
    }
