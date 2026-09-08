package org.openstreetmap.josm.plugins.lanelet2.infra

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lanelet2-style centerline between two bounds.
 *
 * Port of `core/infra/lanelet2_centerline_calculate.py`, itself a port of
 * lanelet2_core `Lanelet.cpp` `calculateCenterline` / `BoundChecker` /
 * `findClosestNonintersectingPoint`. Coordinates are (lon, lat) planar degrees.
 *
 * Pure math: no JOSM `DataSet` or GUI.
 */
object Centerline {
    const val EPS_PT = 1e-12
    const val EPS_SEG = 1e-10

    /**
     * Return centerline vertices in driving order.
     *
     * [leftPts] / [rightPts] are aligned polylines in driving direction.
     * Same-length is the documented intent of the original, but unequal lengths
     * are handled (the Python never enforced equal size).
     */
    fun calculateCenterlinePoints(leftPts: List<LonLat>, rightPts: List<LonLat>): List<LonLat> {
        if (leftPts.isEmpty() || rightPts.isEmpty()) return emptyList()

        val bounds = BoundChecker(leftPts, rightPts)
        val out = ArrayList<LonLat>()
        out.add(mid(leftPts[0], rightPts[0]))

        var li = 0
        var ri = 0
        if (pointsEqual(leftPts[0], rightPts[0])) {
            li = 1
        }

        while (li < leftPts.size - 1 || ri < rightPts.size - 1) {
            val lastXy = out.last()

            val (liCand, liDist) = findClosestNonintersecting(
                leftPts, rightPts, bounds, lastXy, li + 1, ri, isLeft = true,
            )
            val (riCand, riDist) = findClosestNonintersecting(
                leftPts, rightPts, bounds, lastXy, ri + 1, li, isLeft = false,
            )

            val takeLeft = liDist != null && (riDist == null || liDist <= riDist + EPS_SEG)
            val takeRight = riDist != null && (liDist == null || liDist > riDist + EPS_SEG)

            if (takeLeft) {
                if (liCand == null || liCand >= leftPts.size) break
                out.add(mid(leftPts[liCand], rightPts[ri]))
                li = liCand
            } else if (takeRight) {
                if (riCand == null || riCand >= rightPts.size) break
                out.add(mid(leftPts[li], rightPts[riCand]))
                ri = riCand
            } else {
                break
            }
        }

        val atLast = li == leftPts.size - 1 && ri == rightPts.size - 1
        if (!atLast) {
            out.add(mid(leftPts.last(), rightPts.last()))
        }
        return out
    }
}

internal fun dist(a: LonLat, b: LonLat): Double {
    val dx = a.lon - b.lon
    val dy = a.lat - b.lat
    return sqrt(dx * dx + dy * dy)
}

internal fun distSq(a: LonLat, b: LonLat): Double {
    val dx = a.lon - b.lon
    val dy = a.lat - b.lat
    return dx * dx + dy * dy
}

internal fun pointsEqual(a: LonLat, b: LonLat): Boolean =
    distSq(a, b) < Centerline.EPS_PT * Centerline.EPS_PT

internal fun mid(a: LonLat, b: LonLat): LonLat =
    LonLat((a.lon + b.lon) * 0.5, (a.lat + b.lat) * 0.5)

internal fun orient(a: LonLat, b: LonLat, c: LonLat): Double =
    (b.lat - a.lat) * (c.lon - b.lon) - (b.lon - a.lon) * (c.lat - b.lat)

internal fun onSegment(p: LonLat, q: LonLat, r: LonLat): Boolean {
    val eps = Centerline.EPS_SEG
    return min(p.lon, r.lon) - eps <= q.lon && q.lon <= maxOf(p.lon, r.lon) + eps &&
        min(p.lat, r.lat) - eps <= q.lat && q.lat <= maxOf(p.lat, r.lat) + eps
}

internal fun segmentsIntersect(a1: LonLat, a2: LonLat, b1: LonLat, b2: LonLat): Boolean {
    val eps2 = Centerline.EPS_SEG * Centerline.EPS_SEG
    val o1 = orient(a1, a2, b1)
    val o2 = orient(a1, a2, b2)
    val o3 = orient(b1, b2, a1)
    val o4 = orient(b1, b2, a2)
    if (o1 * o2 < -eps2 && o3 * o4 < -eps2) return true
    if (abs(o1) < eps2 && onSegment(a1, b1, a2)) return true
    if (abs(o2) < eps2 && onSegment(a1, b2, a2)) return true
    if (abs(o3) < eps2 && onSegment(b1, a1, b2)) return true
    if (abs(o4) < eps2 && onSegment(b1, a2, b2)) return true
    return false
}

