package io.github.renaldi2108.arus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.select
import io.github.renaldi2108.arus.nav.ArusTab
import io.github.renaldi2108.arus.nav.ArusTabHost
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusTabs
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data object TabOneRoute : Route

@Serializable
private data object TabTwoRoute : Route

private data class Typed(
    val text: String = "",
)

private class TypingController : ArusController<Typed, Nothing>(Typed()) {
    fun type(next: String) = setState { copy(text = next) }
}

/**
 * A tab must keep its **screen state**, not merely its back stack.
 *
 * The distinction cost a real bug. `ArusTabHost` composes only the selected
 * tab, so an unselected tab's host left composition and took the entry
 * decorators with it — the back stack survived, so the user came back to the
 * right screen with every controller on it freshly constructed. Every earlier
 * check asserted the route and never the contents, so nothing caught it.
 * See ADR-032.
 */
@OptIn(ExperimentalTestApi::class)
class TabStateTest {
    @Test
    fun aTabsControllerStateSurvivesSwitchingAway() =
        runComposeUiTest {
            var created = 0

            setContent {
                val tabs =
                    rememberArusTabs(
                        ArusTab("one", TabOneRoute),
                        ArusTab("two", TabTwoRoute),
                    ) {
                        screen<TabOneRoute> {
                            val controller =
                                viewModel {
                                    created += 1
                                    TypingController()
                                }
                            val text by controller.select { it.text }
                            Column {
                                BasicText(
                                    text = "type",
                                    modifier =
                                        Modifier
                                            .testTag("type")
                                            .clickable { controller.type("typed") },
                                )
                                BasicText(text, Modifier.testTag("text"))
                            }
                        }
                        screen<TabTwoRoute> { BasicText("two", Modifier.testTag("two")) }
                    }

                Column {
                    BasicText(
                        text = "to-two",
                        modifier = Modifier.testTag("to_two").clickable { tabs.select("two") },
                    )
                    BasicText(
                        text = "to-one",
                        modifier = Modifier.testTag("to_one").clickable { tabs.select("one") },
                    )
                    ArusTabHost(tabs)
                }
            }

            onNodeWithTag("type").performClick()
            waitForIdle()
            onNodeWithTag("text").assertTextEquals("typed")

            onNodeWithTag("to_two").performClick()
            waitForIdle()
            onNodeWithTag("to_one").performClick()
            waitForIdle()

            onNodeWithTag("text").assertTextEquals("typed")
            assertEquals(1, created, "the tab's controller must not be rebuilt by a tab switch")
        }

    @Test
    fun aTabsSaveableStateSurvivesSwitchingAway() =
        runComposeUiTest {
            setContent {
                val tabs =
                    rememberArusTabs(
                        ArusTab("one", TabOneRoute),
                        ArusTab("two", TabTwoRoute),
                    ) {
                        screen<TabOneRoute> {
                            // `rememberSaveable` is held by the other
                            // decorator, so this covers the second half of the
                            // same bug.
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Column {
                                BasicText(
                                    text = "bump",
                                    modifier =
                                        Modifier
                                            .testTag("bump")
                                            .clickable { count += 1 },
                                )
                                BasicText("$count", Modifier.testTag("count"))
                            }
                        }
                        screen<TabTwoRoute> { BasicText("two", Modifier.testTag("two")) }
                    }

                Column {
                    BasicText(
                        text = "to-two",
                        modifier = Modifier.testTag("to_two").clickable { tabs.select("two") },
                    )
                    BasicText(
                        text = "to-one",
                        modifier = Modifier.testTag("to_one").clickable { tabs.select("one") },
                    )
                    ArusTabHost(tabs)
                }
            }

            onNodeWithTag("bump").performClick()
            onNodeWithTag("bump").performClick()
            waitForIdle()
            onNodeWithTag("count").assertTextEquals("2")

            onNodeWithTag("to_two").performClick()
            waitForIdle()
            onNodeWithTag("to_one").performClick()
            waitForIdle()

            onNodeWithTag("count").assertTextEquals("2")
        }
}
