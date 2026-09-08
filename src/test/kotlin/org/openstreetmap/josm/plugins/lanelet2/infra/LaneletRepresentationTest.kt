package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class LaneletRepresentationTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun wayViewReversesNodeOrder() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        val fwd = WayView(w, isReversed = false)
        val rev = WayView(w, isReversed = true)
        assertSame(w.nodes[0], fwd.getFirstNode())
        assertSame(w.nodes[2], fwd.getLastNode())
        assertEquals(w.nodes, fwd.getNodes())
        assertSame(w.nodes[2], rev.getFirstNode())
        assertSame(w.nodes[0], rev.getLastNode())
        assertEquals(listOf(w.nodes[2], w.nodes[1], w.nodes[0]), rev.getNodes())
        assertSame(w, fwd.way)
    }

    @Test
    fun wayViewEmptyAndNull() {
        assertTrue(WayView(null).getNodes().isEmpty())
        assertNull(WayView(null).getFirstNode())
        assertNull(WayView(null).getLastNode())
        val empty = Way()
        assertTrue(WayView(empty).getNodes().isEmpty())
        assertTrue(wayViewPoints(null).isEmpty())
        assertTrue(wayViewPointsLonLat(null).isEmpty())
    }

    @Test
    fun wayViewPointsLatLonVsLonLat() {
        val w = OsmFixtures.way(8.4 to 49.0, 8.5 to 49.1)
        val view = WayView(w)
        val latlon = wayViewPoints(view)
        assertEquals(49.0, latlon[0].lat(), 1e-12)
        assertEquals(8.4, latlon[0].lon(), 1e-12)
        val lonlat = wayViewPointsLonLat(view)
        assertEquals(8.4, lonlat[0].lon, 1e-12)
        assertEquals(49.0, lonlat[0].lat, 1e-12)
    }

    @Test
    fun wayViewTangentsUnitLength() {
        val w = OsmFixtures.way(0.0 to 0.0, 3.0 to 4.0)
        val view = WayView(w)
        val start = wayViewTangentAtStart(view)
        assertEquals(0.6, start.dlon, 1e-12)
        assertEquals(0.8, start.dlat, 1e-12)
        val end = wayViewTangentAtEnd(view)
        assertEquals(0.6, end.dlon, 1e-12)
        assertEquals(0.8, end.dlat, 1e-12)
        val shortView = WayView(OsmFixtures.way(1.0 to 1.0))
        assertEquals(0.0, wayViewTangentAtStart(shortView).dlon, 0.0)
        assertEquals(0.0, wayViewTangentAtEnd(shortView).dlat, 0.0)
        val degen = WayView(OsmFixtures.way(1.0 to 1.0, 1.0 to 1.0))
        assertEquals(0.0, wayViewTangentAtStart(degen).dlon, 0.0)
    }

    @Test
    fun wayViewTangentAtEndUsesLastSegment() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)
        val t = wayViewTangentAtEnd(WayView(w))
        assertEquals(0.0, t.dlon, 1e-12)
        assertEquals(1.0, t.dlat, 1e-12)
        val tRev = wayViewTangentAtEnd(WayView(w, isReversed = true))
        assertEquals(-1.0, tRev.dlon, 1e-12)
        assertEquals(0.0, tRev.dlat, 1e-12)
    }

    @Test
    fun laneletAlignsReversedRightBorder() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        val rightFwd = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        val rightRev = OsmFixtures.way(2.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        val ok = Lanelet(OsmFixtures.laneletRelation(left, rightFwd))
        assertFalse(ok.leftBound()!!.isReversed)
        assertFalse(ok.rightBound()!!.isReversed)
        assertSame(left.nodes.first(), ok.getFirstNodesInDrivingDirection().first)
        assertSame(rightFwd.nodes.first(), ok.getFirstNodesInDrivingDirection().second)

        val aligned = Lanelet(OsmFixtures.laneletRelation(left, rightRev))
        assertFalse(aligned.leftBound()!!.isReversed)
        assertTrue(aligned.rightBound()!!.isReversed)
        assertSame(rightRev.nodes.last(), aligned.getFirstNodesInDrivingDirection().second)
        assertSame(rightRev.nodes.first(), aligned.getLastNodesInDrivingDirection().second)
    }

    @Test
    fun laneletMissingSideDoesNotAlign() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", left))
        val ll = Lanelet(rel)
        assertSame(left, ll.leftBound()!!.way)
        assertNull(ll.rightBound())
        assertNull(ll.getFirstNodesInDrivingDirection().first)
        assertNull(ll.getFirstNodesInDrivingDirection().second)
    }

    @Test
    fun invertSwapsBoundsWithoutReversingNodeOrder() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = Lanelet(OsmFixtures.laneletRelation(left, right))
        val inv = ll.invert()
        assertNotSame(ll, inv)
        assertSame(ll.relation, inv.relation)
        assertSame(ll.rightBound()!!.way, inv.leftBound()!!.way)
        assertSame(ll.leftBound()!!.way, inv.rightBound()!!.way)
        assertFalse(inv.leftBound()!!.isReversed)
        assertFalse(inv.rightBound()!!.isReversed)
        assertSame(right.nodes.first(), inv.getFirstNodesInDrivingDirection().first)
    }

    @Test
    fun factoryCreateRelationTagsAndMembers() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = LaneletFactory.createRelation(left, right, subtype = "bicycle_lane", location = "urban", oneWay = "no")
        assertEquals("lanelet", rel.get("type"))
        assertEquals("bicycle_lane", rel.get("subtype"))
        assertEquals("urban", rel.get("location"))
        assertEquals("no", rel.get("one_way"))
        assertEquals(2, rel.membersCount)
        assertEquals("left", rel.members[0].role)
        assertSame(left, rel.members[0].member)
        assertEquals("right", rel.members[1].role)
        assertSame(right, rel.members[1].member)

        val omitted = LaneletFactory.createRelation(left, right)
        assertEquals("road", omitted.get("subtype"))
        assertNull(omitted.get("location"))
        assertNull(omitted.get("one_way"))
    }

    @Test
    fun factoryCreateAddsToDatasetViaUndo() {
        val data = DataSet()
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        data.addPrimitive(left.nodes[0])
        data.addPrimitive(left.nodes[1])
        data.addPrimitive(right.nodes[0])
        data.addPrimitive(right.nodes[1])
        data.addPrimitive(left)
        data.addPrimitive(right)
        val undo = UndoRedoHandler.getInstance()
        undo.clean()
        try {
            val ll = LaneletFactory.create(data, null, left, right, undo, "road", null, null)
            assertTrue(data.relations.contains(ll.relation))
            assertEquals("lanelet", ll.relation.get("type"))
        } finally {
            undo.clean()
        }
    }

    @Test
    fun extractLeftRightIgnoresNonWayMembers() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = Relation()
        rel.addMember(RelationMember("left", left))
        rel.addMember(RelationMember("regulatory_element", Relation()))
        rel.addMember(RelationMember("right", right))
        val (l, r) = Lanelet.extractLeftRightWays(rel)
        assertSame(left, l)
        assertSame(right, r)
        val (n1, n2) = Lanelet.extractLeftRightWays(null)
        assertNull(n1)
        assertNull(n2)
    }
}
