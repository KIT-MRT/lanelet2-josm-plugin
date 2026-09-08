package org.openstreetmap.josm.plugins.lanelet2.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SelectionActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in SelectionActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(2, SelectionActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(2, SelectionActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = SelectionActions.allSlots()
        assertEquals(SelectionActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(SelectionActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnTheMatchingMenus() {
        val registry = ActionRegistry()
        SelectionActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in SelectionActions.SLOT_SPECS) {
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
        SelectionActions.registerAll(registry)
        assertTrue(registry.get("selection.select_lanelets_from_linestrings_TYPO") == null)
        assertTrue(
            "selection.select_lanelets_from_linestrings_TYPO" !in
                registry.build(MenuId.MAP).mapNotNull { it?.id },
        )
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val slots = SelectionActions.allSlots().associateBy { it.id }
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
            "selection.select_lanelets_from_linestrings",
            "Select Lanelets from Linestrings",
            "Sel LL",
            "icons/sel_lanelets.svg",
        )
        check(
            "selection.select_relations_from_linestrings",
            "Select Relations from Linestrings",
            "Sel Rel",
            "icons/sel_relations.svg",
        )
    }
}
