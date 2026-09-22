package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class HeightToolsTest {
    private lateinit var ds: DataSet

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        ds = DataSet()
    }

    /** Node [east] metres east of (49, 8.4), optionally with an ele. */
    private fun node(east: Double, ele: String? = null, north: Double = 0.0): Node {
        val lat = 49.0 + north / 111_320.0
        val lon = 8.4 + east / (111_320.0 * Math.cos(Math.toRadians(49.0)))
        return Node(LatLon(lat, lon)).also { n ->
            ele?.let { n.put("ele", it) }
            ds.addPrimitive(n)
        }
    }

    private fun way(vararg nodes: Node) = Way().also {
        it.setNodes(nodes.toList())
        ds.addPrimitive(it)
    }

    @Test
    fun interpolatesLinearlyInDistanceBetweenAnchors() {
        val cum = doubleArrayOf(0.0, 10.0, 30.0, 40.0, 50.0)
        val z = HeightTools.interpolate(cum, mapOf(0 to 100.0, 3 to 104.0))
        assertEquals(setOf(1, 2), z.keys)
        assertEquals(101.0, z[1]!!, 1e-12)
        assertEquals(103.0, z[2]!!, 1e-12)
    }

    @Test
    fun formatsHeightsToMillimetres() {
        assertEquals("110", HeightTools.formatEle(110.0))
        assertEquals("110.5", HeightTools.formatEle(110.5))
        assertEquals("-2.125", HeightTools.formatEle(-2.1249996))
        assertEquals("0", HeightTools.formatEle(-0.0001))
    }

    @Test
    fun planBetweenTheEndsOfAWay() {
        val a = node(0.0, "100")
        val b = node(10.0)
        val c = node(30.0, "100")
        val d = node(40.0, "104")
        val w = way(a, b, c, d)
        val plan = HeightTools.planInterpolation(w, emptyList())
        assertNull(plan.error)
        val byNode = plan.changes.toMap()
        assertEquals(setOf(b, c), byNode.keys) // c had the wrong height, b none
        assertEquals(101.0, byNode[b]!!, 0.01)
        assertEquals(103.0, byNode[c]!!, 0.01)
    }

    @Test
    fun planBetweenSelectedAnchorsLeavesTheRest() {
        val a = node(0.0, "90")
        val b = node(10.0, "100")
        val c = node(20.0, "0")
        val d = node(30.0, "104")
        val e = node(40.0, "50")
        val plan = HeightTools.planInterpolation(way(a, b, c, d, e), listOf(d, b))
        assertEquals(listOf(c), plan.changes.map { it.first })
        assertEquals(102.0, plan.changes.single().second, 0.01)
    }

    @Test
    fun anchorsNeedAHeight() {
        val plan = HeightTools.planInterpolation(way(node(0.0), node(10.0), node(20.0, "3")), emptyList())
        assertNotNull(plan.error)
        assertTrue(plan.error!!.contains("no ele"), plan.error)
    }

    @Test
    fun closedWaysNeedExplicitAnchors() {
        val a = node(0.0, "1")
        val w = way(a, node(10.0), node(10.0, north = 10.0), a)
        assertNotNull(HeightTools.planInterpolation(w, emptyList()).error)
    }

    @Test
    fun nearestWithEleSkipsExcludedAndHeightlessNodes() {
        node(3.0) // closer, but no height
        val far = node(40.0, "120")
        val excluded = node(1.0, "999")
        val at = node(0.0)
        val found = HeightTools.nearestWithEle(ds, at.coor, setOf(at, excluded))!!
        assertEquals(far, found.first)
        assertEquals(120.0, found.second, 0.0)
        assertNull(HeightTools.nearestWithEle(ds, at.coor, setOf(at, excluded, far), maxRadiusM = 20.0))
    }

    @Test
    fun newNodesTakeTheNearestHeightButNotEachOthers() {
        node(0.0, "110")
        node(200.0, "130")
        val n1 = node(5.0)
        val n2 = node(190.0)
        val plan = HeightTools.planNewNodeHeights(ds, listOf(n1, n2)).changes.toMap()
        assertEquals(110.0, plan[n1]!!, 0.0)
        assertEquals(130.0, plan[n2]!!, 0.0)
    }

    @Test
    fun aNodeInsertedIntoAWayIsInterpolatedNotCopied() {
        val a = node(0.0, "100")
        val b = node(40.0, "110")
        val w = way(a, b)
        val n = node(10.0)
        w.setNodes(listOf(a, n, b)) // JOSM's insert-into-way
        val z = HeightTools.planNewNodeHeights(ds, listOf(n)).changes.single().second
        assertEquals(102.5, z, 0.01) // not 100, the nearest node's height
    }

    @Test
    fun aNewWayBetweenTwoNodesGetsOneStraightProfile() {
        val e1 = node(0.0, "100")
        val e2 = node(40.0, "110")
        val news = listOf(node(10.0), node(20.0), node(30.0))
        way(e1, *news.toTypedArray(), e2)
        val z = HeightTools.planNewNodeHeights(ds, news).changes.toMap()
        assertEquals(listOf(102.5, 105.0, 107.5), news.map { z[it]!! }.map { Math.round(it * 100) / 100.0 })
    }

    @Test
    fun theJointOfTwoWaysIsInterpolatedAcrossBoth() {
        val p = node(0.0, "100")
        val n = node(10.0)
        val q = node(40.0, "110")
        way(p, n)
        way(n, q)
        assertEquals(102.5, HeightTools.planNewNodeHeights(ds, listOf(n)).changes.single().second, 0.01)
    }

    @Test
    fun aFreeEndTakesTheNearestHeight() {
        val a = node(0.0, "100")
        val b = node(40.0, "110")
        val n = node(45.0)
        way(a, b, n) // extends the way at its end, joined to nothing else
        assertEquals(110.0, HeightTools.planNewNodeHeights(ds, listOf(n)).changes.single().second, 0.0)
    }

    @Test
    fun aNoDataSentinelIsNoHeight() {
        val a = node(0.0, "100")
        val bad = node(5.0, "-340282349999999991754788743781432688640") // -FLT_MAX from some tools
        val b = node(10.0, "104")
        way(a, bad, b)
        assertNull(HeightTools.eleOf(bad))
        assertTrue(HeightTools.heightJumps(listOf(bad), 2.0).isEmpty())
        assertEquals(bad to 102.0, HeightTools.planInterpolation(ds.ways.single(), emptyList()).changes.single()
            .let { it.first to Math.round(it.second * 100) / 100.0 })
    }

    @Test
    fun jumpsAboveTheThresholdAreFound() {
        val a = node(0.0, "100")
        val b = node(5.0, "103.5")
        val c = node(10.0, "104")
        val w = way(a, b, c)
        val jumps = HeightTools.heightJumps(listOf(b), 2.0)
        assertEquals(1, jumps.size)
        assertEquals(3.5, jumps.single().dz, 1e-9)
        assertEquals(w, jumps.single().way)
        val text = HeightTools.describeJumps(jumps, 2.0)!!
        assertTrue(text.contains("3.5 m over 5.0 m (70 % grade)"), text)
        assertTrue(HeightTools.heightJumps(listOf(c), 2.0).isEmpty())
    }
}
