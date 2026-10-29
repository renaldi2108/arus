package io.github.renaldi2108.arus.core

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class SearchState(
    val results: List<String> = emptyList(),
)

private class SearchController(
    handle: SavedStateHandle,
) : ArusController<SearchState, Nothing>(SearchState(), savedStateHandle = handle) {
    var query: String by savedState("query", "")
    var page: Int by savedState("page", 1)

    fun search(text: String) {
        query = text
        page = 1
    }
}

/** Declares a saved value but is built without a handle. */
private class ForgetfulController : ArusController<SearchState, Nothing>(SearchState()) {
    @Suppress("unused")
    val query: String by savedState("query", "")
}

private class LaunchingController : ArusController<SearchState, Nothing>(SearchState()) {
    var ran = false

    fun work() = launch { ran = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SavedStateTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSavedValueStartsAtItsDefault() {
        val controller = SearchController(SavedStateHandle())

        assertEquals("", controller.query)
        assertEquals(1, controller.page)
    }

    @Test
    fun writingGoesStraightIntoTheHandle() {
        val handle = SavedStateHandle()
        val controller = SearchController(handle)

        controller.search("lamp")

        // Through the handle, not a cached copy: this is what the platform
        // actually writes out when the process is killed.
        assertEquals("lamp", handle.get<String>("query"))
        assertEquals(1, handle.get<Int>("page"))
    }

    @Test
    fun aRestoredHandleIsReadBack() {
        // What the system hands back after process death.
        val restored = SavedStateHandle(mapOf("query" to "desk", "page" to 4))

        val controller = SearchController(restored)

        assertEquals("desk", controller.query)
        assertEquals(4, controller.page)
    }

    @Test
    fun readingReflectsAWriteMadeDirectlyToTheHandle() {
        val handle = SavedStateHandle()
        val controller = SearchController(handle)

        // Write through the property first, so a caching implementation
        // would have something stale to return.
        controller.query = "lamp"
        handle["query"] = "chair"

        // No cache to go stale — the handle is the source of truth, which is
        // what makes a restore authoritative.
        assertEquals("chair", controller.query)
    }

    @Test
    fun twoKeysDoNotCollide() {
        val controller = SearchController(SavedStateHandle())

        controller.query = "lamp"
        controller.page = 7

        assertEquals("lamp", controller.query)
        assertEquals(7, controller.page)
    }

    @Test
    fun declaringASavedValueWithoutAHandleFailsByName() {
        val failure = assertFailsWith<IllegalStateException> { ForgetfulController() }

        // Fails when the controller is constructed, not on a user's phone
        // after a kill, and the message says how to fix it.
        val message = failure.message.orEmpty()
        assertTrue(message.contains("query"), message)
        assertTrue(message.contains("createSavedStateHandle"), message)
    }

    @Test
    fun launchRunsInTheControllerScope() =
        runTest {
            val controller = LaunchingController()

            controller.work()

            assertTrue(controller.ran)
        }
}
