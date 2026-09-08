package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.tools.Logging
import kotlin.math.sqrt

/**
 * Pure geometry used by [Lanelet] alignment (port of helpers in
 * `lanelet_representation.py`). Operates on [LonLat] lists so it is testable
 * without a live JOSM dataset.
 */
object LaneletGeometry {
    /**
     * Project [p] onto segment [a]-[b]. Returns (t in [0,1], projected point).
     */
    fun projectPointToSegment(p: LonLat, a: LonLat, b: LonLat): Pair<Double, LonLat> {
        val abx = b.lon - a.lon
        val aby = b.lat - a.lat
        val apx = p.lon - a.lon
        val apy = p.lat - a.lat
        val ab2 = abx * abx + aby * aby
        if (ab2 < 1e-20) return Pair(0.0, LonLat(a.lon, a.lat))
        var t = (apx * abx + apy * aby) / ab2
        t = t.coerceIn(0.0, 1.0)
        return Pair(t, LonLat(a.lon + t * abx, a.lat + t * aby))
    }

    /**
     * Signed distance from polyline to point. Positive = point is left of the
     * line when traversing the polyline. Mirrors Lanelet2 `geometry::signedDistance`.
     */
    fun signedDistance(polylinePts: List<LonLat>, point: LonLat): Double {
        if (polylinePts.size < 2) return 0.0
        var bestDSq = 1e30
        var bestSignedD = 0.0
        for (i in 0 until polylinePts.size - 1) {
            val a = polylinePts[i]
            val b = polylinePts[i + 1]
            val (_, proj) = projectPointToSegment(point, a, b)
            val dSq = distSq(point, proj)
            if (dSq < bestDSq) {
                bestDSq = dSq
                val d = if (dSq > 0) sqrt(dSq) else 0.0
                val cross = (b.lon - a.lon) * (point.lat - a.lat) - (b.lat - a.lat) * (point.lon - a.lon)
                bestSignedD = when {
                    cross > 0 -> d
                    cross < 0 -> -d
                    else -> 0.0
                }
            }
        }
        return bestSignedD
    }

    /**
     * Middle point of a polyline.
     *
     * Original docstring claims "centroid if >2 points", but the Jython actually
     * returns the vertex at index `len // 2` (Python 2 truncating division).
     * That quirk is preserved.
     */
    fun wayMiddlePoint(pts: List<LonLat>): LonLat? {
        if (pts.isEmpty()) return null
        if (pts.size > 2) {
            return pts[pts.size / 2]
        }
        return LonLat(
            (pts.first().lon + pts.last().lon) / 2.0,
            (pts.first().lat + pts.last().lat) / 2.0,
        )
    }

    /**
     * Align left and right polylines to driving direction.
     * Returns (leftReversed, rightReversed).
     *
     * Lanelet2 `geometry::align`: middle of right must be right of left line,
     * middle of left must be left of right line.
     */
    fun alignBounds(leftPts: List<LonLat>, rightPts: List<LonLat>): Pair<Boolean, Boolean> {
        if (leftPts.size < 2 || rightPts.size < 2) return Pair(false, false)
        val midRight = wayMiddlePoint(rightPts) ?: return Pair(false, false)
        val midLeft = wayMiddlePoint(leftPts) ?: return Pair(false, false)
        val rightOfLeft = signedDistance(leftPts, midRight) < 0
        val leftReversed = !rightOfLeft
        val leftOfRight = signedDistance(rightPts, midLeft) > 0
        val rightReversed = !leftOfRight
        return Pair(leftReversed, rightReversed)
    }

    fun wayPointsLonLat(way: Way?): List<LonLat> {
        if (way == null) return emptyList()
        val out = ArrayList<LonLat>()
        for (n in way.nodes) {
            val c = n.coor
            if (c != null) out.add(LonLat(c.lon(), c.lat()))
        }
        return out
    }

    fun alignBounds(leftWay: Way, rightWay: Way): Pair<Boolean, Boolean> {
        val leftPts = wayPointsLonLat(leftWay)
        val rightPts = wayPointsLonLat(rightWay)
        val (leftReversed, rightReversed) = alignBounds(leftPts, rightPts)
        if (leftReversed || rightReversed) {
            val parts = ArrayList<String>(2)
            if (leftReversed) parts.add("left")
            if (rightReversed) parts.add("right")
            Logging.debug("[lanelet_representation] DEBUG: reversed {0}", parts.joinToString(", "))
        }
        return Pair(leftReversed, rightReversed)
    }
}
