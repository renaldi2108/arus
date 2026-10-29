package io.github.renaldi2108.arus

import android.os.Debug
import androidx.navigation3.runtime.NavBackStack
import io.github.renaldi2108.arus.core.ArusController
import io.github.renaldi2108.arus.core.UiEffect
import io.github.renaldi2108.arus.core.ars
import io.github.renaldi2108.arus.nav.ArusNavigator
import io.github.renaldi2108.arus.nav.NavInspector
import io.github.renaldi2108.arus.nav.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

@Serializable
private data object AllocHomeRoute : Route

@Serializable
private data object AllocNextRoute : Route

private data class Counter(
    val value: Int = 0,
)

private data class Tick(
    val n: Int,
) : UiEffect

private class CountingController : ArusController<Counter, Tick>(Counter()) {
    fun bump() = setState { copy(value = value + 1) }

    fun fire(n: Int) = emit(Tick(n))
}

/**
 * What `setState` and `emit` cost in allocations.
 *
 * The leak question was already answered — Java heap is flat across 40
 * controller lifecycles. This is the other half: whether the per-call cost is
 * what a reader would expect from the code, or whether something allocates
 * behind their back.
 *
 * Counts come from `Debug.startAllocCounting`, which is approximate and counts
 * the whole process, so the assertions are deliberately loose: they catch an
 * order-of-magnitude regression, not a single extra object.
 */
class AllocationTest {
    private fun allocationsDuring(
        warmUp: () -> Unit,
        block: () -> Unit,
    ): Int {
        // Warm up first: class loading and the first StateFlow subscription
        // allocate once and would otherwise be charged to the measurement.
        warmUp()
        Runtime.getRuntime().gc()
        Debug.startAllocCounting()
        try {
            block()
        } finally {
            Debug.stopAllocCounting()
        }
        return Debug.getGlobalAllocCount()
    }

    @Test
    fun setStateCostsAboutWhatAStateFlowWriteCosts() {
        val iterations = 10_000

        val controller = CountingController()
        val arus =
            allocationsDuring(
                warmUp = { repeat(100) { controller.bump() } },
                block = { repeat(iterations) { controller.bump() } },
            )

        val flow = MutableStateFlow(Counter())
        val baseline =
            allocationsDuring(
                warmUp = { repeat(100) { flow.value = flow.value.copy(value = flow.value.value + 1) } },
                block = {
                    repeat(iterations) { flow.value = flow.value.copy(value = flow.value.value + 1) }
                },
            )

        // Measured: 2 objects per `setState`, against 1 for a bare StateFlow
        // write — the `copy()` both make, plus one for the reducer call. The
        // bound is set at 3 per call so a real regression trips it while
        // ordinary measurement noise does not.
        assertTrue(
            arus < iterations * 3,
            "setState allocated $arus vs a plain StateFlow write $baseline over $iterations calls",
        )
    }

    @Test
    fun emittingWithNoCollectorDoesNotAllocatePerCallBeyondTheEffect() {
        val iterations = 10_000
        val controller = CountingController()

        val allocated =
            allocationsDuring(
                warmUp = { repeat(100) { controller.fire(it) } },
                block = { repeat(iterations) { controller.fire(it) } },
            )

        // Measured: ~1.1 objects per call — the effect itself, plus occasional
        // channel bookkeeping. The buffer is bounded and drops the oldest, so
        // emitting into the void does not grow with the number of calls.
        assertTrue(
            allocated < iterations * 2,
            "emit allocated $allocated objects over $iterations calls",
        )
    }

    @Test
    fun navigationAllocatesNothingExtraWithNoInspectorAttached() {
        val iterations = 10_000

        val plain = ArusNavigator(NavBackStack<Route>(AllocHomeRoute))
        val withoutInspector =
            allocationsDuring(
                warmUp = {
                    repeat(100) {
                        plain.push(AllocNextRoute)
                        plain.pop()
                    }
                },
                block = {
                    repeat(iterations) {
                        plain.push(AllocNextRoute)
                        plain.pop()
                    }
                },
            )

        val watched = ArusNavigator(NavBackStack<Route>(AllocHomeRoute))
        watched.inspector = NavInspector()
        val withInspector =
            allocationsDuring(
                warmUp = {
                    repeat(100) {
                        watched.push(AllocNextRoute)
                        watched.pop()
                    }
                },
                block = {
                    repeat(iterations) {
                        watched.push(AllocNextRoute)
                        watched.pop()
                    }
                },
            )

        // The claim written next to `publish`: with nothing listening and
        // logging off, a navigation does not build the event at all. An
        // attached inspector must therefore cost measurably more — if it does
        // not, the guard is not doing what the comment says.
        assertTrue(
            withInspector > withoutInspector,
            "attaching an inspector should cost allocations: " +
                "without=$withoutInspector with=$withInspector",
        )
    }

    @Test
    fun publishingWithNobodyListeningAllocatesNothing() {
        val iterations = 10_000
        val nav = ArusNavigator(NavBackStack<Route>(AllocHomeRoute))
        val message = AllocPing

        val quiet =
            allocationsDuring(
                warmUp = { repeat(100) { nav.publish(message) } },
                block = { repeat(iterations) { nav.publish(message) } },
            )

        // The claim in the KDoc: `publish` on a hot path is safe because the
        // bus does not exist until somebody subscribes. Anything near one
        // object per call would mean it does.
        assertTrue(
            quiet < iterations,
            "publish with no listener allocated $quiet objects over $iterations calls",
        )
    }

    @Test
    fun theExtensionFormCostsTheSameAsTheFunction() {
        val iterations = 10_000

        val viaFunction =
            allocationsDuring(
                warmUp = { repeat(100) { ars("x") } },
                block = { repeat(iterations) { ars("x") } },
            )

        val viaReceiver =
            allocationsDuring(
                warmUp = { repeat(100) { "x".ars() } },
                block = { repeat(iterations) { "x".ars() } },
            )

        // `"x".ars()` is `ars("x")` with the argument on the left. If the two
        // differ by more than measurement noise, one of them is doing
        // something the source does not show.
        val difference = kotlin.math.abs(viaFunction - viaReceiver)
        assertTrue(
            difference < iterations / 10,
            "ars(x)=$viaFunction vs x.ars()=$viaReceiver over $iterations calls",
        )
    }
}

/** A message with no fields, so the measurement is the bus and not the payload. */
private data object AllocPing
