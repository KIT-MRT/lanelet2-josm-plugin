package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.plugins.lanelet2.infra.Centerline
import org.openstreetmap.josm.plugins.lanelet2.infra.HeightTools
import org.openstreetmap.josm.plugins.lanelet2.infra.LonLat
import kotlin.math.hypot

/** Build viewer features and signatures from headless way / lanelet snapshots. */
object Viewer3dFeatures {
    /** Where along the centerline the direction arrow sits (fraction of its length). */
    const val ARROW_AT = 0.35

    /** The node's height, or null without a usable `ele` ([HeightTools.parseEle]). */
    fun nodeEleMetres(eleTag: String?): Double? = HeightTools.parseEle(eleTag)

    /**
     * Give the points of one line (x, y, z triples, [count] of them) whose
     * height is unknown (`known[i]` false) a height to draw at: interpolated
     * by distance between the nearest known heights before and after, or the
     * nearest known one at an end, or 0 when the line has none. Display only;
     * the node keeps its tag.
     */
    fun fillUnknownHeights(pts: DoubleArray, known: BooleanArray, count: Int) {
        var prev = -1
        var i = 0
        while (i < count) {
            if (known[i]) {
                prev = i
                i++
                continue
            }
            var next = i
            while (next < count && !known[next]) next++
            for (j in i until next) {
                pts[j * 3 + 2] = when {
                    prev < 0 && next >= count -> 0.0
                    prev < 0 -> pts[next * 3 + 2]
                    next >= count -> pts[prev * 3 + 2]
                    else -> {
                        val d0 = along(pts, prev, j)
                        val d1 = along(pts, j, next)
                        val z0 = pts[prev * 3 + 2]
                        val z1 = pts[next * 3 + 2]
                        if (d0 + d1 < 1e-9) (z0 + z1) / 2 else z0 + (z1 - z0) * d0 / (d0 + d1)
                    }
                }
            }
            i = next
        }
    }

    /** Horizontal length of the polyline from point [a] to point [b] (a <= b). */
    private fun along(pts: DoubleArray, a: Int, b: Int): Double {
        var d = 0.0
        for (k in a until b) d += hypot(pts[k * 3 + 3] - pts[k * 3], pts[k * 3 + 4] - pts[k * 3 + 1])
        return d
    }

    fun featureForWay(way: WaySnapshot, anchor: Anchor): ViewerFeature? {
        val n = way.nodes.size
        var pts = DoubleArray(n * 3)
        var nodeIds = LongArray(n)
        val known = BooleanArray(n)
        var allKnown = true
        var k = 0
        for (node in way.nodes) {
            val lat = node.lat ?: continue
            val lon = node.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            pts[k * 3] = Viewer3dEnu.roundCoord(x)
            pts[k * 3 + 1] = Viewer3dEnu.roundCoord(y)
            val z = nodeEleMetres(node.eleTag)
            if (z != null) pts[k * 3 + 2] = Viewer3dEnu.roundCoord(z)
            known[k] = z != null
            allKnown = allKnown && z != null
            nodeIds[k] = node.uniqueId
            k++
        }
        if (k < 2) return null
        if (!allKnown) {
            fillUnknownHeights(pts, known, k)
            for (i in 0 until k) if (!known[i]) pts[i * 3 + 2] = Viewer3dEnu.roundCoord(pts[i * 3 + 2])
        }
        if (k < n) {
            pts = pts.copyOf(k * 3)
            nodeIds = nodeIds.copyOf(k)
        }
        val tags = linkedMapOf<String, String>()
        way.type?.let { tags["type"] = it }
        way.subtype?.let { tags["subtype"] = it }
        way.participantBicycle?.let { tags["participant:bicycle"] = it }
        return ViewerFeature(
            id = "way/${way.uniqueId}",
            kind = "line",
            tags = tags,
            pts = pts,
            nodeIds = nodeIds,
        )
    }

