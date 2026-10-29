package io.github.renaldi2108.arus.nav

import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Stand-ins for routes declared in three different feature modules.
@Serializable
private data object CatalogListRoute : Route

@Serializable
private data class CatalogDetailRoute(
    val itemId: Int,
) : Route

@Serializable
private data class BillingInvoiceRoute(
    val invoiceId: String,
) : Route

/** Mimics what a feature module's registration function looks like. */
private fun ArusEntries.catalogScreens() {
    screen<CatalogListRoute> { }
    screen<CatalogDetailRoute> { }
}

private fun ArusEntries.billingScreens() {
    screen<BillingInvoiceRoute> { }
}

class ArusEntriesTest {
    private fun declare(builder: ArusEntries.() -> Unit) = ArusEntries().apply(builder)

    /**
     * Round-trips a route through the module derived from the declarations,
     * which is what saved state does on a real process death.
     */
    private fun roundTrip(
        entries: ArusEntries,
        route: Route,
    ): NavKey {
        val config = entries.toSavedStateConfiguration()
        val json = Json { serializersModule = config.serializersModule }
        val polymorphic = PolymorphicSerializer(NavKey::class)
        return json.decodeFromString(polymorphic, json.encodeToString(polymorphic, route))
    }

    @Test
    fun declaringAScreenAlsoRegistersItsRouteForSavedState() {
        // The point of the whole design: one declaration, both jobs. Nothing
        // in this test mentions serialization.
        val entries = declare { catalogScreens() }

        assertEquals(CatalogListRoute, roundTrip(entries, CatalogListRoute))
        assertEquals(CatalogDetailRoute(7), roundTrip(entries, CatalogDetailRoute(7)))
    }

    @Test
    fun screensFromSeveralFeaturesAreAllRegistered() {
        val entries =
            declare {
                catalogScreens()
                billingScreens()
            }

        assertEquals(CatalogListRoute, roundTrip(entries, CatalogListRoute))
        assertEquals(BillingInvoiceRoute("inv-1"), roundTrip(entries, BillingInvoiceRoute("inv-1")))
        assertEquals(3, entries.screens.size)
    }

    @Test
    fun orderOfFeaturesDoesNotMatter() {
        val a =
            declare {
                catalogScreens()
                billingScreens()
            }
        val b =
            declare {
                billingScreens()
                catalogScreens()
            }

        assertEquals(
            roundTrip(a, CatalogDetailRoute(3)),
            roundTrip(b, CatalogDetailRoute(3)),
        )
    }

    @Test
    fun aRouteWithNoScreenCannotBeRestored() {
        // Billing was never declared, so its route must fail loudly rather than
        // silently vanish from a restored back stack.
        val entries = declare { catalogScreens() }

        val failure =
            assertFailsWith<Exception> {
                roundTrip(entries, BillingInvoiceRoute("inv-2"))
            }

        assertTrue(
            failure.message.orEmpty().contains("BillingInvoiceRoute"),
            "the error must name the unregistered route, was: ${failure.message}",
        )
    }

