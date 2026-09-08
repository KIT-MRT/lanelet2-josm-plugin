package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import javax.swing.Action

class HooksActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in HooksActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(2, HooksActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(2, HooksActions.SLOT_SPECS.size)
    }

    @Test
    fun registerAllPutsSlotsOnUtilsMenu() {
        val registry = ActionRegistry()
        HooksActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in HooksActions.SLOT_SPECS) {
            assertEquals(MenuId.UTILS, menu)
            assertTrue(id in utilsIds, id)
            assertTrue(id !in mapIds, id)
        }
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val expected = parseCoreRegistry()
        val slots = HooksActions.allSlots().associateBy { it.id }
        for (id in listOf("hooks.autotag_new_elements", "hooks.zoom_filter_window")) {
            val tuple = expected.getValue(id)
            val slot = slots.getValue(id)
            assertEquals(tuple.displayName, slot.action.getValue(Action.NAME), id)
            assertEquals(tuple.toolbarLabel, slot.toolbarLabel, id)
            assertEquals(tuple.iconPath, slot.iconName, id)
            assertEquals(tuple.shortcutKey, null, "$id shortcut")
        }
    }

    private data class Tuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
    )

    private fun parseCoreRegistry(): Map<String, Tuple> {
        val text = JythonSources.readText("core/launcher/registry.py")
        val out = linkedMapOf<String, Tuple>()
        val entry = Regex(
            """"([^"]+)":\s*\(\s*"([^"]+)",\s*"([^"]+)",\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|"[^"]*")""",
        )
        for (m in entry.findAll(text)) {
            fun unquote(raw: String): String? = if (raw == "None") null else raw.trim('"')
            out[m.groupValues[1]] = Tuple(
                displayName = m.groupValues[3],
                shortcutKey = unquote(m.groupValues[4]),
                toolbarLabel = unquote(m.groupValues[5]),
                iconPath = unquote(m.groupValues[6]),
            )
        }
        return out
    }
}
