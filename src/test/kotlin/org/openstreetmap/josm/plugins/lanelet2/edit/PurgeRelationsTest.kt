package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class PurgeRelationsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun purgesLaneletWaysAndExclusiveNodesAndUndoRestoresAll() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        val leftNodes = left.nodes.toList()
        val rightNodes = right.nodes.toList()
        OsmFixtures.withUndo { undo ->
            val result = PurgeRelations.apply(ds, listOf(ll), undo = undo)
            assertEquals(1, result.deletedRelations)
            assertEquals(0, result.referrersUpdated)
            assertEquals(2, result.deletedWays)
            assertEquals(4, result.deletedNodes)
            assertTrue(ll.isDeleted)
            assertTrue(left.isDeleted)
            assertTrue(right.isDeleted)
            assertTrue(leftNodes.all { it.isDeleted })
            assertTrue(rightNodes.all { it.isDeleted })
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertFalse(ll.isDeleted)
            assertFalse(left.isDeleted)
            assertFalse(right.isDeleted)
            assertTrue(leftNodes.none { it.isDeleted })
        }
    }

    @Test
    fun sharedWayIsKeptWhenAnotherRelationReferencesIt() {
        val shared = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rightA = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rightB = OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0)
        val a = OsmFixtures.laneletRelation(shared, rightA)
        val b = OsmFixtures.laneletRelation(shared, rightB)
        val ds = OsmFixtures.dataSet(a, b)
        OsmFixtures.withUndo { undo ->
            val result = PurgeRelations.apply(ds, listOf(a), undo = undo)
            assertTrue(a.isDeleted)
            assertFalse(b.isDeleted)
            assertFalse(shared.isDeleted)
            assertTrue(rightA.isDeleted)
            assertFalse(rightB.isDeleted)
            assertEquals(1, result.deletedWays)
            undo.undo()
            assertFalse(a.isDeleted)
            assertFalse(rightA.isDeleted)
        }
    }

    @Test
    fun referrerLosesThePurgedMember() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val row = OsmFixtures.relation("regulatory_element", "right_of_way", "yield" to ll)
        val ds = OsmFixtures.dataSet(ll)
        ds.addPrimitiveRecursive(row)
        OsmFixtures.withUndo { undo ->
            val result = PurgeRelations.apply(ds, listOf(ll), undo = undo)
            assertEquals(1, result.referrersUpdated)
            assertTrue(row.members.none { it.member === ll })
            assertFalse(row.isDeleted)
            undo.undo()
            assertTrue(row.members.any { it.member === ll })
        }
    }

    @Test
    fun memberWaysAndWayNodesHelpers() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.relation("lanelet", "road", "left" to w)
        assertEquals(setOf(w), PurgeRelations.memberWays(listOf(rel)))
        assertEquals(w.nodes.toSet(), PurgeRelations.wayNodes(listOf(w)))
        assertTrue(PurgeRelations.memberWays(listOf(null)).isEmpty())
    }
}
