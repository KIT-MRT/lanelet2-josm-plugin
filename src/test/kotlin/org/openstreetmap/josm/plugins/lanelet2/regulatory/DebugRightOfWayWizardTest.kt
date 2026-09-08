package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class DebugRightOfWayWizardTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun pageExtractsTheThreeRoles() {
        val yieldLl = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
            subtype = "road",
        )
        val rowLl = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 3.0, 1.0 to 3.0),
            OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0),
            subtype = "bicycle_lane",
        )
        val stop = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        stop.put("type", "stop_line")
        val rel = OsmFixtures.relation("regulatory_element", "right_of_way")
        rel.addMember(RelationMember("yield", yieldLl))
        rel.addMember(RelationMember("right_of_way", rowLl))
        rel.addMember(RelationMember("ref_line", stop))
        rel.addMember(RelationMember("refers", OsmFixtures.way(2.0 to 2.0, 3.0 to 3.0)))
        val page = DebugRightOfWayWizard.pageFor(rel)
        assertSame(rel, page.relation)
        assertEquals(listOf(rowLl), page.rightOfWay)
        assertEquals(listOf(yieldLl), page.yield)
        assertEquals(listOf(stop), page.refLines)
        assertEquals(3, page.allMembers.size)
        assertEquals(rowLl.uniqueId.toString(), DebugRightOfWayWizard.primIdStr(rowLl))
        assertEquals("bicycle_lane", DebugRightOfWayWizard.primSubtype(rowLl))
        assertEquals("stop_line", DebugRightOfWayWizard.primType(stop))
    }

    @Test
    fun nextPrevAndDone() {
        assertEquals(1, DebugRightOfWayWizard.nextIndex(0, 3))
        assertEquals(2, DebugRightOfWayWizard.nextIndex(1, 3))
        assertNull(DebugRightOfWayWizard.nextIndex(2, 3))
        assertNull(DebugRightOfWayWizard.nextIndex(0, 1))
        assertNull(DebugRightOfWayWizard.prevIndex(0))
        assertEquals(0, DebugRightOfWayWizard.prevIndex(1))
    }

    @Test
    fun selectAndZoomSetsDatasetSelection() {
        val stop = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ds = OsmFixtures.dataSet(stop)
        DebugRightOfWayWizard.selectAndZoom(ds, listOf(stop))
        assertEquals(setOf(stop), ds.selected.toSet())
    }
}
