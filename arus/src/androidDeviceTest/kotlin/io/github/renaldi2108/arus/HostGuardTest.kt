package io.github.renaldi2108.arus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.GuardDecision
import io.github.renaldi2108.arus.nav.NavGuard
import io.github.renaldi2108.arus.nav.NavInspector
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusGraph
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data object GuardHomeRoute : Route

@Serializable
private data object GuardLockedRoute : Route

@Serializable
private data object GuardSignInRoute : Route

/**
 * A guard written the way apps write them: a class holding a dependency.
 *
 * It has no `equals`, so two instances are never equal — which is exactly why
 * `guards = listOf(AllowAll())` allocates a key that differs on every
 * recomposition. A `NavGuard { }` lambda with no captures would be compiled to
 * a singleton and hide the problem.
 */
private class AllowAll(
    @Suppress("unused") private val dependency: () -> Boolean,
) : NavGuard {
    override fun check(
        from: Route,
        to: Route,
    ): GuardDecision = GuardDecision.Allow
}

/**
 * A guard installed on the host must apply to navigation from a screen.
 *
 * `guardedBy` existed before this, but nothing wired it into `ArusHost`, so a
 * guard could only wrap a navigator an app constructed by hand — which is not
 * how screens get theirs. The `guards` parameter closes that.
 */
@OptIn(ExperimentalTestApi::class)
class HostGuardTest {
    @Test
    fun aGuardRedirectsNavigationComingFromAScreen() =
        runComposeUiTest {
            setContent {
                ArusHost(
                    start = GuardHomeRoute,
                    guards =
                        listOf(
                            NavGuard { _, to ->
                                if (to == GuardLockedRoute) {
                                    GuardDecision.Redirect(GuardSignInRoute)
                                } else {
                                    GuardDecision.Allow
                                }
                            },
                        ),
                ) {
                    screen<GuardHomeRoute> {
                        BasicText(
                            text = "tap",
                            modifier =
                                Modifier
                                    .testTag("tap")
                                    .clickableTo { navigator.push(GuardLockedRoute) },
                        )
                    }
                    screen<GuardLockedRoute> { BasicText("locked", Modifier.testTag("screen")) }
                    screen<GuardSignInRoute> { BasicText("sign-in", Modifier.testTag("screen")) }
                }
            }

            onNodeWithTag("tap").performClick()
            waitForIdle()

            // Redirected, not blocked: the user lands somewhere useful.
            onNodeWithTag("screen").assertTextEquals("sign-in")
        }

    @Test
    fun anUnguardedDestinationIsUntouched() =
        runComposeUiTest {
            setContent {
                ArusHost(
                    start = GuardHomeRoute,
                    guards = listOf(NavGuard { _, _ -> GuardDecision.Allow }),
                ) {
                    screen<GuardHomeRoute> {
                        BasicText(
                            text = "tap",
                            modifier =
                                Modifier
                                    .testTag("tap")
                                    .clickableTo { navigator.push(GuardLockedRoute) },
                        )
                    }
                    screen<GuardLockedRoute> { BasicText("locked", Modifier.testTag("screen")) }
                }
            }

            onNodeWithTag("tap").performClick()
            waitForIdle()

            onNodeWithTag("screen").assertTextEquals("locked")
        }

    @Test
    fun theGraphSurvivesARecompositionThatRebuildsTheGuardList() =
        runComposeUiTest {
            // The shape every caller writes: `guards = listOf(SomeGuard { ... })`
            // inline, allocating a new list on every recomposition. Keying the
            // graph on that list rebuilds it each time, silently discarding
            // whatever was attached to it.
            val tick = mutableStateOf(0)
            var inspector: NavInspector? = null
            var graphInstances = 0

            setContent {
                Column {
                    // Recomposes this scope without being captured by the
                    // builder below. A builder that genuinely changes *must*
                    // rebuild the declarations, so capturing it there would
                    // test nothing.
                    BasicText("revision " + tick.value)

                    val graph =
                        rememberArusGraph(
                            start = GuardHomeRoute,
                            guards = listOf(AllowAll { true }),
                        ) {
                            screen<GuardHomeRoute> {
                                BasicText(
                                    text = "tap",
                                    modifier =
                                        Modifier
                                            .testTag("tap")
                                            .clickableTo { navigator.push(GuardLockedRoute) },
                                )
                            }
                            screen<GuardLockedRoute> {
                                BasicText("locked", Modifier.testTag("screen"))
                            }
                        }

                    // Exactly how an app attaches one: remembered against the
                    // graph. An unstable graph rebuilds this, and the history
                    // is gone without a single error anywhere.
                    inspector =
                        remember(graph) {
                            graphInstances += 1
                            NavInspector().also { graph.inspector = it }
                        }

                    ArusHost(graph)
                }
            }

            onNodeWithTag("tap").performClick()
            waitForIdle()
            repeat(3) {
                tick.value += 1
                waitForIdle()
            }

            onNodeWithTag("screen").assertTextEquals("locked")
            assertEquals(1, graphInstances, "the graph must survive guard-list churn")
            assertEquals(1, inspector?.events?.size, "the navigation history must survive it too")
        }
}

/** `clickable` as a plain modifier, so the test bodies stay readable. */
private fun Modifier.clickableTo(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