    @Test
    fun declaringTheSameRouteTwiceIsRejectedWithItsName() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                declare {
                    catalogScreens()
                    screen<CatalogListRoute> { }
                }
            }

        assertTrue(
            failure.message.orEmpty().contains("CatalogListRoute"),
            "the error must name the duplicate, was: ${failure.message}",
        )
    }

    @Test
    fun twoFeaturesClaimingTheSameRouteIsRejected() {
        // The realistic version of the mistake in a modular codebase.
        assertFailsWith<IllegalArgumentException> {
            declare {
                catalogScreens()
                screen<CatalogDetailRoute> { }
            }
        }
    }

    @Test
    fun aCustomSerializerCanBeSuppliedPerScreen() {
        val entries =
            declare {
                screen(CatalogDetailRoute.serializer()) { }
            }

        assertEquals(CatalogDetailRoute(9), roundTrip(entries, CatalogDetailRoute(9)))
    }

    @Test
    fun sceneCarriesArbitraryMetadataToTheEntry() {
        // How a bottom sheet works without the library depending on one: the
        // app supplies both the metadata and the strategy that reads it.
        val entries =
            declare {
                scene<CatalogDetailRoute>(mapOf("sheet" to true)) { }
            }

        assertEquals(mapOf("sheet" to true), entries.screens.single().metadata)
    }

    @Test
    fun sceneStillRegistersItsRouteForSavedState() {
        val entries =
            declare {
                scene<CatalogDetailRoute>(mapOf("sheet" to true)) { }
            }

        assertEquals(CatalogDetailRoute(4), roundTrip(entries, CatalogDetailRoute(4)))
    }

    @Test
    fun sceneSharesTheDuplicateCheckWithScreen() {
        assertFailsWith<IllegalArgumentException> {
            declare {
                screen<CatalogDetailRoute> { }
                scene<CatalogDetailRoute>(emptyMap()) { }
            }
        }
    }

    @Test
    fun dialogAttachesTheMetadataTheDialogStrategyLooksFor() {
        val entries =
            declare {
                dialog<CatalogDetailRoute> { }
            }

        // Same keys the built-in DialogSceneStrategy reads. Declared this way
        // rather than asserted by rendering, so the wiring is checked without
        // a device.
        assertEquals(
            DialogSceneStrategy.dialog().keys,
            entries.screens
                .single()
                .metadata.keys,
        )
    }

    @Test
    fun dialogPassesItsPropertiesThrough() {
        val entries =
            declare {
                dialog<CatalogDetailRoute>(
                    DialogProperties(dismissOnClickOutside = false),
                ) { }
            }

        assertTrue(
            entries.screens
                .single()
                .metadata
                .isNotEmpty(),
        )
    }

    @Test
    fun aDialogRouteIsStillRegisteredForSavedState() {
        // The thing that makes a dialog an ordinary entry rather than a
        // floating special case: it comes back after process death.
        val entries =
            declare {
                dialog<CatalogDetailRoute> { }
            }

        assertEquals(CatalogDetailRoute(5), roundTrip(entries, CatalogDetailRoute(5)))
    }

    @Test
    fun aPerRouteTransitionIsRecordedAsEntryMetadata() {
        val entries =
            declare {
                screen<CatalogDetailRoute>(
                    transition = { null },
                ) { }
            }

        assertEquals(
            NavDisplay.transitionSpec { null }.keys,
            entries.screens
                .single()
                .metadata.keys,
        )
    }

    @Test
    fun aPopTransitionIsRecordedAlongsideTheEnterTransition() {
        val entries =
            declare {
                screen<CatalogDetailRoute>(
                    transition = { null },
                    popTransition = { null },
                ) { }
            }

        val expected =
            NavDisplay.transitionSpec { null }.keys +
                NavDisplay.popTransitionSpec { null }.keys
        assertEquals(
            expected,
            entries.screens
                .single()
                .metadata.keys,
        )
    }

    @Test
    fun aScreenWithNoTransitionCarriesNoMetadata() {
        val entries =
            declare {
                screen<CatalogDetailRoute> { }
            }

        // An empty map, not a map of nulls: the host must fall through to its
        // default animation rather than override it with nothing.
        assertTrue(
            entries.screens
                .single()
                .metadata
                .isEmpty(),
        )
    }

    @Test
    fun bottomSheetDialogMarksTheEntryForTheSheetStrategy() {
        val entries =
            declare {
                bottomSheetDialog<CatalogDetailRoute> { }
            }

        // Same key the built-in strategy reads, asserted without rendering.
        assertEquals(
            bottomSheetMetadata(BottomSheetProperties()).keys,
            entries.screens
                .single()
                .metadata.keys,
        )
    }

    @Test
    fun bottomSheetPropertiesTravelToTheEntry() {
        val entries =
            declare {
                bottomSheetDialog<CatalogDetailRoute>(
                    BottomSheetProperties(skipPartiallyExpanded = true, showDragHandle = false),
                ) { }
            }

        val carried =
            entries.screens
                .single()
                .metadata.values
                .single() as BottomSheetProperties
        assertTrue(carried.skipPartiallyExpanded)
        assertEquals(false, carried.showDragHandle)
    }

    @Test
    fun bottomSheetDefaultsLeaveEveryLookToMaterial() {
        val defaults = BottomSheetProperties()

        // Null means "whatever Material does", so a future Material default is
        // picked up instead of frozen at the version Arus was built against.
        assertNull(defaults.shape)
        assertNull(defaults.containerColor)
        assertNull(defaults.scrimColor)
        assertNull(defaults.maxWidth)
        assertTrue(defaults.showDragHandle)
        assertTrue(defaults.dismissOnTapOutside)
    }

    @Test
    fun aBottomSheetRouteIsStillRegisteredForSavedState() {
        val entries =
            declare {
                bottomSheetDialog<CatalogDetailRoute> { }
            }

        assertEquals(CatalogDetailRoute(6), roundTrip(entries, CatalogDetailRoute(6)))
    }

    @Test
    fun aSheetAndADialogAreToldApartByTheirMetadata() {
        val sheet = declare { bottomSheetDialog<CatalogDetailRoute> { } }
        val dialog = declare { dialog<CatalogDetailRoute> { } }

        assertTrue(
            sheet.screens
                .single()
                .metadata.keys !=
                dialog.screens
                    .single()
                    .metadata.keys,
            "a sheet must not be claimed by the dialog strategy, or vice versa",
        )
    }
}
