package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data object MsgRootRoute : Route

@Serializable
private data object MsgDeepRoute : Route

private data class MethodPicked(
    val value: String,
)

private data class CartChanged(
    val size: Int,
)

class NavMessageTest {
    @Test
    fun aMessageReachesAListenerFiveScreensBack() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val seen = mutableListOf<String>()

            // The screen at the root subscribes. Its controller is alive for
            // as long as its entry is on the stack, which is the whole point.
            val job = backgroundScope.launch { nav.messages<MethodPicked>().collect { seen += it.value } }
            yield()

            repeat(4) { nav.push(MsgDeepRoute) }
            nav.publish(MethodPicked("TRANSFER"))
            yield()

            assertEquals(listOf("TRANSFER"), seen)
            job.cancel()
        }

    @Test
    fun onlyTheRequestedTypeArrives() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val picked = mutableListOf<MethodPicked>()

            val job = backgroundScope.launch { nav.messages<MethodPicked>().collect { picked += it } }
            yield()

            nav.publish(CartChanged(3))
            nav.publish(MethodPicked("CARD"))
            nav.publish(CartChanged(4))
            yield()

            assertEquals(listOf(MethodPicked("CARD")), picked)
            job.cancel()
        }

    @Test
    fun severalListenersAllReceiveIt() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val a = mutableListOf<String>()
            val b = mutableListOf<String>()

            val j1 = backgroundScope.launch { nav.messages<MethodPicked>().collect { a += it.value } }
            val j2 = backgroundScope.launch { nav.messages<MethodPicked>().collect { b += it.value } }
            yield()

            nav.publish(MethodPicked("COD"))
            yield()

            // No addressing: every screen listening for the type hears it.
            assertEquals(listOf("COD"), a)
            assertEquals(listOf("COD"), b)
            j1.cancel()
            j2.cancel()
        }

    @Test
    fun publishingWithNobodyListeningIsANoOp() {
        val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))

        // Must not throw, must not grow anything: the bus does not exist yet.
        repeat(1000) { nav.publish(MethodPicked("ignored")) }

        assertEquals(listOf(MsgRootRoute), nav.entries)
    }

    @Test
    fun nothingIsReplayedToALateListener() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val early = mutableListOf<String>()
            val j1 = backgroundScope.launch { nav.messages<MethodPicked>().collect { early += it.value } }
            yield()

            nav.publish(MethodPicked("first"))
            yield()

            val late = mutableListOf<String>()
            val j2 = backgroundScope.launch { nav.messages<MethodPicked>().collect { late += it.value } }
            yield()
            nav.publish(MethodPicked("second"))
            yield()

            // A screen pushed *after* the message was sent does not see it.
            // Screens behind you subscribed before you got here, so this is
            // the normal case rather than a gap.
            assertEquals(listOf("first", "second"), early)
            assertEquals(listOf("second"), late)
            j1.cancel()
            j2.cancel()
        }

    @Test
    fun navigationDoesNotDisturbTheBus() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val seen = mutableListOf<String>()
            val job = backgroundScope.launch { nav.messages<MethodPicked>().collect { seen += it.value } }
            yield()

            nav.push(MsgDeepRoute)
            nav.publish(MethodPicked("a"))
            yield()
            nav.popToRoot()
            nav.publish(MethodPicked("b"))
            yield()

            assertEquals(listOf("a", "b"), seen)
            job.cancel()
        }

    @Test
    fun theBusIsSharedWithAGuardedNavigator() =
        runTest {
            val raw = ArusNavigator(NavBackStack<Route>(MsgRootRoute))
            val guarded = raw.guardedBy(NavGuard { _, _ -> GuardDecision.Allow })
            val seen = mutableListOf<String>()

            val job = backgroundScope.launch { guarded.messages<MethodPicked>().collect { seen += it.value } }
            yield()

            // Publishing through the raw navigator and listening through the
            // guarded one must be the same bus, or a guard would silently
            // split screens into two conversations.
            raw.publish(MethodPicked("same bus"))
            yield()

            assertTrue(seen.isNotEmpty(), "a guarded navigator must share the bus")
            job.cancel()
        }

    @Test
    fun messagesCanBeCollectedAsAList() =
        runTest {
            val nav = ArusNavigator(NavBackStack<Route>(MsgRootRoute))

            val collected = backgroundScope.async { nav.messages<MethodPicked>().take(2).toList() }
            yield()

            nav.publish(MethodPicked("one"))
            nav.publish(MethodPicked("two"))

            assertEquals(listOf(MethodPicked("one"), MethodPicked("two")), collected.await())
        }
}
