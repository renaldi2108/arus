package io.github.renaldi2108.arus.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * How a [ArusEntries.bottomSheetDialog] destination looks and behaves.
 *
 * Every value defaults to `null`, meaning "whatever Material 3 does". Only
 * what you name is overridden, so a future Material default is picked up
 * rather than frozen at the version Arus was built against.
 *
 * ```kotlin
 * bottomSheetDialog<FilterRoute>(
 *     BottomSheetProperties(skipPartiallyExpanded = true, showDragHandle = false),
 * ) { route -> FilterSheet(viewModel { FilterController(route, navigator) }) }
 * ```
 *
 * @param skipPartiallyExpanded open straight to full height instead of resting
 *   at half. Right for a short sheet; wrong for a long scrollable one.
 * @param showDragHandle draw the grab handle at the top.
 * @param dismissOnBackPress let a back press dismiss the sheet. Leaving this
 *   `true` is almost always right — the entry is popped either way, this only
 *   decides whether the sheet animates out first.
 * @param dismissOnTapOutside let a tap on the scrim dismiss the sheet. Set
 *   `false` for a decision the user must actually make.
 * @param gesturesEnabled allow dragging the sheet. `false` pins it in place.
 * @param shape the sheet outline.
 * @param containerColor the sheet background.
 * @param contentColor the colour content inherits; defaults to the one
 *   Material pairs with [containerColor].
 * @param scrimColor the dimmed area behind the sheet.
 * @param tonalElevation elevation overlay applied to [containerColor].
 * @param maxWidth caps the sheet on a wide screen.
 * @param windowInsets insets the sheet respects.
 *
 * Material's `confirmValueChange` is deliberately **not** exposed: its
 * parameter is `SheetValue`, an experimental Material type, and putting it in
 * a signature here would drag that experimental status into Arus's stable
 * ABI — every caller would need an opt-in for a hook almost nobody uses.
 * [gesturesEnabled] covers the common case of "do not let the user drag
 * this"; anything finer is a reason to reach for [ArusEntries.scene] and your
 * own strategy.
 */
@Immutable
// A properties holder is one parameter per thing it configures; collapsing
// them into a smaller object would just move the list somewhere else. Material
// 3's own DialogProperties and ModalBottomSheet have the same shape.
@Suppress("LongParameterList")
public class BottomSheetProperties(
    public val skipPartiallyExpanded: Boolean = false,
    public val showDragHandle: Boolean = true,
    public val dismissOnBackPress: Boolean = true,
    public val dismissOnTapOutside: Boolean = true,
    public val gesturesEnabled: Boolean = true,
    public val shape: Shape? = null,
    public val containerColor: Color? = null,
    public val contentColor: Color? = null,
    public val scrimColor: Color? = null,
    public val tonalElevation: Dp? = null,
    public val maxWidth: Dp? = null,
    public val windowInsets: (@Composable () -> WindowInsets)? = null,
)

/**
 * Presents entries declared with [ArusEntries.bottomSheetDialog].
 *
 * Installed by [ArusHost] automatically, after any strategy the app supplies —
 * so an app can still claim a sheet entry for itself if it wants a different
 * presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class ArusBottomSheetStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val top = entries.lastOrNull() ?: return null

        // Only entries declared with `bottomSheetDialog` are ours; anything
        // else falls through to the next strategy, and finally to a screen.
        return top.metadata[BottomSheetKeyImpl]?.let { properties ->
            BottomSheetScene(
                key = top.contentKey,
                entry = top,
                previousEntries = entries.dropLast(1),
                properties = properties,
                onBack = onBack,
            )
        }
    }
}

private object BottomSheetKeyImpl : NavMetadataKey<BottomSheetProperties>

/**
 * Entry metadata marking a destination as a bottom sheet.
 *
 * `@PublishedApi internal` because `bottomSheetDialog` is `inline` — the
 * reified type parameter is what makes the route's serializer derivable
 * without the caller naming it, and an inline function cannot reach a purely
 * internal declaration.
 */
@PublishedApi
internal fun bottomSheetMetadata(properties: BottomSheetProperties): Map<String, Any> =
    metadata { put(BottomSheetKeyImpl, properties) }

@OptIn(ExperimentalMaterial3Api::class)
private class BottomSheetScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    private val properties: BottomSheetProperties,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    /**
     * What stays composed underneath.
     *
     * This is what makes a sheet an overlay rather than a screen: the entry
     * below keeps its state, so dismissing the sheet does not reset the list
     * the user opened it from.
     */
    override val overlaidEntries: List<NavEntry<T>> = previousEntries

    override val content: @Composable () -> Unit = {
        // The entry owns its own lifecycle, so a controller inside the sheet is
        // scoped to the entry and not to whatever opened it.
        val lifecycleOwner = rememberLifecycleOwner()
        val sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = properties.skipPartiallyExpanded,
            )

        val container = properties.containerColor ?: BottomSheetDefaults.ContainerColor

        ModalBottomSheet(
            onDismissRequest = onBack,
            sheetState = sheetState,
            sheetMaxWidth = properties.maxWidth ?: BottomSheetDefaults.SheetMaxWidth,
            sheetGesturesEnabled = properties.gesturesEnabled,
            shape = properties.shape ?: BottomSheetDefaults.ExpandedShape,
            containerColor = container,
            contentColor = properties.contentColor ?: contentColorFor(container),
            tonalElevation = properties.tonalElevation ?: ZeroElevation,
            scrimColor = properties.scrimColor ?: BottomSheetDefaults.ScrimColor,
            dragHandle = if (properties.showDragHandle) ({ BottomSheetDefaults.DragHandle() }) else null,
            contentWindowInsets = properties.windowInsets ?: { BottomSheetDefaults.windowInsets },
            properties =
                ModalBottomSheetProperties(
                    shouldDismissOnBackPress = properties.dismissOnBackPress,
                    shouldDismissOnClickOutside = properties.dismissOnTapOutside,
                ),
        ) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                entry.Content()
            }
        }
    }
}

private val ZeroElevation: Dp = Dp(0f)

@Composable
private fun contentColorFor(background: Color): Color = androidx.compose.material3.contentColorFor(background)
