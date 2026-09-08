package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CenterlineTest {

    private fun ll(lon: Double, lat: Double) = LonLat(lon, lat)

    private fun pts(vararg xy: Pair<Double, Double>): List<LonLat> =
        xy.map { ll(it.first, it.second) }

    private fun assertPtsEqual(expected: List<LonLat>, actual: List<LonLat>, eps: Double = 1e-12) {
        assertEquals(expected.size, actual.size, "size: expected=$expected actual=$actual")
        for (i in expected.indices) {
            assertEquals(expected[i].lon, actual[i].lon, eps, "lon[$i]")
            assertEquals(expected[i].lat, actual[i].lat, eps, "lat[$i]")
        }
    }

    private fun center(left: List<LonLat>, right: List<LonLat>) =
        Centerline.calculateCenterlinePoints(left, right)

    @Test
    fun emptyInputsYieldEmpty() {
        assertTrue(center(emptyList(), pts(0.0 to 0.0, 1.0 to 0.0)).isEmpty())
        assertTrue(center(pts(0.0 to 0.0, 1.0 to 0.0), emptyList()).isEmpty())
        assertTrue(center(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun singlePointEachIsMidpoint() {
        assertPtsEqual(pts(0.0 to 0.5), center(pts(0.0 to 1.0), pts(0.0 to 0.0)))
    }

    @Test
    fun parallelEqualCountWalksBothBorders() {
        // Golden from lanelet2_centerline_calculate.py (python3).
        val left = pts(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 0.5),
            center(left, right),
        )
    }

    @Test
    fun differingBorderPointCountsLeftShort() {
        val left = pts(0.0 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.5, 0.5 to 0.5, 1.5 to 0.5, 2.0 to 0.5),
            center(left, right),
        )
    }

    @Test
    fun differingBorderPointCountsLeftDense() {
        val left = pts(0.0 to 1.0, 0.5 to 1.0, 1.5 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.5, 0.25 to 0.5, 0.75 to 0.5, 1.75 to 0.5, 2.0 to 0.5),
            center(left, right),
        )
    }

    @Test
    fun sharedStartEndpointSkipsLeftIndexZero() {
        val left = pts(0.0 to 0.0, 1.0 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.0, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 0.5),
            center(left, right),
        )
    }

    @Test
    fun sharedEndEndpoint() {
        val left = pts(0.0 to 1.0, 1.0 to 1.0, 2.0 to 0.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5, 2.0 to 0.0),
            center(left, right),
        )
    }

    @Test
    fun duplicateConsecutiveLeftVerticesAreKept() {
        val left = pts(0.0 to 1.0, 1.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        val right = pts(0.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(
            pts(0.0 to 0.5, 0.5 to 0.5, 0.5 to 0.5, 1.5 to 0.5, 2.0 to 0.5),
            center(left, right),
        )
    }

    @Test
    fun reversedRightBorderBailsToStartAndEndMids() {
        // Right runs opposite to left. Original bails after the first midpoint
        // and then appends the last-vertex mid (which happens to be the same).
        val left = pts(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        val right = pts(2.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        assertPtsEqual(pts(1.0 to 0.5, 1.0 to 0.5), center(left, right))
    }

    @Test
    fun bothBordersReversedStillWalksInGivenOrder() {
        val left = pts(2.0 to 1.0, 1.0 to 1.0, 0.0 to 1.0)
        val right = pts(2.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        assertPtsEqual(pts(2.0 to 0.5, 0.0 to 0.5), center(left, right))
    }

    @Test
    fun taperingNonParallel() {
        val left = pts(0.0 to 2.0, 1.0 to 1.5, 2.0 to 1.2)
        val right = pts(0.0 to 0.0, 1.0 to 0.1, 2.0 to 0.8)
        assertPtsEqual(
            pts(0.0 to 1.0, 0.5 to 0.75, 1.5 to 1.15, 2.0 to 1.0),
            center(left, right),
        )
    }

    @Test
    fun denseLeftVsSparseRight() {
        val left = (0..10).map { ll(it / 10.0, 1.0) }
        val right = pts(0.0 to 0.0, 1.0 to 0.0)
        assertPtsEqual(
            pts(
                0.0 to 0.5, 0.05 to 0.5, 0.1 to 0.5, 0.15 to 0.5, 0.2 to 0.5,
                0.25 to 0.5, 0.75 to 0.5, 1.0 to 0.5,
            ),
            center(left, right),
        )
    }

    @Test
    fun identicalBordersSkipInteriorDueToIntersections() {
        val left = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(pts(0.0 to 0.0, 2.0 to 0.0), center(left, right))
    }

    @Test
    fun onePointVsMany() {
        val left = pts(0.0 to 1.0)
        val right = pts(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertPtsEqual(pts(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5), center(left, right))
    }

    @Test
    fun jaggedBordersMatchPythonGolden() {
        val left = pts(0.0 to 1.0, 0.4 to 1.05, 1.1 to 0.95, 2.0 to 1.02)
        val right = pts(0.0 to 0.0, 0.7 to -0.02, 1.3 to 0.04, 2.0 to 0.0)
        assertPtsEqual(
            pts(
                0.0 to 0.5,
                0.2 to 0.525,
                0.55 to 0.515,
                0.9 to 0.46499999999999997,
                1.2000000000000002 to 0.495,
                1.65 to 0.53,
                2.0 to 0.51,
            ),
            center(left, right),
        )
    }

    @Test
    fun geographicSmallDegreesMatchPythonGolden() {
        val left = pts(8.4 to 49.0, 8.401 to 49.0005, 8.402 to 49.001)
        val right = pts(8.4 to 48.999, 8.4012 to 48.9994, 8.402 to 49.0001)
        assertPtsEqual(
            pts(
                8.4 to 48.9995,
                8.4006 to 48.999700000000004,
                8.4011 to 48.99995,
                8.401499999999999 to 49.0003,
                8.402 to 49.000550000000004,
            ),
            center(left, right),
        )
    }

    @Test
    fun segmentsIntersectBranches() {
        assertTrue(segmentsIntersect(ll(0.0, 0.0), ll(1.0, 1.0), ll(0.0, 1.0), ll(1.0, 0.0)))
        assertFalse(segmentsIntersect(ll(0.0, 0.0), ll(1.0, 0.0), ll(0.0, 1.0), ll(1.0, 1.0)))
        assertTrue(segmentsIntersect(ll(0.0, 0.0), ll(1.0, 0.0), ll(1.0, 0.0), ll(1.0, 1.0)))
        assertTrue(segmentsIntersect(ll(0.0, 0.0), ll(2.0, 0.0), ll(1.0, 0.0), ll(3.0, 0.0)))
        assertFalse(segmentsIntersect(ll(0.0, 0.0), ll(1.0, 0.0), ll(2.0, 0.0), ll(3.0, 0.0)))
    }

    @Test
    fun pointLeftOfLineAndEquality() {
        assertTrue(pointLeftOfLine(0.0, 1.0, 0.0, 0.0, 1.0, 0.0))
        assertFalse(pointLeftOfLine(0.0, -1.0, 0.0, 0.0, 1.0, 0.0))
        assertFalse(pointLeftOfLine(0.5, 0.0, 0.0, 0.0, 1.0, 0.0))
        assertTrue(pointsEqual(ll(0.0, 0.0), ll(0.0, 0.0)))
        assertFalse(pointsEqual(ll(0.0, 0.0), ll(1e-6, 0.0)))
        val m = mid(ll(0.0, 0.0), ll(2.0, 4.0))
        assertEquals(1.0, m.lon, 0.0)
        assertEquals(2.0, m.lat, 0.0)
    }
}
