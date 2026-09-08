package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SplitWayAtSelectedNodesTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun interiorNodeProducesTwoSegments() {
        val a = OsmFixtures.node(0.0, 0.0)
        val b = OsmFixtures.node(1.0, 0.0)
        val c = OsmFixtures.node(2.0, 0.0)
        val way = wayOf(a, b, c)
        val segs = SplitWayAtSelectedNodes.splitWayAtNodes(way, setOf(b.uniqueId))!!
        assertEquals(2, segs.size)
        assertEquals(listOf(a, b), segs[0])
        assertEquals(listOf(b, c), segs[1])
    }

    @Test
    fun endpointDoesNotSplit() {
        val a = OsmFixtures.node(0.0, 0.0)
        val b = OsmFixtures.node(1.0, 0.0)
        val way = wayOf(a, b)
        assertNull(SplitWayAtSelectedNodes.splitWayAtNodes(way, setOf(a.uniqueId, b.uniqueId)))
    }

    @Test
    fun splitReplacesLaneletBorderWithFirstDrivingSegmentAndUndoRestores() {
        val a = OsmFixtures.node(0.0, 1.0)
        val b = OsmFixtures.node(1.0, 1.0)
        val c = OsmFixtures.node(2.0, 1.0)
        val left = wayOf(a, b, c)
        left.put("type", "line_thin")
        val right = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            val plan = SplitWayAtSelectedNodes.apply(ds, listOf(b), undo = undo)!!
            assertEquals(2, plan.newWays.size)
            assertTrue(left.isDeleted)
            val (newLeft, newRight) = Lanelet.extractLeftRightWays(rel)
            assertSame(right, newRight)
            assertTrue(newLeft === plan.newWays[0])
            assertEquals(listOf(a, b).map { it.uniqueId }, newLeft!!.nodes.map { it.uniqueId })
            assertEquals("line_thin", newLeft.get("type"))
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertFalse(left.isDeleted)
            val (restoredLeft, _) = Lanelet.extractLeftRightWays(rel)
            assertSame(left, restoredLeft)
        }
    }

    @Test
    fun reversedBoundKeepsLastSegmentAsFirstInDrivingDirection() {
        // OSM left way runs east-to-west, so alignBounds will reverse it.
        // Driving-direction first node is the OSM last node -> last segment.
        val a = OsmFixtures.node(2.0, 1.0)
        val b = OsmFixtures.node(1.0, 1.0)
        val c = OsmFixtures.node(0.0, 1.0)
        val left = wayOf(a, b, c)
        val right = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel)
        val idx = SplitWayAtSelectedNodes.firstSegmentIdxInDrivingDirection(
            left,
            SplitWayAtSelectedNodes.splitWayAtNodes(left, setOf(b.uniqueId))!!,
            rel,
        )
        assertEquals(1, idx)
        OsmFixtures.withUndo { undo ->
            val plan = SplitWayAtSelectedNodes.apply(ds, listOf(b), undo = undo)!!
            val (newLeft, _) = Lanelet.extractLeftRightWays(rel)
            assertSame(plan.newWays[1], newLeft)
            assertEquals(listOf(b, c).map { it.uniqueId }, newLeft!!.nodes.map { it.uniqueId })
        }
    }

    @Test
    fun nonLaneletRelationDropsTheOriginalWay() {
        val a = OsmFixtures.node(0.0, 0.0)
        val b = OsmFixtures.node(1.0, 0.0)
        val c = OsmFixtures.node(2.0, 0.0)
        val way = wayOf(a, b, c)
        val other = OsmFixtures.relation("multipolygon", members = arrayOf("outer" to way))
        val ds = OsmFixtures.dataSet(other)
        OsmFixtures.withUndo { undo ->
            SplitWayAtSelectedNodes.apply(ds, listOf(b), undo = undo)
            assertTrue(other.members.none { it.member === way })
            assertTrue(other.members.none { it.member is Way && !it.member.isDeleted })
            undo.undo()
            assertTrue(other.members.any { it.member === way })
        }
    }

    @Test
    fun extraLaneletMemberIsOnlyRemovedIfSameLaneletAlsoSplit() {
        val a = OsmFixtures.node(0.0, 1.0)
        val b = OsmFixtures.node(1.0, 1.0)
        val c = OsmFixtures.node(2.0, 1.0)
        val left = wayOf(a, b, c)
        val right = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        val extra = wayOf(OsmFixtures.node(0.0, 0.5), b, OsmFixtures.node(2.0, 0.5))
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.addMember(RelationMember("centerline", extra))
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            SplitWayAtSelectedNodes.apply(ds, listOf(b), undo = undo)
            // extra is a lanelet member but neither left nor right; because the
            // same relation also had a left split, extra is in extraRemovals and
            // is removed (Jython applies extras only when the rel is in replacements).
            assertTrue(rel.members.none { it.member === extra })
        }
    }

    private fun wayOf(vararg nodes: Node): Way {
        val w = Way()
        w.setNodes(nodes.toList())
        return w
    }
}
