package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import javax.swing.Action
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources

class DependentActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in DependentActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(5, DependentActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(5, DependentActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = DependentActions.allSlots()
        assertEquals(DependentActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(DependentActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnTheMatchingMenus() {
        val registry = ActionRegistry()
        DependentActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in DependentActions.SLOT_SPECS) {
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
    fun metadataMatchesLl2ScriptRegistryVerbatim() {
        val expected = parseLl2Registry()
        assertEquals(5, expected.size, "ll2_script_registry.py should list 5 slots")
        val slots = DependentActions.allSlots().associateBy { it.id }
        val meta = DependentActions.SLOT_META.associateBy { it.id }
        for ((id, tuple) in expected) {
            val slot = slots.getValue(id)
            val m = meta.getValue(id)
            assertEquals(tuple.displayName, slot.action.getValue(Action.NAME), id)
            assertEquals(tuple.toolbarLabel, slot.toolbarLabel, id)
            assertEquals(tuple.iconPath, slot.iconName, id)
            assertEquals(tuple.shortcutKey, m.shortcutKey, "$id shortcutKey")
            assertEquals(tuple.displayName, m.displayName, id)
        }
    }

    @Test
    fun metadataIsNotInCoreRegistryPy() {
        val core = JythonSources.readText("core/launcher/registry.py")
        for (id in DependentActions.SLOT_SPECS.map { it.first }) {
            assertTrue(
                id !in core || "ll2_" !in id,
                "core/launcher/registry.py unexpectedly lists $id; source of truth is ll2_script_registry.py",
            )
        }
        for (id in listOf(
            "scripts.ll2_debug_routing_graph",
            "scripts.ll2_make_positive_ids",
            "scripts.ll2_merge_input_osm_files_launcher",
            "scripts.ll2_split_merged_osm_files_launcher",
        )) {
            assertTrue(id !in core, "$id must not be in core/launcher/registry.py")
        }
    }

    private data class RegistryTuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
    )

    private fun parseLl2Registry(): Map<String, RegistryTuple> {
        val text = JythonSources.readText("ll2_dependent/ll2_script_registry.py")
        val out = LinkedHashMap<String, RegistryTuple>()
        val entry = Regex(
            """"([^"]+)":\s*\(\s*"([^"]+)",\s*"([^"]+)",\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|"[^"]*")""",
        )
        for (m in entry.findAll(text)) {
            val id = m.groupValues[1]
            fun unquote(raw: String): String? =
                if (raw == "None") null else raw.trim('"')
            out[id] = RegistryTuple(
                displayName = m.groupValues[3],
                shortcutKey = unquote(m.groupValues[4]),
                toolbarLabel = unquote(m.groupValues[5]),
                iconPath = unquote(m.groupValues[6]),
            )
        }
        return out
    }
}
