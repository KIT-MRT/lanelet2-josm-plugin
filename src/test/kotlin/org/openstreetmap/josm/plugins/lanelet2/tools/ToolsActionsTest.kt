package org.openstreetmap.josm.plugins.lanelet2.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import javax.swing.Action

class ToolsActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in ToolsActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(3, ToolsActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(3, ToolsActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = ToolsActions.allSlots()
        assertEquals(ToolsActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(ToolsActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnUtilsMenu() {
        val registry = ActionRegistry()
        ToolsActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }.toSet()
        for ((id, menu) in ToolsActions.SLOT_SPECS) {
            assertEquals(MenuId.UTILS, menu, id)
            assertTrue(id in utilsIds, id)
            assertTrue(id !in mapIds, id)
        }
    }

    @Test
    fun metadataMatchesCoreRegistryPyVerbatim() {
        val expected = parseCoreRegistry()
        val wanted = ToolsActions.SLOT_SPECS.map { it.first }
        val slots = ToolsActions.allSlots().associateBy { it.id }
        for (id in wanted) {
            val tuple = expected.getValue(id)
            val slot = slots.getValue(id)
            assertEquals(tuple.displayName, slot.action.getValue(Action.NAME), id)
            assertEquals(tuple.toolbarLabel, slot.toolbarLabel, id)
            assertEquals(tuple.iconPath, slot.iconName, id)
            assertEquals(tuple.shortcutKey, null, "$id shortcutKey must be None in registry")
        }
    }

    @Test
    fun actionIconsExistOnTheTestClasspath() {
        for (slot in ToolsActions.allSlots()) {
            val icon = slot.iconName ?: continue
            val base = icon.substringAfterLast('/').substringAfterLast('\\')
            assertNotNull(
                javaClass.getResource("/images/lanelet2/$base"),
                "missing images/lanelet2/$base for ${slot.id}",
            )
        }
    }

    private data class RegistryTuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
    )

    private fun parseCoreRegistry(): Map<String, RegistryTuple> {
        val text = JythonSources.readText("core/launcher/registry.py")
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
