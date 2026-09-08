package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LaneletGeometryTest {

    private fun ll(lon: Double, lat: Double) = LonLat(lon, lat)

    private fun pts(vararg xy: Pair<Double, Double>) = xy.map { ll(it.first, it.second) }

    @Test
    fun projectPointOntoSegmentInteriorAndClamped() {
        val (tMid, pMid) = LaneletGeometry.projectPointToSegment(ll(0.5, 1.0), ll(0.0, 0.0), ll(1.0, 0.0))
        assertEquals(0.5, tMid, 1e-12)
        assertEquals(0.5, pMid.lon, 1e-12)
        assertEquals(0.0, pMid.lat, 1e-12)

        val (tBeyond, pBeyond) = LaneletGeometry.projectPointToSegment(ll(2.0, 1.0), ll(0.0, 0.0), ll(1.0, 0.0))
        assertEquals(1.0, tBeyond, 1e-12)
        assertEquals(1.0, pBeyond.lon, 1e-12)
        assertEquals(0.0, pBeyond.lat, 1e-12)

        val (tDegen, pDegen) = LaneletGeometry.projectPointToSegment(ll(1.0, 1.0), ll(0.0, 0.0), ll(0.0, 0.0))
        assertEquals(0.0, tDegen, 1e-12)
        assertEquals(0.0, pDegen.lon, 1e-12)
        assertEquals(0.0, pDegen.lat, 1e-12)
    }

    @Test
    fun signedDistanceLeftRightOnAndDegenerate() {
        val line = pts(0.0 to 0.0, 2.0 to 0.0)
        assertEquals(1.0, LaneletGeometry.signedDistance(line, ll(1.0, 1.0)), 1e-12)
        assertEquals(-1.0, LaneletGeometry.signedDistance(line, ll(1.0, -1.0)), 1e-12)
        assertEquals(0.0, LaneletGeometry.signedDistance(line, ll(1.0, 0.0)), 1e-12)
        assertEquals(0.0, LaneletGeometry.signedDistance(pts(0.0 to 0.0), ll(1.0, 1.0)), 1e-12)
        assertEquals(0.0, LaneletGeometry.signedDistance(emptyList(), ll(1.0, 1.0)), 1e-12)
        val poly = pts(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)
        assertEquals(0.5, LaneletGeometry.signedDistance(poly, ll(0.5, 0.5)), 1e-12)
    }

    @Test
    fun wayMiddlePointUsesIndexNotCentroidWhenMoreThanTwo() {
        assertNull(LaneletGeometry.wayMiddlePoint(emptyList()))
        val one = LaneletGeometry.wayMiddlePoint(pts(1.0 to 2.0))!!
        assertEquals(1.0, one.lon, 1e-12)
        assertEquals(2.0, one.lat, 1e-12)
        val two = LaneletGeometry.wayMiddlePoint(pts(0.0 to 0.0, 2.0 to 4.0))!!
        assertEquals(1.0, two.lon, 1e-12)
        assertEquals(2.0, two.lat, 1e-12)
        // 3 points: Python 2 `n/2` -> index 1, not the geometric centroid
        val three = LaneletGeometry.wayMiddlePoint(pts(0.0 to 0.0, 1.0 to 1.0, 10.0 to 10.0))!!
        assertEquals(1.0, three.lon, 1e-12)
        assertEquals(1.0, three.lat, 1e-12)
        val four = LaneletGeometry.wayMiddlePoint(pts(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0))!!
        assertEquals(2.0, four.lon, 1e-12)
        val five = LaneletGeometry.wayMiddlePoint(
            pts(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
        )!!
        assertEquals(2.0, five.lon, 1e-12)
    }

    @Test
    fun alignBoundsDetectsReversedSides() {
        val left = pts(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertEquals(Pair(false, false), LaneletGeometry.alignBounds(left, right))
        assertEquals(Pair(true, false), LaneletGeometry.alignBounds(left.reversed(), right))
        assertEquals(Pair(false, true), LaneletGeometry.alignBounds(left, right.reversed()))
        assertEquals(Pair(true, true), LaneletGeometry.alignBounds(left.reversed(), right.reversed()))
        assertEquals(Pair(false, false), LaneletGeometry.alignBounds(pts(0.0 to 1.0), pts(0.0 to 0.0, 1.0 to 0.0)))
    }
}