    fun featureSignature(feat: ViewerFeature): FeatureSignature =
        FeatureSignature(
            nodeIds = feat.nodeIds,
            pts = feat.pts,
            type = feat.tags["type"],
            subtype = feat.tags["subtype"],
            participantBicycle = feat.tags["participant:bicycle"],
            lanelet = feat.lanelet,
        )

    /**
     * lanelet2 parses `one_way` as a bool (`lexical_cast<bool>`, then
     * "true"/"yes"/"false"/"no"); a lanelet is two-way for vehicles only when
     * it parses as false. Absent means one-way (GenericTrafficRules).
     */
    fun isTwoWay(oneWay: String?): Boolean = oneWay?.trim() in setOf("no", "false", "0")

    /** ENU points of a bound, unknown heights filled like [featureForWay] draws them. */
    private fun enuPoints(nodes: List<NodeSnapshot>, anchor: Anchor): List<DoubleArray> {
        val flat = DoubleArray(nodes.size * 3)
        val known = BooleanArray(nodes.size)
        var k = 0
        for (n in nodes) {
            val lat = n.lat ?: continue
            val lon = n.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            val z = nodeEleMetres(n.eleTag)
            flat[k * 3] = x
            flat[k * 3 + 1] = y
            flat[k * 3 + 2] = z ?: 0.0
            known[k] = z != null
            k++
        }
        if (known.take(k).any { !it }) fillUnknownHeights(flat, known, k)
        return List(k) { i -> doubleArrayOf(flat[i * 3], flat[i * 3 + 1], flat[i * 3 + 2]) }
    }

    /**
     * A lanelet for the viewer: references to its bounds (which stream as way
     * features) and its direction arrow. `pts` holds one point (the arrow, or
     * the start of the lanelet) so the viewer can place it in its spatial tiles.
     */
    fun featureForLanelet(l: LaneletSnapshot, anchor: Anchor): ViewerFeature? {
        val leftId = l.leftWayId ?: return null
        val rightId = l.rightWayId ?: return null
        val left = enuPoints(l.left, anchor)
        val right = enuPoints(l.right, anchor)
        if (left.size < 2 || right.size < 2) return null
        val arrow = laneletArrow(left, right)?.map { Viewer3dEnu.roundCoord(it) }?.toDoubleArray()
        val rep = arrow?.copyOf(3) ?: doubleArrayOf(
            Viewer3dEnu.roundCoord((left[0][0] + right[0][0]) / 2),
            Viewer3dEnu.roundCoord((left[0][1] + right[0][1]) / 2),
            Viewer3dEnu.roundCoord((left[0][2] + right[0][2]) / 2),
        )
        val tags = linkedMapOf<String, String>()
        l.subtype?.let { tags["subtype"] = it }
        l.oneWay?.let { tags["one_way"] = it }
        return ViewerFeature(
            id = "relation/${l.uniqueId}",
            kind = "lanelet",
            tags = tags,
            pts = rep,
            lanelet = LaneletRefs(
                left = "way/$leftId",
                right = "way/$rightId",
                leftReversed = l.leftReversed,
                rightReversed = l.rightReversed,
                arrow = arrow,
                twoWay = isTwoWay(l.oneWay),
            ),
        )
    }

