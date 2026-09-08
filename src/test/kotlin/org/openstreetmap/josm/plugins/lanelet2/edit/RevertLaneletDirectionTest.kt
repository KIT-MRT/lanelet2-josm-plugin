package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class RevertLaneletDirectionTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun revertCopySwapsLeftRightAndLeavesOtherRoles() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.addMember(RelationMember("regulatory_element", reg))
        val copy = RevertLaneletDirection.revertCopy(rel)
        assertEquals("right", copy.members[0].role)
        assertSame(left, copy.members[0].member)
        assertEquals("left", copy.members[1].role)
        assertSame(right, copy.members[1].member)
        assertEquals("regulatory_element", copy.members[2].role)
        assertSame(reg, copy.members[2].member)
        assertEquals("left", rel.members[0].role)
    }

    @Test
    fun applyAndUndoRestoresRoles() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            val n = RevertLaneletDirection.apply(listOf(rel), undo = undo)
            assertEquals(1, n)
            assertEquals("right", rel.members[0].role)
            assertSame(left, rel.members[0].member)
            assertEquals("left", rel.members[1].role)
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertEquals("left", rel.members[0].role)
            assertSame(left, rel.members[0].member)
            assertEquals("right", rel.members[1].role)
            assertFalse(rel.isDeleted)
            assertTrue(rel in ds.relations)
        }
    }

    @Test
    fun oneSequenceCommandForMultipleLanelets() {
        val a = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val b = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 3.0, 1.0 to 3.0),
            OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0),
        )
        OsmFixtures.dataSet(a, b)
        OsmFixtures.withUndo { undo ->
            RevertLaneletDirection.apply(listOf(a, b), undo = undo)
            assertEquals(1, undo.undoCommands.size)
            assertTrue(undo.lastCommand.descriptionText.contains(RevertLaneletDirection.SEQUENCE_NAME))
            undo.undo()
            assertEquals("left", a.members[0].role)
            assertEquals("left", b.members[0].role)
        }
    }
}
