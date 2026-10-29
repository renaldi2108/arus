package io.github.renaldi2108.arus.effects

import io.github.renaldi2108.arus.core.ArusController
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class EffectEmitter : ArusController<Unit, ArusEffect>(Unit) {
    fun shout(effect: ArusEffect) = emit(effect)
}

class BuiltInEffectsTest {
    @Test
    fun aSnackbarDefaultsToShortWithNoAction() {
        val effect = ArusEffect.Snackbar("Saved")

        assertEquals("Saved", effect.message)
        assertNull(effect.actionLabel)
        assertEquals(SnackbarDuration.Short, effect.duration)
    }

    @Test
    fun aDialogDefaultsToASingleConfirmButton() {
        val effect = ArusEffect.Dialog(title = "Delete?", message = "This cannot be undone.")

        assertEquals("OK", effect.confirmLabel)
        // Null, not empty: a one-button dialog has no dismiss control at all,
        // which a blank label would render as an invisible tap target.
        assertNull(effect.dismissLabel)
    }

    @Test
    fun everyDurationIsAddressableByName() {
        assertEquals(
            listOf("Short", "Long", "Indefinite"),
            SnackbarDuration.entries.map { it.name },
        )
    }

    @Test
    fun builtInEffectsTravelThroughAController() =
        runTest {
            val controller = EffectEmitter()
            val sent =
                listOf(
                    ArusEffect.Snackbar("Undo?", actionLabel = "Undo", duration = SnackbarDuration.Long),
                    ArusEffect.Dialog("Delete?", "Gone for good.", dismissLabel = "Cancel"),
                )

            sent.forEach(controller::shout)

            assertEquals(sent, controller.effects.take(sent.size).toList())
        }
}
