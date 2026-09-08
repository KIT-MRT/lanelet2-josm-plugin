package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class EditActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in EditActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(7, EditActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(7, EditActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = EditActions.allSlots()
        assertEquals(EditActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(EditActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnTheMatchingMenus() {
        val registry = ActionRegistry()
        EditActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in EditActions.SLOT_SPECS) {
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
        EditActions.registerAll(registry)
        assertTrue(registry.get("lanelet_edit.check_lanelet_borders_TYPO") == null)
        assertTrue(
            "lanelet_edit.check_lanelet_borders_TYPO" !in
                registry.build(MenuId.UTILS).mapNotNull { it?.id },
        )
    }
}