    /**
     * Arrow pose at [ARROW_AT] of the centerline (lanelet2
     * `calculateCenterline`, run on ENU metres), with its height and slope
     * taken from the bounds: x, y, z, dx, dy, dz (unit), lanelet width there.
     * [left] / [right] are x, y, z points in driving order.
     */
    fun laneletArrow(left: List<DoubleArray>, right: List<DoubleArray>): DoubleArray? {
        val cl = Centerline.calculateCenterlinePoints(
            left.map { LonLat(it[0], it[1]) },
            right.map { LonLat(it[0], it[1]) },
        )
        if (cl.size < 2) return null
        val cum = DoubleArray(cl.size)
        for (i in 1 until cl.size) cum[i] = cum[i - 1] + hypot(cl[i].lon - cl[i - 1].lon, cl[i].lat - cl[i - 1].lat)
        val total = cum.last()
        if (total < 1e-6) return null
        val target = total * ARROW_AT
        // The segment holding the target, skipping zero-length ones (the
        // centerline can repeat a vertex, e.g. upstream's duplicated tail).
        var k = 0
        while (k < cl.size - 2 && (cum[k + 1] < target || cum[k + 1] - cum[k] <= 1e-9)) k++
        val seg = cum[k + 1] - cum[k]
        if (seg <= 1e-9) return null
        val t = ((target - cum[k]) / seg).coerceIn(0.0, 1.0)
        val x = cl[k].lon + (cl[k + 1].lon - cl[k].lon) * t
        val y = cl[k].lat + (cl[k + 1].lat - cl[k].lat) * t
        var dx = (cl[k + 1].lon - cl[k].lon) / seg
        var dy = (cl[k + 1].lat - cl[k].lat) / seg
        val z = surfaceZ(left, right, x, y)
        // Slope from the surface half a metre either side of the arrow.
        val slope = surfaceZ(left, right, x + dx * 0.5, y + dy * 0.5) - surfaceZ(left, right, x - dx * 0.5, y - dy * 0.5)
        val len = hypot(hypot(dx, dy), slope)
        dx /= len
        dy /= len
        val width = closestOnPolyline(left, x, y).first + closestOnPolyline(right, x, y).first
        return doubleArrayOf(x, y, z, dx, dy, slope / len, width)
    }

    /** Height under (x, y): the mean of both bounds' heights at their closest points. */
    private fun surfaceZ(left: List<DoubleArray>, right: List<DoubleArray>, x: Double, y: Double): Double =
        (closestOnPolyline(left, x, y).second + closestOnPolyline(right, x, y).second) / 2

    /** Horizontal distance from (x, y) to the polyline, and its height there. */
    private fun closestOnPolyline(pts: List<DoubleArray>, x: Double, y: Double): Pair<Double, Double> {
        var bestD = Double.MAX_VALUE
        var bestZ = pts[0][2]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val ex = b[0] - a[0]
            val ey = b[1] - a[1]
            val len2 = ex * ex + ey * ey
            val t = if (len2 > 1e-12) (((x - a[0]) * ex + (y - a[1]) * ey) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(a[0] + ex * t - x, a[1] + ey * t - y)
            if (d < bestD) {
                bestD = d
                bestZ = a[2] + (b[2] - a[2]) * t
            }
        }
        return bestD to bestZ
    }

