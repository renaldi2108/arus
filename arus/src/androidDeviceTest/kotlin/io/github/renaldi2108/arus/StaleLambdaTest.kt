package io.github.renaldi2108.arus

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import io.github.renaldi2108.arus.core.select
import io.github.renaldi2108.arus.effects.CollectEffects
import kotlin.test.Test
import kotlin.test.assertEquals

private data class Counters(
    val left: Int = 0,
    val right: Int = 0,
)

private data class Ping(
    val label: String,
) : UiEffect

private class CountersController : ArusController<Counters, Ping>(Counters()) {
    fun bumpBoth() = setState { copy(left = left + 1, right = right + 10) }

    fun ping(label: String) = emit(Ping(label))
}

/**
 * Regression tests for two bugs that were silent in production: a lambda
 * captured at first composition and never refreshed. Both were fixed with
 * `rememberUpdatedState`; neither had a test until now.
 *
 * These run on a device because Compose behaviour cannot be asserted on the
 * JVM host without Robolectric.
 */
@OptIn(ExperimentalTestApi::class)
class StaleLambdaTest {
    @Test
    fun selectReadsThroughTheLatestSelector() =
        runComposeUiTest {
            val controller = CountersController()
            val readRight = mutableStateOf(false)

            setContent {
                // Read the flag *during composition* and capture it by value,
                // so composition #2 produces a genuinely different closure.
                // Reading `readRight.value` inside the lambda instead would
                // hide the bug: even a frozen lambda sees current state.
                val right = readRight.value
                val shown by controller.select { state ->
                    if (right) state.right else state.left
                }
                BasicText(text = shown.toString(), modifier = Modifier.testTag("shown"))
            }

            controller.bumpBoth()
            waitForIdle()
            onNodeWithTag("shown").assertTextEquals("1")

            // Swap the selector. A frozen lambda would keep reporting `left`.
            readRight.value = true
            waitForIdle()
            controller.bumpBoth()
            waitForIdle()

            onNodeWithTag("shown").assertTextEquals("20")
        }

    @Test
    fun collectEffectsDeliversToTheLatestHandler() =
        runComposeUiTest {
            val controller = CountersController()
            val useSecond = mutableStateOf(false)
            val first = mutableListOf<String>()
            val second = mutableListOf<String>()

            setContent {
                // Captured by value at composition time, for the same reason as
                // above: the handler from composition #1 must be observably
                // different from the one from composition #2.
                val target = if (useSecond.value) second else first
                CollectEffects(controller) { effect -> target += effect.label }
            }

            controller.ping("before")
            waitForIdle()

            useSecond.value = true
            waitForIdle()
            controller.ping("after")
            waitForIdle()

            assertEquals(listOf("before"), first)
            assertEquals(listOf("after"), second)
        }

    @Test
    fun anEffectEmittedBeforeAnyCollectorSurvivesIntoTheFirstComposition() =
        runComposeUiTest {
            // Retention, end to end through a real composition: emitted with
            // nobody listening, delivered once the screen appears, and the
            // recomposition in between does not replay or lose it.
            //
            // This is deliberately *not* a gate on "keying the LaunchedEffect
            // on the handler would drop an in-flight effect" — that was probed
            // and this test passes either way, because the controller's
            // retention re-delivers across a restart. The drop window is too
            // narrow to hit deterministically. See ADR-024.
            val controller = CountersController()
            val handler = mutableStateOf(0)
            val seen = mutableListOf<String>()

            controller.ping("emitted-before-any-collector")

            setContent {
                @Suppress("UNUSED_EXPRESSION")
                handler.value
                CollectEffects(controller) { seen += it.label }
            }
            waitForIdle()

            handler.value = 1
            waitForIdle()
            controller.ping("after-swap")
            waitForIdle()

            assertEquals(listOf("emitted-before-any-collector", "after-swap"), seen)
        }
}
