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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.renaldi2108.arus.core.select
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.testing.setArusHost
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data object RestoreListRoute : Route

@Serializable
private data class RestoreDetailRoute(
    val itemId: Int,
    val title: String,
) : Route

/**
 * Process-death restoration, asserted without killing a process.
 *
 * The device sweep already proves this against a real `am kill`. These cover
 * the same ground in seconds, so a regression is caught by the build rather
 * than by somebody remembering to run the sweep.
 */
@OptIn(ExperimentalTestApi::class)
class ProcessDeathRestorationTest {
    @Test
    fun theBackStackComesBack() =
        runComposeUiTest {
            val host =
                setArusHost(start = RestoreListRoute) {
                    screen<RestoreListRoute> { BasicText("list", Modifier.testTag("screen")) }
                    screen<RestoreDetailRoute> { route ->
                        BasicText(route.title, Modifier.testTag("screen"))
                    }
                }

            host.navigator.push(RestoreDetailRoute(7, "Lamp"))
            waitForIdle()
            onNodeWithTag("screen").assertTextEquals("Lamp")

            host.simulateProcessDeath()

            onNodeWithTag("screen").assertTextEquals("Lamp")
            assertEquals(RestoreDetailRoute(7, "Lamp"), host.navigator.currentRoute.value)
        }

    @Test
    fun argumentsSurviveWithTheirTypes() =
        runComposeUiTest {
            val host =
                setArusHost(start = RestoreListRoute) {
                    screen<RestoreListRoute> { }
                    screen<RestoreDetailRoute> { }
                }

            host.navigator.push(RestoreDetailRoute(42, "Desk"))
            waitForIdle()

            host.simulateProcessDeath()

            // Not a string bundle: the restored route is the same data class
            // with the same typed fields.
            val restored = host.navigator.currentRoute.value as RestoreDetailRoute
            assertEquals(42, restored.itemId)
            assertEquals("Desk", restored.title)
        }

    @Test
    fun aStackSeveralDeepComesBackWhole() =
        runComposeUiTest {
            val host =
                setArusHost(start = RestoreListRoute) {
                    screen<RestoreListRoute> { }
                    screen<RestoreDetailRoute> { }
                }

            host.navigator.push(RestoreDetailRoute(1, "a"))
            host.navigator.push(RestoreDetailRoute(2, "b"))
            waitForIdle()

            host.simulateProcessDeath()

            // Back still works all the way down, which a stack restored as a
            // single top entry would fail.
            assertEquals(RestoreDetailRoute(2, "b"), host.navigator.currentRoute.value)
            host.navigator.pop()
            waitForIdle()
            assertEquals(RestoreDetailRoute(1, "a"), host.navigator.currentRoute.value)
            host.navigator.pop()
            waitForIdle()
            assertEquals(RestoreListRoute, host.navigator.currentRoute.value)
        }

    @Test
    fun savedStateInAControllerComesBackToo() =
        runComposeUiTest {
            // The back stack surviving is one thing; a controller's own
            // persisted value is another, and nothing covered it before.
            val host =
                setArusHost(start = RestoreListRoute) {
                    screen<RestoreListRoute> {
                        val controller =
                            viewModel { DraftController(createSavedStateHandle()) }
                        val draft by controller.select { it.echo }
                        Column {
                            BasicText(
                                text = "type",
                                modifier =
                                    Modifier
                                        .testTag("type")
                                        .clickable { controller.type("half-written") },
                            )
                            BasicText(draft, Modifier.testTag("draft"))
                        }
                    }
                    screen<RestoreDetailRoute> { }
                }

            onNodeWithTag("type").performClick()
            waitForIdle()
            onNodeWithTag("draft").assertTextEquals("half-written")

            host.simulateProcessDeath()

            // The controller itself is gone and rebuilt; the value is not.
            onNodeWithTag("draft").assertTextEquals("half-written")
        }
}

private data class DraftState(
    val echo: String = "",
)

/** Keeps one value across process death, the way a real draft would. */
private class DraftController(
    handle: androidx.lifecycle.SavedStateHandle,
) : io.github.renaldi2108.arus.core.ArusController<DraftState, Nothing>(
        DraftState(),
        savedStateHandle = handle,
    ) {
    private var draft: String by savedState("draft", "")

    init {
        // Republish whatever survived so the screen shows it immediately.
        setState { copy(echo = draft) }
    }

    fun type(text: String) {
        draft = text
        setState { copy(echo = text) }
    }
}
