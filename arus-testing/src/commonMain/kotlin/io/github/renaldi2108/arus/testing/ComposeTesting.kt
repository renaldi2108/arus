package io.github.renaldi2108.arus.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import io.github.renaldi2108.arus.nav.LocalArusNavigator
import io.github.renaldi2108.arus.nav.Navigator

/**
 * Sets composable [content] with [navigator] published as `LocalArusNavigator`.
 *
 * A screen reaches the navigator through the composition, so composing one
 * directly in a test otherwise throws. This supplies a navigator — usually a
 * [FakeNavigator] — without standing up a whole [io.github.renaldi2108.arus.nav.ArusHost]:
 *
 * ```kotlin
 * @Test
 * fun tappingTheRowOpensTheDetail() = runComposeUiTest {
 *     val navigator = FakeNavigator(ListRoute)
 *
 *     setArusContent(navigator) {
 *         ListScreen(ListController(FakeRepository(), navigator))
 *     }
 *     onNodeWithText("Item 7").performClick()
 *
 *     assertEquals(listOf(ListRoute, DetailRoute(7)), navigator.backStack)
 * }
 * ```
 *
 * **What this does not give you.** There is no back stack and no entry
 * scoping, so a `viewModel { }` inside [content] resolves against the test's
 * own store and is never cleared. That is the point: it isolates one screen.
 * To assert on scoping or on real navigation, compose an `ArusHost` instead.
 *
 * @param navigator published for [content] to use.
 * @param content the composable under test.
 */
@ExperimentalTestApi
public fun ComposeUiTest.setArusContent(
    navigator: Navigator,
    content: @Composable () -> Unit,
) {
    setContent {
        CompositionLocalProvider(LocalArusNavigator provides navigator) {
            content()
        }
    }
}
