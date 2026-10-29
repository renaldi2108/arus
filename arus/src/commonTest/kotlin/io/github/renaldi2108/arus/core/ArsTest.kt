package io.github.renaldi2108.arus.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class Form {
    var query: String by ars("")
    val queryArs: Ars<String> = ars("")
}

class ArsTest {
    @Test
    fun rxReadsAndWritesThroughTheDelegate() {
        val form = Form()

        form.query = "lamp"

        assertEquals("lamp", form.query)
    }

    @Test
    fun theValuePropertyAndTheDelegateSeeTheSameCell() {
        val cell = ars(1)

        cell.value = 7

        assertEquals(7, cell.value)
        assertEquals(7, cell.asFlow.value)
    }

    @Test
    fun asFlowEmitsEveryWrite() =
        runTest {
            val cell = ars(0)

            cell.value = 1
            cell.value = 2

            // A StateFlow conflates, so a collector arriving now sees the
            // latest value rather than the history — the documented behaviour,
            // asserted so a change to a replaying flow would be noticed.
            assertEquals(2, cell.asFlow.first())
        }

    @Test
    fun derivedStartsAtTheTransformOfTheCurrentValue() =
        runTest {
            val source = MutableStateFlow(3)

            val doubled = source.derived(backgroundScope) { it * 2 }

            // Available immediately, without waiting for a collector: a screen
            // reading it during its first composition must not see a null or a
            // placeholder.
            assertEquals(6, doubled.value)
        }

    @Test
    fun derivedFollowsTheSource() =
        runTest {
            val source = MutableStateFlow(1)
            val doubled = source.derived(backgroundScope) { it * 2 }
            val seen = mutableListOf<Int>()

            val job = backgroundScope.launch { doubled.collect { seen += it } }
            testScheduler.runCurrent()
            source.value = 5
            testScheduler.runCurrent()
            job.cancel()

            assertEquals(listOf(2, 10), seen)
        }

    @Test
    fun theExtensionFormBuildsTheSameThing() {
        val fromFunction = ars("lamp")
        val fromReceiver = "lamp".ars()

        assertEquals(fromFunction.value, fromReceiver.value)
        assertEquals("lamp", fromReceiver.asFlow.value)
    }

    @Test
    fun theExtensionWorksAsADelegate() {
        class Form {
            var page: Int by 1.ars()
        }

        val form = Form()
        assertEquals(1, form.page)

        form.page = 7

        assertEquals(7, form.page)
    }

    @Test
    fun eachCallBuildsItsOwnContainer() {
        val first = "".ars()
        val second = "".ars()

        first.value = "typed"

        // Why it is a function and not a property: as `val T.ars` every read
        // would land here, so a write would be invisible to the next read.
        // The parentheses put that in the caller's face.
        assertEquals("typed", first.value)
        assertEquals("", second.value)
    }
}
