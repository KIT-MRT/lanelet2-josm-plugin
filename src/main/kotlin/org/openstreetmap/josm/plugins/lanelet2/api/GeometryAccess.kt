package org.openstreetmap.josm.plugins.lanelet2.api

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.plugins.lanelet2.infra.Centerline
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletGeometry
import org.openstreetmap.josm.plugins.lanelet2.infra.LonLat
import org.openstreetmap.josm.plugins.lanelet2.infra.wayViewPointsLonLat

/**
 * Geometry helpers reused from `infra` without exposing that package as a
 * contract. Coordinates are (lon, lat) planar degrees, matching the lanelet
 * model.
 */
class GeometryAccess internal constructor() {

    /**
     * Lanelet2-style centerline vertices in driving order.
     *
     * [left] / [right] are aligned polylines. Delegates to
     * [Centerline.calculateCenterlinePoints].
     */
    fun calculateCenterlinePoints(left: List<LonLat>, right: List<LonLat>): List<LonLat> =
        Centerline.calculateCenterlinePoints(left, right)

    /**
     * Centerline of [lanelet] from its (direction-aligned) left and right
     * bounds. Empty when a bound is missing.
     */
    fun centerlineOf(lanelet: Lanelet): List<LonLat> {
        val left = wayViewPointsLonLat(lanelet.leftBound())
        val right = wayViewPointsLonLat(lanelet.rightBound())
        return Centerline.calculateCenterlinePoints(left, right)
    }

    /**
     * Great-circle length of [lanelet]'s centerline, in metres.
     *
     * Uses [LatLon.greatCircleDistance] (Haversine). Zero when the centerline
     * has fewer than two vertices.
     */
    fun centerlineLengthMeters(lanelet: Lanelet): Double {
        val pts = centerlineOf(lanelet)
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until pts.lastIndex) {
            val a = LatLon(pts[i].lat, pts[i].lon)
            val b = LatLon(pts[i + 1].lat, pts[i + 1].lon)
            sum += a.greatCircleDistance(b)
        }
        return sum
    }

    /**
     * Middle vertex of a polyline. Matches the Jython quirk: for more than
     * two points this is index `len // 2`, not a centroid.
     */
    fun wayMiddlePoint(pts: List<LonLat>): LonLat? = LaneletGeometry.wayMiddlePoint(pts)
}
