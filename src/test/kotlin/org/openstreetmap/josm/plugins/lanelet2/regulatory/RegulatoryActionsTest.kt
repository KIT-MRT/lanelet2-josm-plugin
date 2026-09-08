package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class RegulatoryActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in RegulatoryActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(7, RegulatoryActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(7, RegulatoryActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = RegulatoryActions.allSlots()
        assertEquals(RegulatoryActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(RegulatoryActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnTheMatchingMenus() {
        val registry = ActionRegistry()
        RegulatoryActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in RegulatoryActions.SLOT_SPECS) {
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
        RegulatoryActions.registerAll(registry)
        assertTrue(registry.get("regulatory.create_traffic_light_relation_TYPO") == null)
        assertTrue(
            "regulatory.create_traffic_light_relation_TYPO" !in
                registry.build(MenuId.MAP).mapNotNull { it?.id },
        )
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val slots = RegulatoryActions.allSlots().associateBy { it.id }
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
            "regulatory.convert_traffic_light_bikes_to_traffic_light",
            "Convert bike traffic lights to traffic_light + participants",
            null,
            null,
        )
        check(
            "regulatory.create_traffic_light_relation",
            "Create Traffic Light Relation",
            "TL",
            "icons/traffic_light.svg",
        )
        check(
            "regulatory.create_traffic_sign_relation",
            "Create Traffic Sign Relation",
            "TS",
            "icons/traffic_sign.svg",
        )
        check(
            "regulatory.create_speed_limit_relation",
            "Create Speed Limit Relation",
            "SL",
            "icons/speed_limit.svg",
        )
        check(
            "regulatory.create_right_of_way_relation",
            "Create Right of Way Relation",
            "RoW",
            "icons/right_of_way.svg",
        )
        check(
            "regulatory.debug_regulatory_element_connections",
            "Visualize Regulatory Element Connections",
            "Viz RegE",
            "icons/viz_reg_elem.svg",
        )
        check(
            "regulatory.debug_right_of_way_wizard",
            "Visualize Right of Way Wizard",
            "Viz RoW",
            "icons/viz_row.svg",
        )
    }
}