    /** ENU bbox of a lanelet (both bounds), for culling. */
    fun laneletBBoxEnu(l: LaneletSnapshot, anchor: Anchor): EnuBounds? {
        val pts = enuPoints(l.left, anchor) + enuPoints(l.right, anchor)
        if (pts.isEmpty()) return null
        return EnuBounds(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })
    }

    fun laneletInCull(l: LaneletSnapshot, anchor: Anchor, bounds: EnuBounds?, cullOn: Boolean): Boolean {
        if (!cullOn) return true
        if (bounds == null) return false
        val lb = laneletBBoxEnu(l, anchor) ?: return false
        return bounds.intersects(lb)
    }

    fun wayBBoxEnu(way: WaySnapshot, anchor: Anchor): EnuBounds? {
        var wminX = 1.0e18
        var wminY = 1.0e18
        var wmaxX = -1.0e18
        var wmaxY = -1.0e18
        var found = false
        for (n in way.nodes) {
            val lat = n.lat ?: continue
            val lon = n.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            found = true
            if (x < wminX) wminX = x
            if (x > wmaxX) wmaxX = x
            if (y < wminY) wminY = y
            if (y > wmaxY) wmaxY = y
        }
        if (!found) return null
        return EnuBounds(wminX, wminY, wmaxX, wmaxY)
    }

    fun wayInCull(way: WaySnapshot, anchor: Anchor, bounds: EnuBounds?, cullOn: Boolean): Boolean {
        if (!cullOn) return true
        if (bounds == null) return false
        val wb = wayBBoxEnu(way, anchor) ?: return false
        return bounds.intersects(wb)
    }

    fun computeAnchor(ways: Collection<WaySnapshot>): Anchor? {
        var minLat = 1.0e9
        var maxLat = -1.0e9
        var minLon = 1.0e9
        var maxLon = -1.0e9
        var found = false
        for (w in ways) {
            for (n in w.nodes) {
                val lat = n.lat ?: continue
                val lon = n.lon ?: continue
                found = true
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
            }
        }
        if (!found) return null
        return Anchor((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    }

    fun cullBoundsEnu(viewCenter: Pair<Double, Double>?, anchor: Anchor, rangeM: Double): EnuBounds? {
        if (viewCenter == null) return null
        val (cx, cy) = Viewer3dEnu.enu(viewCenter.first, viewCenter.second, anchor.lat, anchor.lon)
        val half = rangeM / 2.0
        return EnuBounds(cx - half, cy - half, cx + half, cy + half)
    }

    fun featureFromEnuBounds(bounds: EnuBounds): ViewerFeature {
        val corners = listOf(
            bounds.minX to bounds.minY,
            bounds.minX to bounds.maxY,
            bounds.maxX to bounds.maxY,
            bounds.maxX to bounds.minY,
            bounds.minX to bounds.minY,
        )
        return ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            pts = floorLoop(corners),
        )
    }

    /** Closed loop on the floor (z = 0), rounded like every streamed coordinate. */
    private fun floorLoop(corners: List<Pair<Double, Double>>): DoubleArray {
        val pts = DoubleArray(corners.size * 3)
        corners.forEachIndexed { i, (x, y) ->
            pts[i * 3] = Viewer3dEnu.roundCoord(x)
            pts[i * 3 + 1] = Viewer3dEnu.roundCoord(y)
        }
        return pts
    }

    /**
     * Lat/lon box (minLon, minLat, maxLon, maxLat) that contains [bounds], for
     * JOSM's spatial index. Slightly generous; callers still apply the exact
     * ENU test ([wayInCull]).
     */
    fun latLonBoxOf(bounds: EnuBounds, anchor: Anchor, marginM: Double = 1.0): DoubleArray {
        val (lat0, lon0) = Viewer3dEnu.enuToLatLon(bounds.minX - marginM, bounds.minY - marginM, anchor.lat, anchor.lon)
        val (lat1, lon1) = Viewer3dEnu.enuToLatLon(bounds.maxX + marginM, bounds.maxY + marginM, anchor.lat, anchor.lon)
        return doubleArrayOf(minOf(lon0, lon1), minOf(lat0, lat1), maxOf(lon0, lon1), maxOf(lat0, lat1))
    }

    fun finalizeViewportFeature(
        feat: ViewerFeature,
        viewCenterEnu: EnuPoint?,
        followCamera: Boolean,
    ): ViewerFeature {
        var out = feat
        if (viewCenterEnu != null) {
            out = out.copy(
                center = listOf(
                    Viewer3dEnu.roundCoord(viewCenterEnu.x),
                    Viewer3dEnu.roundCoord(viewCenterEnu.y),
                    Viewer3dEnu.roundCoord(viewCenterEnu.z),
                ),
            )
        }
        if (followCamera) {
            val tags = LinkedHashMap(out.tags)
            tags["follow_camera"] = "1"
            out = out.copy(tags = tags)
        }
        return out
    }

    fun buildViewportFromMapBounds(
        bounds: ViewBounds,
        anchor: Anchor,
        viewCenterEnu: EnuPoint?,
        followCamera: Boolean,
    ): ViewerFeature {
        val corners = listOf(
            bounds.minLat to bounds.minLon,
            bounds.minLat to bounds.maxLon,
            bounds.maxLat to bounds.maxLon,
            bounds.maxLat to bounds.minLon,
            bounds.minLat to bounds.minLon,
        )
        val base = ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            pts = floorLoop(corners.map { (la, lo) -> Viewer3dEnu.enu(la, lo, anchor.lat, anchor.lon) }),
        )
        return finalizeViewportFeature(base, viewCenterEnu, followCamera)
    }
}
