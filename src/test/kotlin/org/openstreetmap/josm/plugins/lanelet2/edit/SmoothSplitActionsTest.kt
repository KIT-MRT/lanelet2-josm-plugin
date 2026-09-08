package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SmoothSplitActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in SmoothSplitActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(5, SmoothSplitActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(5, SmoothSplitActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = SmoothSplitActions.allSlots()
        assertEquals(SmoothSplitActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(SmoothSplitActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnTheMatchingMenus() {
        val registry = ActionRegistry()
        SmoothSplitActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in SmoothSplitActions.SLOT_SPECS) {
            when (menu) {
                MenuId.UTILS -> {
                    assertTrue(id in utilsIds, id)
                    assertTrue(id !in mapIds, id)
                }
                MenuId.MAP -> {
                    assertTrue(id in mapIds, id)
                    assertTrue(id !in utilsIds, id)
                }
            }
        }
    }

    @Test
    fun typoIdWouldVanishFromMenus() {
        val registry = ActionRegistry()
        SmoothSplitActions.registerAll(registry)
        assertTrue(registry.get("lanelet_edit.create_lanelet_relation_TYPO") == null)
        assertTrue(
            "lanelet_edit.create_lanelet_relation_TYPO" !in
                registry.build(MenuId.MAP).mapNotNull { it?.id },
        )
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val slots = SmoothSplitActions.allSlots().associateBy { it.id }
        fun check(
            id: String,
            displayName: String,
            toolbarLabel: String?,
            iconPath: String?,
        ) {
            val slot = slots.getValue(id)
            assertEquals(displayName, slot.action.getValue(javax.swing.Action.NAME), id)
            assertEquals(toolbarLabel, slot.toolbarLabel, id)
            assertEquals(iconPath, slot.iconName, id)
        }
        check(
            "lanelet_edit.create_lanelet_relation",
            "Create Lanelet(s) (starting w. 2 l/r linestrings)",
            "c LL",
            "icons/create_lanelet.svg",
        )
        check(
            "lanelet_edit.split_bidirectional_lanelets_on_centerline",
            "Split bidirectional lanelets on virtual centerline",
            "Spl Bi",
            "icons/split_bidirectional_centerline.svg",
        )
        check(
            "lanelet_edit.smooth_center_lanelet",
            "Smooth Center Lanelet (batch)",
            "Smth",
            "icons/smooth_batch.svg",
        )
        check(
            "lanelet_edit.smooth_center_from_center",
            "Smooth Center from Selection (1 center)",
            "Smth1",
            "icons/smooth_one.svg",
        )
        check(
            "lanelet_edit.split_way_at_selected_nodes",
            "Split Ways at Selected Nodes",
            "Split",
            "icons/split_way.svg",
        )
    }
}
