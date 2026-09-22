package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.infra.HeightTools

/** How the viewer draws nodes whose `ele` is missing or unusable. */
class Viewer3dHeightsTest {
    private val anchor = Anchor(49.0, 8.4)
    private val sentinel = "-340282349999999991754788743781432688640" // -FLT_MAX, seen in real maps

    /** A way along the equator-ish east axis: node i at [east[i]] metres, with [ele[i]]. */
    private fun way(east: List<Double>, ele: List<String?>): WaySnapshot {
        val mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(49.0))
        val nodes = east.indices.map { i -> NodeSnapshot(i + 1L, 49.0, 8.4 + east[i] / mPerDegLon, ele[i]) }
        return WaySnapshot(1, false, nodes, "line_thin", null, null)
    }

    private fun zs(w: WaySnapshot) = Viewer3dFeatures.featureForWay(w, anchor)!!.pts.let { p -> List(p.size / 3) { p[it * 3 + 2] } }

    @Test
    fun implausibleHeightsAreNoHeights() {
        assertNull(HeightTools.parseEle(sentinel))
        assertNull(HeightTools.parseEle("NaN"))
        assertNull(HeightTools.parseEle("Infinity"))
        assertNull(HeightTools.parseEle("1e6"))
        assertNull(HeightTools.parseEle("abc"))
        assertEquals(-12.5, HeightTools.parseEle(" -12.5 "))
        assertEquals(8848.0, HeightTools.parseEle("8848"))
    }

    @Test
    fun anUnknownHeightBetweenKnownOnesIsInterpolatedByDistance() {
        val z = zs(way(listOf(0.0, 10.0, 40.0), listOf("100", sentinel, "104")))
        assertEquals(100.0, z[0], 1e-9)
        assertEquals(101.0, z[1], 0.01)
        assertEquals(104.0, z[2], 1e-9)
    }

    @Test
    fun unknownHeightsAtTheEndsCopyTheNearestKnownOne() {
        val z = zs(way(listOf(0.0, 10.0, 20.0, 30.0), listOf(null, "163.5", "164", sentinel)))
        assertEquals(listOf(163.5, 163.5, 164.0, 164.0), z)
    }

    @Test
    fun aWayWithoutAnyHeightStaysAtZero() {
        assertEquals(listOf(0.0, 0.0), zs(way(listOf(0.0, 10.0), listOf(null, sentinel))))
    }
}
