package org.openstreetmap.josm.plugins.lanelet2.notes

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

class NotesActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun everyRegisteredSlotIdIsInBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in NotesActions.SLOT_SPECS) {
            assertTrue(id in known, "slot id '$id' is missing from BASE_UTILS_ORDER / BASE_MAP_ORDER")
        }
        assertEquals(1, NotesActions.SLOT_SPECS.size)
    }

    @Test
    fun registerAllPutsSlotOnUtilsMenu() {
        val registry = ActionRegistry()
        NotesActions.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        val mapIds = registry.build(MenuId.MAP).mapNotNull { it?.id }
        assertTrue(NotesActions.SLOT_ID in utilsIds)
        assertTrue(NotesActions.SLOT_ID !in mapIds)
    }

    @Test
    fun metadataMatchesJythonRegistryVerbatim() {
        val text = JythonSources.readText("core/launcher/registry.py")
        val entry = Regex(
            """"notes\.notes_dialog":\s*\(\s*"([^"]+)",\s*"([^"]+)",\s*(None|"[^"]*"),\s*(None|"[^"]*"),\s*(None|"[^"]*")""",
        ).find(text)
        requireNotNull(entry) { "notes.notes_dialog missing from core/launcher/registry.py" }
        fun unquote(raw: String): String? = if (raw == "None") null else raw.trim('"')
        val module = entry.groupValues[1]
        val displayName = entry.groupValues[2]
        val shortcut = unquote(entry.groupValues[3])
        val toolbar = unquote(entry.groupValues[4])
        val icon = unquote(entry.groupValues[5])

        val slot = NotesActions.allSlots().single()
        assertEquals(module, slot.id)
        assertEquals(displayName, slot.action.getValue(Action.NAME))
        assertEquals(toolbar, slot.toolbarLabel)
        assertEquals(icon, slot.iconName)
        assertEquals(null, shortcut)
        assertEquals(MenuId.UTILS, slot.menu)
        assertEquals(NotesActions.DISPLAY_NAME, displayName)
        assertEquals(NotesActions.TOOLBAR_LABEL, toolbar)
        assertEquals(NotesActions.ICON_PATH, icon)
    }
}
