package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data object ResultHomeRoute : Route

@Serializable
private data object ResultOtherRoute : Route

@Serializable
private data object ResultReplacementRoute : Route

@Serializable
private data class PickCountryRoute(
    val preselected: String?,
) : ResultRoute<String>

@Serializable
private data class PickCountRoute(
    val max: Int,
) : ResultRoute<Int>

private fun navigatorOf(vararg routes: Route) = ArusNavigator(NavBackStack(*routes))

class NavResultTest {
    @Test
    fun completingHandsTheValueBack() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val awaited = async { nav.pushForResult(PickCountryRoute(null)) }
            yield()

            nav.popWithResult("ID")

            assertEquals(NavResult.Completed("ID"), awaited.await())
            assertEquals(listOf(ResultHomeRoute), nav.entries)
        }

    @Test
    fun poppingWithoutAResultCancels() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val awaited = async { nav.pushForResult(PickCountryRoute(null)) }
            yield()

            // The user pressed back rather than choosing anything.
            nav.pop()

            assertEquals(NavResult.Cancelled, awaited.await())
        }

    @Test
    fun anEntryDiscardedByPopToIsCancelledRatherThanLeftHanging() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val awaited = async { nav.pushForResult(PickCountryRoute(null)) }
            yield()
            nav.push(ResultOtherRoute)

            // popTo skips straight past the result screen. Without explicit
            // cancellation the caller would suspend forever on a screen the user
            // can no longer reach.
            nav.popTo(ResultHomeRoute)

            assertEquals(NavResult.Cancelled, awaited.await())
        }

    @Test
    fun resetToCancelsEveryPendingRequest() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val first = async { nav.pushForResult(PickCountryRoute(null)) }
            yield()
            val second = async { nav.pushForResult(PickCountRoute(max = 5)) }
            yield()

            nav.resetTo(ResultHomeRoute)

            assertEquals(NavResult.Cancelled, first.await())
            assertEquals(NavResult.Cancelled, second.await())
        }

    @Test
    fun nestedRequestsResolveIndependently() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val country = async { nav.pushForResult(PickCountryRoute(null)) }
            yield()
            val count = async { nav.pushForResult(PickCountRoute(max = 5)) }
            yield()

            // Innermost closes first, which is the only order the stack allows.
            nav.popWithResult(3)
            assertEquals(NavResult.Completed(3), count.await())

            nav.popWithResult("ID")
            assertEquals(NavResult.Completed("ID"), country.await())

            assertEquals(listOf(ResultHomeRoute), nav.entries)
        }

    @Test
    fun popWithResultStillPopsWhenNobodyIsAwaiting() =
        runTest {
            // What happens after process death: the stack is restored but the
            // coroutine that asked for the result is gone.
            val nav = navigatorOf(ResultHomeRoute, PickCountryRoute(null))

            nav.popWithResult("ID")

            assertEquals(listOf(ResultHomeRoute), nav.entries)
        }

    @Test
    fun valueOrNullUnwrapsBothCases() {
        assertEquals("ID", NavResult.Completed("ID").valueOrNull())
        assertNull((NavResult.Cancelled as NavResult<String>).valueOrNull())
    }

    @Test
    fun theResultScreenIsOnTopWhileAwaiting() =
        runTest {
            val nav = navigatorOf(ResultHomeRoute)

            val awaited = async { nav.pushForResult(PickCountryRoute("ID")) }
            yield()

            assertEquals(PickCountryRoute("ID"), nav.currentRoute.value)
            assertTrue(nav.canPop.value)

            nav.popWithResult("MY")
            awaited.await()
        }

    // The next two run *outside* `runTest` on purpose.
    //
    // `popWithResult` must pop before it hands the value over, and whether that
    // ordering is observable depends on how the awaiting coroutine resumes. On
    // Android it resumes inline, because `viewModelScope` uses
    // `Dispatchers.Main.immediate`. Inside `runTest` the resume is queued onto
    // the test thread's event loop instead, so it always lands after
    // `popWithResult` has returned — and the wrong order looks correct.
    // Plain `Dispatchers.Unconfined`, with no event loop on the calling thread,
    // reproduces the inline resume. Verified by reverting the fix: both of
    // these fail, and no other test does.

    @Test
    fun theDestinationIsOffTheStackBeforeTheCallerResumes() {
        val nav = navigatorOf(ResultHomeRoute)
        var stackWhenResumed: List<Route>? = null

        CoroutineScope(Dispatchers.Unconfined).async {
            nav.pushForResult(PickCountryRoute(null))
            // What a real caller does next: navigate. It must act on the stack
            // the user will actually see, not on one still showing the picker.
            // `entries` is a live view of the stack, so snapshot it — reading
            // it later would show the state after `popWithResult` returned and
            // this assertion would hold either way.
            stackWhenResumed = nav.entries.toList()
        }

        nav.popWithResult("ID")

        assertEquals(listOf(ResultHomeRoute), stackWhenResumed)
    }

    @Test
    fun aReplaceInTheContinuationLandsOnTheScreenBeneath() {
        val nav = navigatorOf(ResultHomeRoute, ResultOtherRoute)

        CoroutineScope(Dispatchers.Unconfined).async {
            nav.pushForResult(PickCountryRoute(null))
            // Replace the screen that asked, not the picker. The replacement is
            // a route distinct from both, so the assertion can tell which one
            // was swapped out.
            nav.replace(ResultReplacementRoute)
        }

        nav.popWithResult("ID")

        assertEquals(listOf(ResultHomeRoute, ResultReplacementRoute), nav.entries)
    }
}
