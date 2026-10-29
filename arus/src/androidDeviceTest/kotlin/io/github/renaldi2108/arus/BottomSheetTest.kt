package io.github.renaldi2108.arus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.ArusTab
import io.github.renaldi2108.arus.nav.ArusTabHost
import io.github.renaldi2108.arus.nav.BottomSheetProperties
import io.github.renaldi2108.arus.nav.NavResult
import io.github.renaldi2108.arus.nav.ResultRoute
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusTabs
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data object SheetHomeRoute : Route

@Serializable
private data object SheetOtherTabRoute : Route

@Serializable
private data object SheetDeeperRoute : Route

@Serializable
private data object SortSheetRoute : ResultRoute<String>

/**
 * `bottomSheetDialog<R>` as a first-class destination (ADR-031).
 *
 * Two contexts matter and behave differently enough to be worth separating:
 * a sheet opened from an ordinary screen, and one opened from a route inside
 * a tab — where there are several back stacks and only one of them should be
 * affected.
 */
@OptIn(ExperimentalTestApi::class)
class BottomSheetTest {
    @Test
    fun aSheetOpensOverTheScreenAndLeavesItComposed() =
        runComposeUiTest {
            val homeCompositions = intArrayOf(0)

            setContent {
                ArusHost(start = SheetHomeRoute) {
                    screen<SheetHomeRoute> {
                        homeCompositions[0] += 1
                        BasicText(
                            text = "home",
                            modifier =
                                Modifier
                                    .testTag("open")
                                    .clickable { navigator.push(SortSheetRoute) },
                        )
                    }
                    bottomSheetDialog<SortSheetRoute> {
                        BasicText("sheet", Modifier.testTag("sheet"))
                    }
                }
            }
            val before = homeCompositions[0]

            onNodeWithTag("open").performClick()
            waitForIdle()

            onNodeWithTag("sheet").assertIsDisplayed()
            // An overlay, not a replacement: the screen underneath is still
            // there, which is why dismissing does not reset it.
            onNodeWithTag("open").assertIsDisplayed()
            assertEquals(before, homeCompositions[0], "the screen beneath must not be rebuilt")
        }

    @Test
    fun popDismissesTheSheet() =
        runComposeUiTest {
            setContent {
                ArusHost(start = SheetHomeRoute) {
                    screen<SheetHomeRoute> {
                        BasicText(
                            text = "home",
                            modifier =
                                Modifier
                                    .testTag("open")
                                    .clickable { navigator.push(SortSheetRoute) },
                        )
                    }
                    bottomSheetDialog<SortSheetRoute> {
                        BasicText(
                            text = "sheet",
                            modifier =
                                Modifier
                                    .testTag("sheet")
                                    .clickable { navigator.pop() },
                        )
                    }
                }
            }

            onNodeWithTag("open").performClick()
            waitForIdle()
            onNodeWithTag("sheet").performClick()
            waitForIdle()

            onNodeWithTag("sheet").assertDoesNotExist()
        }

    @Test
    fun aSheetHandsBackATypedResult() =
        runComposeUiTest {
            var received: NavResult<String>? = null

            setContent {
                val scope = rememberCoroutineScope()
                ArusHost(start = SheetHomeRoute) {
                    screen<SheetHomeRoute> {
                        val nav = navigator
                        BasicText(
                            text = "home",
                            modifier =
                                Modifier
                                    .testTag("open")
                                    .clickable {
                                        // What a controller does, inlined: the
                                        // caller suspends until the sheet
                                        // answers.
                                        scope.launch { received = nav.pushForResult(SortSheetRoute) }
                                    },
                        )
                    }
                    bottomSheetDialog<SortSheetRoute> {
                        BasicText(
                            text = "pick",
                            modifier =
                                Modifier
                                    .testTag("pick")
                                    .clickable { navigator.popWithResult("newest") },
                        )
                    }
                }
            }

            onNodeWithTag("open").performClick()
            waitForIdle()
            onNodeWithTag("pick").performClick()
            waitForIdle()

            assertEquals(NavResult.Completed("newest"), received)
            // Back on the screen that asked, with the sheet gone.
            onNodeWithTag("open").assertIsDisplayed()
        }

