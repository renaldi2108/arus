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
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.ArusTab
import io.github.renaldi2108.arus.nav.ArusTabHost
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusTabs
import io.github.renaldi2108.arus.nav.tabBarVisible
import kotlinx.serialization.Serializable
import kotlin.test.Test

@Serializable
private data object DashboardRoute : Route

@Serializable
private data object BrowseRoute : Route

@Serializable
private data object SettingsRoute : Route

/** Reached from Settings, still inside the tab, but takes the whole window. */
@Serializable
private data object ProfileEditorRoute : Route

/** Reached from Settings and genuinely outside the dashboard. */
@Serializable
private data object LicencesRoute : Route

/**
 * Two ways a destination can leave the bottom bar behind, and they are not
 * the same thing.
 *
 * 1. **Still in the tab, bar hidden** — `screen<R>(hidesTabBar = true)`. Back
 *    returns into the tab, and the tab keeps its history.
 * 2. **Out of the tabs entirely** — nest `ArusTabHost` in a route of an outer
 *    host and push on the outer navigator. Back returns to the dashboard with
 *    every tab exactly as it was.
 */
@OptIn(ExperimentalTestApi::class)
class TabScopeTest {
    @Test
    fun aFullScreenDestinationHidesTheBarButStaysInItsTab() =
        runComposeUiTest {
            setContent {
                val tabs =
                    rememberArusTabs(
                        ArusTab("browse", BrowseRoute),
                        ArusTab("settings", SettingsRoute),
                    ) {
                        screen<BrowseRoute> { BasicText("browse", Modifier.testTag("browse")) }
                        screen<SettingsRoute> {
                            BasicText(
                                text = "settings",
                                modifier =
                                    Modifier
                                        .testTag("settings")
                                        .clickable { navigator.push(ProfileEditorRoute) },
                            )
                        }
                        screen<ProfileEditorRoute>(hidesTabBar = true) {
                            BasicText("editor", Modifier.testTag("editor"))
                        }
                    }

                Column {
                    // The app draws its own bar; Arus only answers whether it
                    // belongs on screen.
                    if (tabs.tabBarVisible()) {
                        BasicText(
                            text = "bar",
                            modifier = Modifier.testTag("bar").clickable { tabs.select("settings") },
                        )
                    }
                    ArusTabHost(tabs)
                }
            }

            onNodeWithTag("bar").performClick()
            waitForIdle()
            onNodeWithTag("settings").assertTextEquals("settings")

            onNodeWithTag("settings").performClick()
            waitForIdle()

            onNodeWithTag("editor").assertTextEquals("editor")
            onNodeWithTag("bar").assertDoesNotExist()
        }

    @Test
    fun theBarComesBackWhenTheFullScreenDestinationIsPopped() =
        runComposeUiTest {
            var popped = false
            setContent {
                val tabs =
                    rememberArusTabs(
                        ArusTab("settings", SettingsRoute),
                        ArusTab("browse", BrowseRoute),
                    ) {
                        screen<SettingsRoute> {
                            BasicText(
                                text = "settings",
                                modifier =
                                    Modifier
                                        .testTag("settings")
                                        .clickable { navigator.push(ProfileEditorRoute) },
                            )
                        }
                        screen<ProfileEditorRoute>(hidesTabBar = true) {
                            BasicText(
                                text = "editor",
                                modifier =
                                    Modifier
                                        .testTag("editor")
                                        .clickable {
                                            popped = navigator.pop()
                                        },
                            )
                        }
                        screen<BrowseRoute> { BasicText("browse", Modifier.testTag("browse")) }
                    }

                Column {
                    if (tabs.tabBarVisible()) BasicText("bar", Modifier.testTag("bar"))
                    ArusTabHost(tabs)
                }
            }

            onNodeWithTag("settings").performClick()
            waitForIdle()
            onNodeWithTag("bar").assertDoesNotExist()

            onNodeWithTag("editor").performClick()
            waitForIdle()

            // Back landed inside the tab, not outside it.
            onNodeWithTag("settings").assertTextEquals("settings")
            onNodeWithTag("bar").assertExists()
            kotlin.test.assertTrue(popped, "pop must act on the tab's own stack")
        }

    @Test
    fun aDestinationOnTheOuterHostLeavesTheTabsEntirely() =
        runComposeUiTest {
            setContent {
                ArusHost(start = DashboardRoute) {
                    screen<DashboardRoute> {
                        // The outer navigator, captured by the tab
                        // declarations below. This is the whole mechanism:
                        // nothing special, just lexical scope.
                        val outer = navigator

                        val tabs =
                            rememberArusTabs(
                                ArusTab("browse", BrowseRoute),
                                ArusTab("settings", SettingsRoute),
                            ) {
                                screen<BrowseRoute> {
                                    var count by rememberSaveable { mutableIntStateOf(0) }
                                    Column {
                                        BasicText(
                                            text = "browse",
                                            modifier =
                                                Modifier
                                                    .testTag("bump")
                                                    .clickable { count += 1 },
                                        )
                                        BasicText("$count", Modifier.testTag("count"))
                                    }
                                }
                                screen<SettingsRoute> {
                                    BasicText(
                                        text = "settings",
                                        modifier =
                                            Modifier
                                                .testTag("settings")
                                                .clickable { outer.push(LicencesRoute) },
                                    )
                                }
                            }

                        Column {
                            BasicText(
                                text = "to-settings",
                                modifier =
                                    Modifier
                                        .testTag("to_settings")
                                        .clickable { tabs.select("settings") },
                            )
                            BasicText("bar", Modifier.testTag("bar"))
                            ArusTabHost(tabs)
                        }
                    }
                    screen<LicencesRoute> {
                        BasicText(
                            text = "licences",
                            modifier =
                                Modifier
                                    .testTag("licences")
                                    .clickable { navigator.pop() },
                        )
                    }
                }
            }

            onNodeWithTag("bump").performClick()
            onNodeWithTag("bump").performClick()
            waitForIdle()
            onNodeWithTag("count").assertTextEquals("2")

            onNodeWithTag("to_settings").performClick()
            waitForIdle()
            onNodeWithTag("settings").performClick()
            waitForIdle()

            // Outside the dashboard: no bar, and the tabs are not rendered.
            onNodeWithTag("licences").assertTextEquals("licences")
            onNodeWithTag("bar").assertDoesNotExist()

            onNodeWithTag("licences").performClick()
            waitForIdle()

            // Back to the dashboard with both tabs as they were.
            onNodeWithTag("bar").assertExists()
            onNodeWithTag("settings").assertTextEquals("settings")
            onNodeWithTag("to_settings").performClick()
            waitForIdle()
        }
}
