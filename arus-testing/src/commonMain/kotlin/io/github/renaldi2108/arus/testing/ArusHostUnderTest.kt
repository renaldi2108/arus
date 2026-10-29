package io.github.renaldi2108.arus.testing

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import io.github.renaldi2108.arus.nav.ArusEntries
import io.github.renaldi2108.arus.nav.ArusHost
import io.github.renaldi2108.arus.nav.Navigator
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.rememberArusGraph

/**
 * A live [ArusHost] a test can navigate and then kill.
 *
 * Returned by [setArusHost].
 */
@ExperimentalTestApi
public class ArusHostUnderTest internal constructor(
    private val tester: StateRestorationTester,
    private val currentNavigator: () -> Navigator?,
) {
    /**
     * The host's navigator, as of the latest composition.
     *
     * Re-read it after [simulateProcessDeath] rather than holding onto it: the
     * composition is torn down and rebuilt, and in a test there is no
     * `ViewModel` outliving that, so the navigator is a new instance.
     */
    public val navigator: Navigator
        get() =
            checkNotNull(currentNavigator()) {
                "The host has not composed yet. Call setArusHost first, and let the test " +
                    "reach idle before reading the navigator."
            }

    /**
     * Saves every piece of saveable state, throws the composition away, and
     * rebuilds it from what was saved.
     *
     * The same path the system takes when it kills a backgrounded process and
     * the user returns — without needing a device, a kill, or a flaky wait.
     * A back stack that does not come back is the bug this catches.
     */
    public fun simulateProcessDeath() {
        tester.emulateSaveAndRestore()
    }
}

/**
 * Composes an [ArusHost] whose state can be saved and restored on demand.
 *
 * The only way to assert process-death restoration without killing a real
 * process:
 *
 * ```kotlin
 * @Test
 * fun theBackStackSurvivesProcessDeath() = runComposeUiTest {
 *     val host = setArusHost(start = ListRoute) {
 *         screen<ListRoute> { ListScreen() }
 *         screen<DetailRoute> { route -> DetailScreen(route.itemId) }
 *     }
 *
 *     host.navigator.push(DetailRoute(7))
 *     waitForIdle()
 *
 *     host.simulateProcessDeath()
 *
 *     assertEquals(DetailRoute(7), host.navigator.currentRoute.value)
 * }
 * ```
 *
 * The restoration is genuine: routes go through the same serialization the
 * platform uses, so a destination declared without a serializable argument
 * fails here exactly as it would on a user's phone.
 *
 * **What it does not simulate.** A pending
 * [pushForResult][Navigator.pushForResult] is not restored, because the
 * coroutine awaiting it would have died with the process. That is the real
 * behaviour, documented on `pushForResult`, not a gap in this helper.
 *
 * @param start the route the stack begins at.
 * @param builder declares every destination, as for [ArusHost].
 */
@ExperimentalTestApi
public fun ComposeUiTest.setArusHost(
    start: Route,
    builder: ArusEntries.() -> Unit,
): ArusHostUnderTest {
    val tester = StateRestorationTester(this)
    var captured: Navigator? = null

    tester.setContent {
        val graph = rememberArusGraph(start, builder = builder)
        captured = graph.navigator
        ArusHost(graph)
    }

    return ArusHostUnderTest(tester) { captured }
}
