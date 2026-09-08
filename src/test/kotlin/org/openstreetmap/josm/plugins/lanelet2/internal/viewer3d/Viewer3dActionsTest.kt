package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import javax.swing.Action
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources

class Viewer3dActionsTest {
    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun registerAllInsertsAfterZoomFilterHook() {
        val registry = ActionRegistry()
        registry.register(
            ActionSlot(
                id = "hooks.zoom_filter_window",
                action = object : org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction("z", null, null, null) {
                    override fun actionPerformed(e: java.awt.event.ActionEvent) {}
                },
                toolbarLabel = null,
                iconName = null,
                menu = MenuId.UTILS,
            ),
        )
        Viewer3dActions.registerAll(registry)
        val ordered = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        val idx = ordered.indexOf(Viewer3dActions.SLOT_ID)
        assertTrue(idx >= 0)
        assertEquals("hooks.zoom_filter_window", ordered[idx - 1])
    }

    @Test
    fun metadataMatchesInternalScriptRegistryVerbatim() {
        val tuple = parseRegistry()[Viewer3dActions.SLOT_ID]!!
        val slot = Viewer3dActions.slot()
        assertEquals(tuple.displayName, slot.action.getValue(Action.NAME))
        assertEquals(tuple.toolbarLabel, slot.toolbarLabel)
        assertEquals(tuple.shortcutKey, null)
        // Jython shipped no icon; the plugin adds one so the toolbar button
        // can show the same selected-state highlight as autotag / zoom filter.
        assertEquals(null, tuple.iconPath)
        assertEquals(Viewer3dActions.ICON_PATH, slot.iconName)
        assertTrue(slot.toolbarHighlight != null)
        assertFalse(slot.toolbarHighlight!!())
        Viewer3dSettings.saveConfig(enabled = true, host = "127.0.0.1", ingestPort = 8766)
        assertTrue(slot.toolbarHighlight!!())
    }

    @Test
    fun slotIsNotRegisteredByInternalActions() {
        val core = JythonSources.readText("internal/internal_script_registry.py")
        assertTrue(Viewer3dActions.SLOT_ID in core)
        assertFalse(Viewer3dActions.SLOT_ID in JythonSources.readText("core/launcher/registry.py"))
    }

    private data class Tuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
    )

    private fun parseRegistry(): Map<String, Tuple> {
        val text = JythonSources.readText("internal/internal_script_registry.py")
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