internal fun pointLeftOfLine(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean =
    (bx - ax) * (py - ay) - (by - ay) * (px - ax) > Centerline.EPS_SEG

private class BoundChecker(leftPts: List<LonLat>, rightPts: List<LonLat>) {
    val leftSegs: List<Pair<LonLat, LonLat>> = makeSegs(leftPts)
    val rightSegs: List<Pair<LonLat, LonLat>> = makeSegs(rightPts)
    val entry: Pair<LonLat, LonLat>? =
        if (leftPts.isNotEmpty() && rightPts.isNotEmpty()) Pair(rightPts[0], leftPts[0]) else null
    val exit: Pair<LonLat, LonLat>? =
        if (leftPts.isNotEmpty() && rightPts.isNotEmpty()) Pair(leftPts.last(), rightPts.last()) else null

    fun intersectsLeft(seg: Pair<LonLat, LonLat>): Boolean {
        val a1 = seg.first
        val a2 = seg.second
        for ((b1, b2) in leftSegs) {
            if (segmentsIntersect(a1, a2, b1, b2)) {
                if (!pointsEqual(b1, a1)) return true
            }
        }
        return false
    }

    fun intersectsRight(seg: Pair<LonLat, LonLat>): Boolean {
        val a1 = seg.first
        val a2 = seg.second
        for ((b1, b2) in rightSegs) {
            if (segmentsIntersect(a1, a2, b1, b2)) {
                if (!pointsEqual(b1, a1)) return true
            }
        }
        return false
    }

    fun crossesEntry(seg: Pair<LonLat, LonLat>): Boolean {
        val e = entry ?: return false
        val a1 = seg.first
        val a2 = seg.second
        if (!segmentsIntersect(a1, a2, e.first, e.second)) return false
        return pointLeftOfLine(a2.lon, a2.lat, e.first.lon, e.first.lat, e.second.lon, e.second.lat)
    }

    fun crossesExit(seg: Pair<LonLat, LonLat>): Boolean {
        val e = exit ?: return false
        val a1 = seg.first
        val a2 = seg.second
        if (!segmentsIntersect(a1, a2, e.first, e.second)) return false
        return pointLeftOfLine(a2.lon, a2.lat, e.first.lon, e.first.lat, e.second.lon, e.second.lat)
    }

    fun intersects(seg: Pair<LonLat, LonLat>): Boolean =
        intersectsLeft(seg) || intersectsRight(seg) || crossesEntry(seg) || crossesExit(seg)

    fun secondCrossesBounds(seg: Pair<LonLat, LonLat>, isLeft: Boolean): Boolean {
        val segs = if (isLeft) leftSegs else rightSegs
        val a1 = seg.first
        val a2 = seg.second
        for ((b1, b2) in segs) {
            if (!segmentsIntersect(a1, a2, b1, b2)) continue
            if (!pointsEqual(b1, a2) && !pointsEqual(b2, a2)) return true
        }
        return false
    }

    companion object {
        fun makeSegs(pts: List<LonLat>): List<Pair<LonLat, LonLat>> {
            val out = ArrayList<Pair<LonLat, LonLat>>(maxOf(0, pts.size - 1))
            for (i in 0 until pts.size - 1) {
                out.add(Pair(pts[i], pts[i + 1]))
            }
            return out
        }
    }
}

private fun findClosestNonintersecting(
    leftPts: List<LonLat>,
    rightPts: List<LonLat>,
    bounds: BoundChecker,
    lastXy: LonLat,
    minIdxOnSearchLine: Int,
    fixIdxOnOtherLine: Int,
    isLeft: Boolean,
): Pair<Int?, Double?> {
    val searchPts: List<LonLat>
    val otherXy: LonLat
    if (isLeft) {
        searchPts = leftPts
        otherXy = rightPts[fixIdxOnOtherLine]
    } else {
        searchPts = rightPts
        otherXy = leftPts[fixIdxOnOtherLine]
    }

    if (minIdxOnSearchLine >= searchPts.size) return Pair(null, null)

    val candIndices = (minIdxOnSearchLine until searchPts.size).toMutableList()
    candIndices.sortBy { dist(searchPts[it], otherXy) }

    var bestIdx: Int? = null
    var bestD: Double? = null
    val dLastOther = dist(otherXy, lastXy)

    for (j in candIndices) {
        val currentBest = bestD
        if (currentBest != null) {
            val dOtherCand = dist(otherXy, searchPts[j]) * 0.5
            if (dOtherCand - dLastOther > currentBest + Centerline.EPS_SEG) break
        }
        val candDist = dist(searchPts[j], otherXy) * 0.5
        if (currentBest != null && currentBest <= candDist + Centerline.EPS_SEG) continue

        val boundA = otherXy
        val boundB = searchPts[j]
        val centerCand = mid(boundA, boundB)
        val centerlineSeg = Pair(lastXy, centerCand)
        val inv = Pair(boundB, boundA)

        if (bounds.intersects(centerlineSeg)) continue
        if (bounds.secondCrossesBounds(Pair(boundA, boundB), isLeft)) continue
        if (bounds.secondCrossesBounds(inv, !isLeft)) continue

        bestD = candDist
        bestIdx = j
    }

    return Pair(bestIdx, bestD)
}
