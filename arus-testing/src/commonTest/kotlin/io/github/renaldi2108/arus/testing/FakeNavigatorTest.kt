package io.github.renaldi2108.arus.testing

import androidx.navigation3.runtime.NavBackStack
import io.github.renaldi2108.arus.nav.ArusNavigator
import io.github.renaldi2108.arus.nav.NavResult
import io.github.renaldi2108.arus.nav.Navigator
import io.github.renaldi2108.arus.nav.ResultRoute
import io.github.renaldi2108.arus.nav.Route
import io.github.renaldi2108.arus.nav.messages
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data object FakeHome : Route

@Serializable
private data object FakeFirst : Route

@Serializable
private data object FakeSecond : Route

@Serializable
private data object FakePick : ResultRoute<String>

/**
 * The same script run against both navigators.
 *
 * A fake that drifts from the real implementation is worse than no fake: tests
 * keep passing while the app breaks. These cases pin the two together.
 */
private fun script(nav: Navigator): List<String> {
    val log = mutableListOf<String>()

    fun note(label: String) = log.add("$label -> ${nav.currentRoute.value} canPop=${nav.canPop.value}")

    note("start")
    nav.push(FakeFirst)
    note("push")
    nav.push(FakeSecond)
    note("push")
    log += "popTo(first)=${nav.popTo(FakeFirst)}"
    note("popTo")
    nav.replace(FakeSecond)
    note("replace")
    log += "pop()=${nav.pop()}"
    note("pop")
    log += "popAtRoot()=${nav.pop()}"
    note("popAtRoot")
    nav.push(FakeFirst)
    nav.popToRoot()
    note("popToRoot")
    nav.resetTo(FakeSecond)
    note("resetTo")
    return log
}

class FakeNavigatorTest {
    @Test
    fun behavesIdenticallyToTheRealNavigator() {
        val fake = script(FakeNavigator(FakeHome))
        val real = script(ArusNavigator(NavBackStack(FakeHome)))

        assertEquals(real, fake, "FakeNavigator must not drift from ArusNavigator")
    }

    @Test
    fun recordsWhatTheControllerIntended() {
        val nav = FakeNavigator(FakeHome)

        nav.push(FakeFirst)
        nav.replace(FakeSecond)
        nav.popToRoot()

        assertEquals(
            listOf(
                NavOperation.Push(FakeFirst),
                NavOperation.Replace(FakeSecond),
                NavOperation.PopToRoot,
            ),
            nav.operations,
        )
    }

    @Test
    fun exposesTheStackForAssertions() {
        val nav = FakeNavigator(FakeHome)

        nav.push(FakeFirst)
        nav.push(FakeSecond)

        assertEquals(listOf(FakeHome, FakeFirst, FakeSecond), nav.backStack)
        assertTrue(nav.canPop.value)
    }

    @Test
    fun awaitingAResultYieldsCancelledByDefault() =
        runTest {
            val nav = FakeNavigator(FakeHome)

            // Nothing staged: the user pressing back is the default outcome.
            assertEquals(NavResult.Cancelled, nav.pushForResult(FakePick))
            assertEquals(listOf(FakeHome), nav.backStack)
        }

    @Test
    fun aStagedResultIsHandedBack() =
        runTest {
            val nav = FakeNavigator(FakeHome)
            nav.nextResult = "ID"

            assertEquals(NavResult.Completed("ID"), nav.pushForResult(FakePick))
            assertEquals(listOf(FakeHome), nav.backStack, "the result screen must not linger")
        }

    @Test
    fun popAtRootReportsFalseLikeTheRealThing() {
        val nav = FakeNavigator(FakeHome)

        assertFalse(nav.pop())
        assertEquals(listOf(FakeHome), nav.backStack)
    }

    @Test
    fun popToRecordsTheOperationAndTrimsTheStack() {
        val fake = FakeNavigator(FakeHome)
        fake.push(FakeFirst)
        fake.push(FakeSecond)

        assertTrue(fake.popTo(FakeFirst))

        assertEquals(listOf(FakeHome, FakeFirst), fake.backStack)
        assertTrue(fake.operations.any { it is NavOperation.PopTo && it.route == FakeFirst })
    }

    @Test
    fun popToInclusiveRemovesTheNamedEntryToo() {
        val fake = FakeNavigator(FakeHome)
        fake.push(FakeFirst)
        fake.push(FakeSecond)

        assertTrue(fake.popTo(FakeFirst, inclusive = true))

        assertEquals(listOf(FakeHome), fake.backStack)
    }

    @Test
    fun popToARouteThatIsNotThereChangesNothing() {
        val fake = FakeNavigator(FakeHome)
        fake.push(FakeFirst)

        assertFalse(fake.popTo(FakeSecond))

        assertEquals(listOf(FakeHome, FakeFirst), fake.backStack)
    }

    @Test
    fun popToTheTopInclusivelyAtTheRootIsRefused() {
        val fake = FakeNavigator(FakeHome)

        // Honouring it would empty the stack, which no navigator allows.
        assertFalse(fake.popTo(FakeHome, inclusive = true))
        assertEquals(listOf(FakeHome), fake.backStack)
    }

    @Test
    fun popToTheCurrentTopIsANoOp() {
        val fake = FakeNavigator(FakeHome)
        fake.push(FakeFirst)

        // Already there: nothing to remove, so nothing changes.
        assertFalse(fake.popTo(FakeFirst))
        assertEquals(listOf(FakeHome, FakeFirst), fake.backStack)
    }

    @Test
    fun popWithResultIsRecordedAndPops() {
        val fake = FakeNavigator(FakeHome)
        fake.push(FakePick)

        fake.popWithResult("TRANSFER")

        assertEquals(listOf(FakeHome), fake.backStack)
        val recorded = fake.operations.filterIsInstance<NavOperation.PopWithResult>().single()
        assertEquals("TRANSFER", recorded.result)
    }

    @Test
    fun publishIsRecordedAndReadable() {
        val fake = FakeNavigator(FakeHome)

        fake.publish("order placed")

        // `published` is the convenience a test usually wants; the operation
        // list keeps it in order with the navigation around it.
        assertEquals(listOf<Any>("order placed"), fake.published)
        val recorded = fake.operations.filterIsInstance<NavOperation.Publish>().single()
        assertEquals("order placed", recorded.message)
    }

    @Test
    fun messagesDeliverToACollectorByType() =
        runTest {
            val fake = FakeNavigator(FakeHome)
            val seen = mutableListOf<String>()
            val job = backgroundScope.launch { fake.messages<String>().collect { seen += it } }
            kotlinx.coroutines.yield()

            fake.publish(7)
            fake.publish("picked")
            kotlinx.coroutines.yield()

            // Same filtering rule as the real navigator: only the asked-for
            // type arrives, so a test written against the fake is not passing
            // against a looser contract.
            assertEquals(listOf("picked"), seen)
            job.cancel()
        }
}
