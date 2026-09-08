package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SmoothCenterTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        CreateLaneletRelation.useDirectedSuccessorOverride = null
    }

    /**
     * Three consecutive lanelets sharing endpoint nodes: entry, center, exit.
     * Geometry is west-to-east so geometric alignment matches OSM node order.
     */
    private fun chain(): Quad {
        val eL0 = OsmFixtures.node(0.0, 1.0)
        val eL1 = OsmFixtures.node(1.0, 1.0)
        val cL1 = OsmFixtures.node(2.0, 1.0)
        val xL1 = OsmFixtures.node(3.0, 1.0)
        val eR0 = OsmFixtures.node(0.0, 0.0)
        val eR1 = OsmFixtures.node(1.0, 0.0)
        val cR1 = OsmFixtures.node(2.0, 0.0)
        val xR1 = OsmFixtures.node(3.0, 0.0)
        val entryLeft = wayOf(eL0, eL1)
        val centerLeft = wayOf(eL1, cL1)
        val exitLeft = wayOf(cL1, xL1)
        val entryRight = wayOf(eR0, eR1)
        val centerRight = wayOf(eR1, cR1)
        val exitRight = wayOf(cR1, xR1)
        val entry = OsmFixtures.laneletRelation(entryLeft, entryRight)
        val center = OsmFixtures.laneletRelation(centerLeft, centerRight)
        val exit = OsmFixtures.laneletRelation(exitLeft, exitRight)
        val ds = OsmFixtures.dataSet(entry, center, exit)
        return Quad(ds, entry, center, exit, centerLeft, centerRight)
    }

    private fun wayOf(vararg nodes: Node): Way {
        val w = Way()
        w.setNodes(nodes.toList())
        return w
    }

    private data class Quad(
        val ds: org.openstreetmap.josm.data.osm.DataSet,
        val entry: org.openstreetmap.josm.data.osm.Relation,
        val center: org.openstreetmap.josm.data.osm.Relation,
        val exit: org.openstreetmap.josm.data.osm.Relation,
        val centerLeft: Way,
        val centerRight: Way,
    )

    @Test
    fun findEntryExitBySharedEndpoints() {
        val q = chain()
        val (entry, exit) = SmoothCenter.findEntryExitForCenter(q.center, q.ds)
        assertSame(q.entry, entry)
        assertSame(q.exit, exit)
    }

    @Test
    fun findEntryExitReturnsNullWhenIsolated() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel)
        val (e, x) = SmoothCenter.findEntryExitForCenter(rel, ds)
        assertNull(e)
        assertNull(x)
    }

    @Test
    fun applyBothReplacesInteriorNodesAndUndoRestores() {
        val q = chain()
        val oldLeftInterior = q.centerLeft.nodes.toList().drop(1).dropLast(1)
        OsmFixtures.withUndo { undo ->
            assertTrue(SmoothCenter.applyBoth(q.ds, null, q.entry, q.center, q.exit, undo))
            assertEquals(1, undo.undoCommands.size)
            assertTrue(undo.lastCommand.descriptionText.contains(SmoothCenter.SEQUENCE_BOTH))
            assertEquals(SmoothCenter.N_SAMPLES, q.centerLeft.nodesCount)
            assertEquals(SmoothCenter.N_SAMPLES, q.centerRight.nodesCount)
            for (n in oldLeftInterior) {
                assertTrue(n.isDeleted, "old interior should be deleted when unreferenced")
            }
            val firstLeft = q.centerLeft.nodes.first()
            val lastLeft = q.centerLeft.nodes.last()
            undo.undo()
            assertEquals(2, q.centerLeft.nodesCount)
            assertSame(firstLeft, q.centerLeft.nodes.first())
            assertSame(lastLeft, q.centerLeft.nodes.last())
            for (n in oldLeftInterior) {
                assertFalse(n.isDeleted)
            }
        }
    }

    @Test
    fun applyOneBorderLeavesTheOtherUntouched() {
        val q = chain()
        val rightBefore = q.centerRight.nodes.toList()
        OsmFixtures.withUndo { undo ->
            assertTrue(
                SmoothCenter.applyOneBorder(q.ds, null, q.entry, q.center, q.exit, "left", undo = undo),
            )
            assertEquals(SmoothCenter.N_SAMPLES, q.centerLeft.nodesCount)
            assertEquals(rightBefore, q.centerRight.nodes)
            undo.undo()
            assertEquals(2, q.centerLeft.nodesCount)
        }
    }

    @Test
    fun constraintNodeOnInteriorIsUsed() {
        val q = chain()
        val mid = OsmFixtures.node(1.5, 1.05)
        q.ds.addPrimitive(mid)
        val leftNodes = ArrayList(q.centerLeft.nodes)
        leftNodes.add(1, mid)
        q.centerLeft.setNodes(leftNodes)
        OsmFixtures.withUndo { undo ->
            assertTrue(
                SmoothCenter.applyOneBorder(
                    q.ds, null, q.entry, q.center, q.exit, "left",
                    constraintNodes = listOf(mid),
                    undo = undo,
                ),
            )
            assertTrue(q.centerLeft.nodesCount > 3)
        }
    }

    @Test
    fun applyBatchSmoothsWhenEntryExitExist() {
        val q = chain()
        OsmFixtures.withUndo { undo ->
            val n = SmoothCenter.applyBatch(q.ds, null, listOf(q.center), undo)
            assertEquals(1, n)
            assertEquals(SmoothCenter.N_SAMPLES, q.centerLeft.nodesCount)
        }
    }

    @Test
    fun hermiteStraightChordStaysOnLatitude() {
        val pts = SmoothCenter.hermiteSpline(
            SmoothCenter.LatLonPt(49.0, 8.4),
            SmoothCenter.LatLonPt(49.0, 8.41),
            SmoothCenter.TangentPt(1.0, 0.0),
            SmoothCenter.TangentPt(1.0, 0.0),
            5,
        )
        assertEquals(5, pts.size)
        assertEquals(49.0, pts.first().lat, 1e-12)
        assertEquals(49.0, pts.last().lat, 1e-12)
        assertEquals(8.4, pts.first().lon, 1e-12)
        assertEquals(8.41, pts.last().lon, 1e-12)
        for (p in pts) {
            assertEquals(49.0, p.lat, 1e-9)
        }
    }

    @Test
    fun degenerateChordRepeatsP0() {
        val p = SmoothCenter.LatLonPt(49.0, 8.4)
        val pts = SmoothCenter.hermiteSpline(p, p, SmoothCenter.TangentPt(1.0, 0.0), SmoothCenter.TangentPt(0.0, 1.0), 4)
        assertEquals(4, pts.size)
        assertTrue(pts.all { it == p })
        assertTrue(SmoothCenter.hermiteSpline(p, p, SmoothCenter.TangentPt(1.0, 0.0), SmoothCenter.TangentPt(1.0, 0.0), 0).isEmpty())
    }
}
