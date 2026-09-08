package org.openstreetmap.josm.plugins.lanelet2.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.edit.EditActions
import org.openstreetmap.josm.plugins.lanelet2.dependent.DependentActions
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import javax.swing.Action
import org.openstreetmap.josm.plugins.lanelet2.testutil.JythonSources

class InternalActionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun slotIdsAreNotInOssBaseOrder() {
        val known = (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .toSet()
        for ((id, _) in InternalActions.SLOT_SPECS) {
            assertFalse(id in known, "internal id '$id' must be spliced via insertAfter, not BASE_*_ORDER")
        }
        assertEquals(2, InternalActions.SLOT_SPECS.size)
    }

    @Test
    fun registerAllInsertsAfterJythonAnchors() {
        val registry = ActionRegistry()
        EditActions.registerAll(registry)
        DependentActions.registerAll(registry)
        InternalActions.registerAll(registry)
        val utils = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        val filterIdx = utils.indexOf(InternalActions.FILTER_ID)
        val gitIdx = utils.indexOf(InternalActions.GIT_ID)
        assertTrue(filterIdx >= 0, "filter-broken missing from utils menu")
        assertTrue(gitIdx >= 0, "git commit missing from utils menu")
        assertEquals(
            "lanelet_edit.check_lanelet_borders",
            utils[filterIdx - 1],
        )
        assertEquals(
            "scripts.ll2_make_positive_ids",
            utils[gitIdx - 1],
        )
        assertTrue(InternalActions.FILTER_ID !in registry.build(MenuId.MAP).mapNotNull { it?.id })
    }

    @Test
    fun doesNotRegisterThe3dViewer() {
        val ids = InternalActions.SLOT_SPECS.map { it.first }.toSet()
        assertFalse("ll2_viewer3d_window" in ids)
        assertFalse("ll2_viewer3d_hook" in ids)
    }

    @Test
    fun metadataMatchesInternalScriptRegistryVerbatim() {
        val expected = parseInternalRegistry()
        val wanted = listOf(InternalActions.FILTER_ID, InternalActions.GIT_ID)
        val slots = InternalActions.allSlots().associateBy { it.id }
        val meta = InternalActions.SLOT_META.associateBy { it.id }
        for (id in wanted) {
            val tuple = expected.getValue(id)
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
        for (id in InternalActions.SLOT_SPECS.map { it.first }) {
            assertFalse(id in core, "$id must not be in core/launcher/registry.py")
        }
    }

    @Test
    fun coreRegistryHasZeroReferencesToTheseIds() {
        val core = JythonSources.readText("core/launcher/registry.py")
        assertFalse("ll2_filter_broken_lanelets_regElements" in core)
        assertFalse("ll2_git_commit" in core)
        assertFalse("ll2_viewer3d_window" in core)
    }

    private data class RegistryTuple(
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
    )

    private fun parseInternalRegistry(): Map<String, RegistryTuple> {
        val text = JythonSources.readText("internal/internal_script_registry.py")
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