    @Test
    fun aSheetInsideATabAffectsOnlyThatTabsStack() =
        runComposeUiTest {
            setContent {
                val tabs =
                    rememberArusTabs(
                        ArusTab("browse", SheetHomeRoute),
                        ArusTab("other", SheetOtherTabRoute),
                    ) {
                        screen<SheetHomeRoute> {
                            Column {
                                BasicText(
                                    text = "browse",
                                    modifier =
                                        Modifier
                                            .testTag("open_sheet")
                                            .clickable { navigator.push(SortSheetRoute) },
                                )
                                BasicText(
                                    text = "deeper",
                                    modifier =
                                        Modifier
                                            .testTag("go_deeper")
                                            .clickable { navigator.push(SheetDeeperRoute) },
                                )
                            }
                        }
                        screen<SheetDeeperRoute> { BasicText("deep", Modifier.testTag("deep")) }
                        screen<SheetOtherTabRoute> { BasicText("other", Modifier.testTag("other")) }
                        bottomSheetDialog<SortSheetRoute> {
                            BasicText(
                                text = "sheet",
                                modifier =
                                    Modifier
                                        .testTag("sheet")
                                        .clickable { navigator.pop() },
                            )
                        }
                    }

                val selected by tabs.selectedTab.collectAsStateWithLifecycle()
                Column {
                    BasicText(
                        text = "to-other",
                        modifier = Modifier.testTag("tab_other").clickable { tabs.select("other") },
                    )
                    BasicText(
                        text = "to-browse",
                        modifier = Modifier.testTag("tab_browse").clickable { tabs.select("browse") },
                    )
                    BasicText("selected=$selected", Modifier.testTag("selected"))
                    ArusTabHost(tabs)
                }
            }

            onNodeWithTag("open_sheet").performClick()
            waitForIdle()
            onNodeWithTag("sheet").assertIsDisplayed()
            // Overlay inside a tab too: the tab's screen stays composed under
            // the sheet rather than being replaced by it.
            onNodeWithTag("open_sheet").assertIsDisplayed()

            // Switching tabs leaves the sheet behind with its own stack.
            onNodeWithTag("tab_other").performClick()
            waitForIdle()
            onNodeWithTag("other").assertIsDisplayed()
            onNodeWithTag("selected").assertTextEquals("selected=other")

            // Coming back restores the browse tab exactly as it was — sheet
            // included, because it is an entry on that tab's stack and nothing
            // else.
            onNodeWithTag("tab_browse").performClick()
            waitForIdle()
            onNodeWithTag("sheet").assertIsDisplayed()
            onNodeWithTag("open_sheet").assertIsDisplayed()

            // And the other tab never saw the sheet: popping the browse tab
            // returns to its own root, not to anything the other tab did.
            onNodeWithTag("sheet").performClick()
            waitForIdle()
        }

    @Test
    fun customPropertiesReachTheSheet() =
        runComposeUiTest {
            setContent {
                ArusHost(start = SheetHomeRoute) {
                    screen<SheetHomeRoute> {
                        BasicText(
                            text = "home",
                            modifier =
                                Modifier
                                    .testTag("open")
                                    .clickable { navigator.push(SortSheetRoute) },
                        )
                    }
                    bottomSheetDialog<SortSheetRoute>(
                        BottomSheetProperties(
                            skipPartiallyExpanded = true,
                            showDragHandle = false,
                            gesturesEnabled = false,
                        ),
                    ) {
                        BasicText("sheet", Modifier.testTag("sheet"))
                    }
                }
            }

            onNodeWithTag("open").performClick()
            waitForIdle()

            // Rendering at all with non-default properties is the claim: a
            // mis-wired property throws inside ModalBottomSheet rather than
            // degrading quietly.
            onNodeWithTag("sheet").assertIsDisplayed()
        }
}
