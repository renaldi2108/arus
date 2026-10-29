package io.github.renaldi2108.arus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
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
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.Navigator
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.messages
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data object MsgRoot : Route

@Serializable
private data class MsgStep(
    val depth: Int,
) : Route

private data class MethodPicked(
    val value: String,
)

private data class RootState(
    val method: String = "none",
)

/**
 * Listens for a message while four screens sit on top of it.
 *
 * The controller is alive the whole time — its entry never left the stack —
 * which is why no buffering or replay is needed.
 */
private class RootController(
    navigator: Navigator,
) : ArusController<RootState, Nothing>(RootState()) {
    init {
        launchCatching {
            navigator.messages<MethodPicked>().collect { picked ->
                setState { copy(method = picked.value) }
            }
        }
    }
}

/**
 * Talking back to a screen several steps behind (ADR-035).
 *
 * `pushForResult` answers the screen that asked and only that one; probing
 * showed a result sent from deeper was swallowed, and the original request
 * was then cancelled on the way back. `publish`/`messages` is the answer for
 * the deep case.
 */
@OptIn(ExperimentalTestApi::class)
class DeepMessageTest {
    @Test
    fun aMessageFromFiveScreensDeepReachesTheRoot() =
        runComposeUiTest {
            setContent {
                ArusHost(start = MsgRoot) {
                    screen<MsgRoot> {
                        val controller = viewModel { RootController(navigator) }
                        val method by controller.select { it.method }
                        Column {
                            BasicText(
                                text = "deeper",
                                modifier =
                                    Modifier
                                        .testTag("go")
                                        .clickable { navigator.push(MsgStep(1)) },
                            )
                            BasicText(method, Modifier.testTag("method"))
                        }
                    }
                    screen<MsgStep> { route ->
                        Column {
                            BasicText(
                                text = "step ${route.depth}",
                                modifier =
                                    Modifier
                                        .testTag("next")
                                        .clickable { navigator.push(MsgStep(route.depth + 1)) },
                            )
                            BasicText(
                                text = "send",
                                modifier =
                                    Modifier
                                        .testTag("send")
                                        .clickable { navigator.publish(MethodPicked("TRANSFER")) },
                            )
                            BasicText(
                                text = "home",
                                modifier =
                                    Modifier
                                        .testTag("home")
                                        .clickable { navigator.popToRoot() },
                            )
                        }
                    }
                }
            }

            onNodeWithTag("go").performClick()
            waitForIdle()
            repeat(3) {
                onNodeWithTag("next").performClick()
                waitForIdle()
            }

            // Five deep, and the root is nowhere near the screen.
            onNodeWithTag("send").performClick()
            waitForIdle()

            onNodeWithTag("home").performClick()
            waitForIdle()

            // The root already had it before the user got back.
            onNodeWithTag("method").assertTextEquals("TRANSFER")
        }

    @Test
    fun theRootIsUntouchedWhenNothingIsSent() =
        runComposeUiTest {
            setContent {
                ArusHost(start = MsgRoot) {
                    screen<MsgRoot> {
                        val controller = viewModel { RootController(navigator) }
                        val method by controller.select { it.method }
                        Column {
                            BasicText(
                                text = "deeper",
                                modifier =
                                    Modifier
                                        .testTag("go")
                                        .clickable { navigator.push(MsgStep(1)) },
                            )
                            BasicText(method, Modifier.testTag("method"))
                        }
                    }
                    screen<MsgStep> {
                        BasicText(
                            text = "home",
                            modifier =
                                Modifier
                                    .testTag("home")
                                    .clickable { navigator.popToRoot() },
                        )
                    }
                }
            }

            onNodeWithTag("go").performClick()
            waitForIdle()
            onNodeWithTag("home").performClick()
            waitForIdle()

            onNodeWithTag("method").assertTextEquals("none")
        }

    @Test
    fun screenOneReceivesItWhileTheUserIsStillStandingOnScreenFour() =
        runComposeUiTest {
            // The precise question: stay on 4, send to 1. No popping, no
            // navigating back — screen 1 is not even composed.
            var root: RootController? = null

            setContent {
                ArusHost(start = MsgRoot) {
                    screen<MsgRoot> {
                        val controller = viewModel { RootController(navigator) }
                        root = controller
                        BasicText(
                            text = "deeper",
                            modifier =
                                Modifier
                                    .testTag("go")
                                    .clickable { navigator.push(MsgStep(2)) },
                        )
                    }
                    screen<MsgStep> { route ->
                        Column {
                            BasicText(
                                text = "step ${route.depth}",
                                modifier =
                                    Modifier
                                        .testTag("next")
                                        .clickable { navigator.push(MsgStep(route.depth + 1)) },
                            )
                            BasicText(
                                text = "send",
                                modifier =
                                    Modifier
                                        .testTag("send")
                                        .clickable { navigator.publish(MethodPicked("TRANSFER")) },
                            )
                        }
                    }
                }
            }

            onNodeWithTag("go").performClick()
            waitForIdle()
            onNodeWithTag("next").performClick()
            waitForIdle()
            onNodeWithTag("next").performClick()
            waitForIdle()

            // Screen 4 is showing; screen 1 is three entries down and out of
            // composition entirely.
            onNodeWithTag("next").assertTextEquals("step 4")
            assertEquals("none", root?.state?.value?.method)

            onNodeWithTag("send").performClick()
            waitForIdle()

            // Screen 1's controller already has it. Nobody navigated.
            assertEquals("TRANSFER", root?.state?.value?.method)
            onNodeWithTag("next").assertTextEquals("step 4")
        }
}
