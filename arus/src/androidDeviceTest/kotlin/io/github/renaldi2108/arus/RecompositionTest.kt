package io.github.renaldi2108.arus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import io.github.renaldi2108.arus.core.collectState
import io.github.renaldi2108.arus.core.select
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusGraph
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data object RecompHomeRoute : Route

@Serializable
private data object RecompNextRoute : Route

private data class Split(
    val watched: Int = 0,
    val ignored: Int = 0,
)

private class SplitController : ArusController<Split, Nothing>(Split()) {
    fun bumpWatched() = setState { copy(watched = watched + 1) }

    fun bumpIgnored() = setState { copy(ignored = ignored + 1) }
}

private object NoEffect : UiEffect

/**
 * Recomposition counts, against a hand-written Navigation 3 control.
 *
 * `compose-runtime-tracing` was wired into the sample but never read. Counting
 * compositions directly in a test is both simpler and checkable by CI.
 */
@OptIn(ExperimentalTestApi::class)
class RecompositionTest {
    @Test
    fun selectIgnoresChangesToSlicesItDoesNotRead() =
        runComposeUiTest {
            val controller = SplitController()
            var bodyCompositions = 0

            setContent {
                val watched by controller.select { it.watched }
                bodyCompositions += 1
                BasicText("watched $watched", Modifier.testTag("out"))
            }
            waitForIdle()
            val afterFirst = bodyCompositions

            repeat(5) { controller.bumpIgnored() }
            waitForIdle()

            // The headline claim in the README: a screen reading one slice is
            // untouched by writes to another.
            assertEquals(afterFirst, bodyCompositions, "select must not wake on an unread slice")

            controller.bumpWatched()
            waitForIdle()
            assertTrue(bodyCompositions > afterFirst, "but it must wake on the slice it reads")
        }

    @Test
    fun collectStateWakesOnAnyChangeWhichIsWhySelectExists() =
        runComposeUiTest {
            val controller = SplitController()
            var bodyCompositions = 0

            setContent {
                val state by controller.collectState()
                bodyCompositions += 1
                BasicText("all ${state.watched}", Modifier.testTag("out"))
            }
            waitForIdle()
            val afterFirst = bodyCompositions

            controller.bumpIgnored()
            waitForIdle()

            // Not a defect — the documented difference between the two, and the
            // reason `select` is worth its extra line.
            assertTrue(
                bodyCompositions > afterFirst,
                "collectState observes the whole value by design",
            )
        }

    @Test
    fun arusHostDoesNotComposeAScreenMoreOftenThanRawNavigation3() =
        runComposeUiTest {
            // Counters in stable holders so neither side's content lambda
            // changes identity between recompositions. A fresh lambda would
            // recompose that side for reasons unrelated to the host.
            val arusHome = intArrayOf(0)
            val rawHome = intArrayOf(0)
            val trigger = mutableStateOf(0)
            var arusNav: io.github.renaldi2108.arus.nav.Navigator? = null
            var rawStack: NavBackStack<Route>? = null

            // Both hosts in one composition, driven by one trigger: the same
            // session, the same frames, the same device state. Comparing two
            // separate runs would reintroduce exactly the between-session
            // noise that invalidated an earlier measurement (ADR-018).
            setContent {
                Column {
                    BasicText("t" + trigger.value)

                    val graph =
                        rememberArusGraph(start = RecompHomeRoute) {
                            screen<RecompHomeRoute> {
                                arusHome[0] += 1
                                BasicText("home", Modifier.testTag("arus"))
                            }
                            screen<RecompNextRoute> { BasicText("next") }
                        }
                    arusNav = graph.navigator
                    ArusHost(graph)

                    rawStack = RawNav3Host(rawHome)
                }
            }
            waitForIdle()

            // Parent recompositions must not reach either screen.
            repeat(3) {
                trigger.value += 1
                waitForIdle()
            }
            assertEquals(
                rawHome[0],
                arusHome[0],
                "parent recomposition: arus=" + arusHome[0] + " raw=" + rawHome[0],
            )

            // Real navigation, there and back, on both.
            repeat(3) {
                arusNav!!.push(RecompNextRoute)
                rawStack!!.add(RecompNextRoute)
                waitForIdle()
                arusNav!!.pop()
                rawStack!!.removeAt(rawStack!!.lastIndex)
                waitForIdle()
            }

            assertEquals(
                rawHome[0],
                arusHome[0],
                "after navigation: arus=" + arusHome[0] + " raw=" + rawHome[0],
            )
            assertTrue(arusHome[0] > 1, "the screen must have recomposed on return")
        }
}

/**
 * The control: Navigation 3 wired by hand, with the same two decorators
 * `ArusHost` installs.
 *
 * Decorators included deliberately — a control without them is not the same
 * app, and comparing against one was the mistake behind the retracted
 * jank finding (ADR-018).
 */
@Composable
private fun RawNav3Host(homeCompositions: IntArray): NavBackStack<Route> {
    val backStack = remember { NavBackStack<Route>(RecompHomeRoute) }
    val saveable = rememberSaveableStateHolderNavEntryDecorator<Route>()
    val viewModels = rememberViewModelStoreNavEntryDecorator<Route>()
    val decorators = remember(saveable, viewModels) { listOf(saveable, viewModels) }

    // Remembered, exactly as `rememberArusGraph` remembers its own. Rebuilding
    // the provider on every recomposition would make the control recompose for
    // a reason the host under test does not share.
    val provider =
        remember {
            entryProvider<Route> {
                entry<RecompHomeRoute> {
                    homeCompositions[0] += 1
                    BasicText("home", Modifier.testTag("raw"))
                }
                entry<RecompNextRoute> { BasicText("next") }
            }
        }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeAt(backStack.lastIndex) },
        entryDecorators = decorators,
        entryProvider = provider,
    )
    return backStack
}
