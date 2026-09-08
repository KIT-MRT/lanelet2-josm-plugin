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

class MergeSwappedBordersTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun findsSwappedPairAndKeepsLowerUniqueId() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(right, left)
        val pairs = MergeSwappedBorders.findSwappedBorderPairs(listOf(a, b))
        assertEquals(1, pairs.size)
        val keep = if (a.uniqueId < b.uniqueId) a else b
        val drop = if (keep === a) b else a
        assertSame(keep, pairs[0].first)
        assertSame(drop, pairs[0].second)
    }

    @Test
    fun sameOrientationIsNotAPair() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(left, right)
        assertTrue(MergeSwappedBorders.findSwappedBorderPairs(listOf(a, b)).isEmpty())
    }

    @Test
    fun eachLaneletInAtMostOnePair() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(right, left)
        val c = OsmFixtures.laneletRelation(right, left)
        val pairs = MergeSwappedBorders.findSwappedBorderPairs(listOf(a, b, c))
        assertEquals(1, pairs.size)
    }

    @Test
    fun applySetsOneWayNoDeletesDuplicateAndUndoRestores() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(right, left)
        val ds = OsmFixtures.dataSet(a, b)
        val pairs = MergeSwappedBorders.findSwappedBorderPairs(listOf(a, b))
        val keep = pairs[0].first
        val drop = pairs[0].second
        OsmFixtures.withUndo { undo ->
            assertEquals(1, MergeSwappedBorders.apply(ds, pairs, undo = undo))
            assertEquals("no", keep.get("one_way"))
            assertTrue(drop.isDeleted)
            assertFalse(keep.isDeleted)
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertFalse(drop.isDeleted)
            assertEquals(null, keep.get("one_way"))
        }
    }

    @Test
    fun referrersAreRewrittenToKeptLanelet() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(right, left)
        val row = OsmFixtures.relation("regulatory_element", "right_of_way", "yield" to b)
        val ds = OsmFixtures.dataSet(a, b)
        ds.addPrimitiveRecursive(row)
        val pairs = MergeSwappedBorders.findSwappedBorderPairs(listOf(a, b))
        val keep = pairs[0].first
        val drop = pairs[0].second
        OsmFixtures.withUndo { undo ->
            MergeSwappedBorders.apply(ds, pairs, undo = undo)
            if (drop === b) {
                assertEquals(1, row.membersCount)
                assertSame(keep, row.members[0].member)
                assertEquals("yield", row.members[0].role)
            }
            undo.undo()
            assertSame(b, row.members[0].member)
        }
    }

    @Test
    fun replaceDeletedMembersDedupesRoleId() {
        val keep = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val drop = OsmFixtures.laneletRelation(
            OsmFixtures.way(2.0 to 1.0, 3.0 to 1.0),
            OsmFixtures.way(2.0 to 0.0, 3.0 to 0.0),
        )
        val ref = OsmFixtures.relation(
            "regulatory_element",
            "right_of_way",
            "yield" to drop,
            "yield" to keep,
        )
        OsmFixtures.dataSet(keep, drop, ref)
        val (newRef, changed) = MergeSwappedBorders.replaceDeletedMembers(ref, mapOf(drop to keep))
        assertTrue(changed)
        assertEquals(1, newRef.membersCount)
        assertSame(keep, newRef.members[0].member)
    }

    @Test
    fun extractViaLinestringsFindsThePair() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val a = OsmFixtures.laneletRelation(left, right)
        val b = OsmFixtures.laneletRelation(right, left)
        val ds = OsmFixtures.dataSet(a, b)
        ds.setSelected(listOf(left, right))
        val lanelets = org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
            .extractLaneletsOrFromLinestrings(ds, ds.selected)
        assertEquals(2, MergeSwappedBorders.dedupeLanelets(lanelets).size)
        assertEquals(1, MergeSwappedBorders.findSwappedBorderPairs(lanelets).size)
    }
}
