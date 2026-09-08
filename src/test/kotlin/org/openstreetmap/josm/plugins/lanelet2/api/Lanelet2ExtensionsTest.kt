package org.openstreetmap.josm.plugins.lanelet2.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LonLat
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.awt.event.ActionEvent

class Lanelet2ExtensionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun registerPlacesJythonShapedCallbackAfterAnchor() {
        val registry = ActionRegistry()
        val ran = booleanArrayOf(false)
        val callback = ScriptAction { ran[0] = true }
        Lanelet2Extensions.registerInto(
            registry,
            "example.hello_lanelet2",
            "Hello Lanelet2 (example)",
            callback,
            Lanelet2Extensions.after("lanelet_edit.check_lanelet_borders"),
        )
        val ids = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        // Anchor itself is not registered on this local registry, so only
        // the inserted script slot appears.
        assertEquals(listOf("example.hello_lanelet2"), ids)
        registry.get("example.hello_lanelet2")!!.action.actionPerformed(ActionEvent(this, 0, "test"))
        assertTrue(ran[0])
    }

    @Test
    fun registerBeforeAnchorAndReregisterKeepsPlacement() {
        val local = ActionRegistry(
            utilsOrder = listOf("anchor"),
            mapOrder = emptyList(),
        )
        val first = intArrayOf(0)
        Lanelet2Extensions.registerInto(
            local, "script.one", "One", ScriptAction { first[0] = 1 },
            Lanelet2Extensions.before("anchor"),
        )
        Lanelet2Extensions.registerInto(
            local, "anchor", "Anchor", ScriptAction { },
            Lanelet2Extensions.after("unused"),
        )
        assertEquals(
            listOf("script.one", "anchor"),
            local.build(MenuId.UTILS).mapNotNull { it?.id },
        )
        Lanelet2Extensions.registerInto(
            local, "script.one", "One again", ScriptAction { first[0] = 2 },
            Lanelet2Extensions.after("anchor"),
        )
        assertEquals(
            listOf("script.one", "anchor"),
            local.build(MenuId.UTILS).mapNotNull { it?.id },
        )
        local.get("script.one")!!.action.actionPerformed(ActionEvent(this, 0, "test"))
        assertEquals(2, first[0])
    }

    @Test
    fun anchorsListsFirstPartySlotIds() {
        val anchors = Lanelet2Extensions.anchors()
        assertTrue("lanelet_edit.check_lanelet_borders" in anchors)
        assertTrue("scripting.copy_example_script" in anchors)
        assertTrue("selection.select_lanelets_from_linestrings" in anchors)
        assertEquals(anchors.size, anchors.distinct().size)
    }

    @Test
    fun settingsUsesOneZeroBooleansAndLocalDefaults() {
        val s = Lanelet2Extensions.settings()
        assertEquals("road", s.get("lanelet.default_subtype", "road"))
        s.put("lanelet.default_subtype", "crosswalk")
        assertEquals("crosswalk", s.get("lanelet.default_subtype", "road"))
        s.putBoolean("routing.hook_full_map", true)
        assertTrue(s.getBoolean("routing.hook_full_map", false))
        s.putBoolean("routing.hook_full_map", false)
        assertFalse(s.getBoolean("routing.hook_full_map", true))
        s.putInt("routing.auto_debounce_ms", 2500)
        assertEquals(2500, s.getInt("routing.auto_debounce_ms", 0))
    }

    @Test
    fun geometryCenterlineAndLengthFromLanelet() {
        val left = OsmFixtures.way(8.4000 to 49.0000, 8.4010 to 49.0000)
        val right = OsmFixtures.way(8.4000 to 48.9999, 8.4010 to 48.9999)
        val rel = OsmFixtures.laneletRelation(left, right)
        val lanelet = Lanelet(rel)
        val pts = Lanelet2Extensions.geometry().centerlineOf(lanelet)
        assertTrue(pts.size >= 2, "centerline should have vertices")
        val meters = Lanelet2Extensions.geometry().centerlineLengthMeters(lanelet)
        // ~0.001 deg of longitude at lat 49 is on the order of 70 m.
        assertTrue(meters in 50.0..120.0, "length was $meters")
        val mid = Lanelet2Extensions.geometry().wayMiddlePoint(
            listOf(LonLat(0.0, 0.0), LonLat(1.0, 0.0), LonLat(2.0, 0.0)),
        )
        assertEquals(1.0, mid!!.lon, 0.0)
    }

    @Test
    fun laneletsFromSelectionIncludesInferred() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel)
        ds.setSelected(left)
        val found = Lanelet2Extensions.lanelets().fromSelection(ds)
        assertEquals(1, found.size)
        assertEquals(rel, found[0].relation)
        val wrapped = Lanelet2Extensions.lanelets().wrap(rel)
        assertEquals(rel, wrapped.relation)
    }
}
