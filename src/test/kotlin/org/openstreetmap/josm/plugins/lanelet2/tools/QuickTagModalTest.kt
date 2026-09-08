package org.openstreetmap.josm.plugins.lanelet2.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class QuickTagModalTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun hintLineSortsKeysAndKeepsEsc() {
        assertEquals(
            "[ROOT] B:Bike marking...  C:Curbstone...  D:Dashed line  P:Pedestrian marking  " +
                "R:Road border  S:Solid line  V:Virtual  X:Stop line  (Esc)",
            QuickTagModal.hintLine("ROOT"),
        )
        assertEquals("[BIKE] D:Bike dashed  S:Bike solid  (Esc)", QuickTagModal.hintLine("BIKE"))
        assertEquals("[CURB] H:High  L:Low...  (Esc)", QuickTagModal.hintLine("CURB"))
        assertEquals(
            "[CURB_LOW] L:Low restricted (default)  R:Low road side-entry  S:Low sidewalk entry  (Esc)",
            QuickTagModal.hintLine("CURB_LOW"),
        )
    }

    @Test
    fun stateTablesMatchJythonLeavesAndSubmenus() {
        val virtual = QuickTagModal.ROOT.getValue("V")
        assertEquals("Virtual", virtual.label)
        assertEquals(
            mapOf("type" to "virtual"),
            (virtual.value as QuickTagModal.Value.Tags).tags,
        )
        assertEquals("BIKE", (QuickTagModal.ROOT.getValue("B").value as QuickTagModal.Value.Submenu).stateId)
        assertEquals("CURB_LOW", (QuickTagModal.CURB.getValue("L").value as QuickTagModal.Value.Submenu).stateId)
        val sidewalk = QuickTagModal.CURB_LOW.getValue("S").value as QuickTagModal.Value.Tags
        assertEquals("true", sidewalk.tags["sidewalk_entry"])
        assertEquals("curbstone_regular_road", (QuickTagModal.CURB_LOW.getValue("R").value as QuickTagModal.Value.Tags).tags["type"])
    }

    @Test
    fun applyTagsPutsKeysAndPreservesOthersAndUndoRestores() {
        val way = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        way.put("type", "line_thin")
        way.put("keep", "me")
        val ds = OsmFixtures.dataSet(way)
        OsmFixtures.withUndo { undo ->
            val n = QuickTagModal.applyTags(
                ds,
                listOf(way),
                "Dashed line",
                mapOf("type" to "line_thin", "subtype" to "dashed"),
                undo = undo,
            )
            assertEquals(1, n)
            assertEquals("dashed", way.get("subtype"))
            assertEquals("line_thin", way.get("type"))
            assertEquals("me", way.get("keep"))
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertEquals("line_thin", way.get("type"))
            assertTrue(way.get("subtype").isNullOrEmpty())
            assertEquals("me", way.get("keep"))
        }
    }

    @Test
    fun selectedWaysIgnoresNodes() {
        val way = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val node = OsmFixtures.node(2.0, 2.0)
        val ds = OsmFixtures.dataSet(way, node)
        ds.setSelected(listOf(way, node))
        assertEquals(listOf(way), QuickTagModal.selectedWays(ds))
    }

    @Test
    fun positionTopCenterUsesPython2IntDivision() {
        assertEquals(15, 100 / 2 - 70 / 2)
        assertEquals(15, 100 + (200 - 170) / 2 - 100)
    }
}
