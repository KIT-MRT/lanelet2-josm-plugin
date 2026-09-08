package org.openstreetmap.josm.plugins.lanelet2.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import javax.swing.Action

class SettingsActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in SettingsActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(6, SettingsActions.SLOT_SPECS.map { it.first }.toSet().size)
        assertEquals(6, SettingsActions.SLOT_SPECS.size)
    }

    @Test
    fun slotSpecsMatchAllSlotsAndMenuIds() {
        val slots = SettingsActions.allSlots()
        assertEquals(SettingsActions.SLOT_SPECS.map { it.first }, slots.map { it.id })
        for (i in slots.indices) {
            assertEquals(SettingsActions.SLOT_SPECS[i].second, slots[i].menu, slots[i].id)
        }
    }

    @Test
    fun registerAllPutsSlotsOnUtilsMenu() {
        val registry = ActionRegistry()
        SettingsActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }.toSet()
        for ((id, menu) in SettingsActions.SLOT_SPECS) {
            assertEquals(MenuId.UTILS, menu, id)
            assertTrue(id in utilsIds, id)
        }
    }

    @Test
    fun variantSlotsUseToolbarToggleGroups() {
        val slots = SettingsActions.allSlots().associateBy { it.id }
        assertEquals(
            ActionSlot.GROUP_LANELET_DEFAULT_SUBTYPE,
            slots.getValue("settings_ui.set_lanelet_default_subtype::road").toolbarGroup,
        )
        assertEquals(
            ActionSlot.GROUP_LANELET_DEFAULT_ONEWAY,
            slots.getValue("settings_ui.set_lanelet_default_one_way::yes").toolbarGroup,
        )
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val expected = parseSettingsRegistry()
        val slots = SettingsActions.allSlots().associateBy { it.id }
        val variantIds = listOf(
            "settings_ui.set_lanelet_default_subtype::road",
            "settings_ui.set_lanelet_default_subtype::bicycle_lane",
            "settings_ui.set_lanelet_default_subtype::crosswalk",
            "settings_ui.set_lanelet_default_one_way::yes",
            "settings_ui.set_lanelet_default_one_way::no",
        )
        for (id in variantIds) {
            val tuple = expected.getValue(id)
            val slot = slots.getValue(id)
            assertEquals(tuple.displayName, slot.action.getValue(Action.NAME), id)
            assertEquals(tuple.shortcutKey, null, "$id shortcutKey")
            assertEquals(tuple.toolbarLabel, slot.toolbarLabel, id)
            assertEquals(tuple.iconPath, slot.iconName, id)
            assertEquals(tuple.variantArg, variantFromId(id), "$id variant")
            assertEquals(tuple.toolbarGroup, slot.toolbarGroup, "$id toolbarGroup")
        }
    }

    @Test
    fun setSubtypeNoopsWhenUnchanged() {
        LaneletSettings.setLaneletDefaultSubtype("road")
        assertFalse(LaneletSettings.setLaneletDefaultSubtype("road"))
        LaneletSettings.setLaneletDefaultSubtype("bicycle_lane")
        assertEquals("bicycle_lane", LaneletSettings.getLaneletDefaultSubtype())
    }

    @Test
    fun setOneWayNoopsWhenUnchanged() {
        LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_YES)
        assertFalse(LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_YES))
    }

    private fun variantFromId(id: String): String? {
        val i = id.indexOf("::")
        return if (i >= 0) id.substring(i + 2) else null
    }

    private data class RegistryTuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
        val variantArg: String?,
        val toolbarGroup: String?,
    )

    private fun parseSettingsRegistry(): Map<String, RegistryTuple> {
        val text = JythonSources.readText("core/launcher/registry.py")
        val out = LinkedHashMap<String, RegistryTuple>()
        val entry = Regex(
            """"(settings_ui\.[^"]+)":\s*\(\s*"([^"]+)",\s*"([^"]+)",\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|TOOLBAR_TOGGLE_GROUP_[A-Z_]+)""",
        )
        for (m in entry.findAll(text)) {
            val id = m.groupValues[1]
            fun unquote(raw: String): String? =
                if (raw == "None") null else raw.trim('"')
            fun toggleGroup(raw: String): String? = when (raw) {
                "None" -> null
                "TOOLBAR_TOGGLE_GROUP_SUBTYPE" -> ActionSlot.GROUP_LANELET_DEFAULT_SUBTYPE
                "TOOLBAR_TOGGLE_GROUP_ONEWAY" -> ActionSlot.GROUP_LANELET_DEFAULT_ONEWAY
                else -> raw
            }
            out[id] = RegistryTuple(
                displayName = m.groupValues[3],
                shortcutKey = unquote(m.groupValues[4]),
                toolbarLabel = unquote(m.groupValues[5]),
                iconPath = unquote(m.groupValues[6]),
                variantArg = unquote(m.groupValues[7]),
                toolbarGroup = toggleGroup(m.groupValues[8]),
            )
        }
        return out
    }
}
