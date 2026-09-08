package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class AddRegElemToLaneletsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun extractFindsFirstRegulatoryElement() {
        val way = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        val other = OsmFixtures.relation("lanelet")
        assertSame(reg, AddRegElemToLanelets.extractRegulatoryElement(listOf(way, other, reg)))
        assertNull(AddRegElemToLanelets.extractRegulatoryElement(listOf(way, other)))
        val mixedCase = OsmFixtures.relation("Regulatory_Element")
        assertSame(mixedCase, AddRegElemToLanelets.extractRegulatoryElement(listOf(mixedCase)))
    }

    @Test
    fun applyAddsMemberAndUndoRemovesIt() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        OsmFixtures.dataSet(ll, reg)
        OsmFixtures.withUndo { undo ->
            val n = AddRegElemToLanelets.apply(reg, listOf(ll), undo = undo)
            assertEquals(1, n)
            assertEquals(3, ll.membersCount)
            assertEquals("regulatory_element", ll.members[2].role)
            assertSame(reg, ll.members[2].member)
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertEquals(2, ll.membersCount)
        }
    }

    @Test
    fun skipsLaneletsThatAlreadyHaveTheElement() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        ll.addMember(RelationMember("regulatory_element", reg))
        OsmFixtures.dataSet(ll, reg)
        OsmFixtures.withUndo { undo ->
            assertEquals(0, AddRegElemToLanelets.apply(reg, listOf(ll), undo = undo))
            assertTrue(undo.undoCommands.isEmpty())
            assertEquals(3, ll.membersCount)
        }
    }

    @Test
    fun oneSequenceForSeveralLanelets() {
        val ll1 = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val ll2 = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 3.0, 1.0 to 3.0),
            OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0),
        )
        val reg = OsmFixtures.relation("regulatory_element", "speed_limit")
        OsmFixtures.dataSet(ll1, ll2, reg)
        OsmFixtures.withUndo { undo ->
            assertEquals(2, AddRegElemToLanelets.apply(reg, listOf(ll1, ll2), undo = undo))
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertEquals(2, ll1.membersCount)
            assertEquals(2, ll2.membersCount)
        }
    }
}
