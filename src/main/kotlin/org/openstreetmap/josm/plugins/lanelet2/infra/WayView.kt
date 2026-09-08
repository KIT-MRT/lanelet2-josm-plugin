package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import kotlin.math.sqrt

/**
 * Lightweight wrapper around a JOSM [Way] that optionally exposes it in reversed
 * order. Analogous to C++ LineString invert() returning a view.
 *
 * Port of `WayView` in `lanelet_representation.py`.
 */
class WayView(
    val way: Way?,
    val isReversed: Boolean = false,
) {
    private val nodesCache: List<Node> by lazy {
        way?.nodes?.toList() ?: emptyList()
    }

    /** First node in driving direction. */
    fun getFirstNode(): Node? {
        val nds = nodesCache
        if (nds.isEmpty()) return null
        return if (isReversed) nds.last() else nds.first()
    }

    /** Last node in driving direction. */
    fun getLastNode(): Node? {
        val nds = nodesCache
        if (nds.isEmpty()) return null
        return if (isReversed) nds.first() else nds.last()
    }

    /** Nodes in driving order. */
    fun getNodes(): List<Node> = if (isReversed) nodesCache.asReversed() else nodesCache
}

/** List of (lat, lon) from [wayView] nodes in driving order. */
fun wayViewPoints(wayView: WayView?): List<LatLon> {
    if (wayView == null) return emptyList()
    val out = ArrayList<LatLon>()
    for (n in wayView.getNodes()) {
        val c = n.coor
        if (c != null) out.add(LatLon(c.lat(), c.lon()))
    }
    return out
}

/** List of (lon, lat) from [wayView] nodes in driving order (x, y for planar geometry). */
fun wayViewPointsLonLat(wayView: WayView?): List<LonLat> {
    if (wayView == null) return emptyList()
    val out = ArrayList<LonLat>()
    for (n in wayView.getNodes()) {
        val c = n.coor
        if (c != null) out.add(LonLat(c.lon(), c.lat()))
    }
    return out
}

/** Unit tangent at first point (driving order), (dlon, dlat). */
fun wayViewTangentAtStart(wayView: WayView): Tangent {
    val nds = wayView.getNodes()
    if (nds.size < 2) return Tangent(0.0, 0.0)
    val c0 = nds[0].coor
    val c1 = nds[1].coor
    if (c0 == null || c1 == null) return Tangent(0.0, 0.0)
    val dx = c1.lon() - c0.lon()
    val dy = c1.lat() - c0.lat()
    val n = sqrt(dx * dx + dy * dy)
    if (n < 1e-10) return Tangent(0.0, 0.0)
    return Tangent(dx / n, dy / n)
}

/** Unit tangent at last point (driving order), (dlon, dlat). */
fun wayViewTangentAtEnd(wayView: WayView): Tangent {
    val nds = wayView.getNodes()
    if (nds.size < 2) return Tangent(0.0, 0.0)
    val c0 = nds[nds.size - 2].coor
    val c1 = nds[nds.size - 1].coor
    if (c0 == null || c1 == null) return Tangent(0.0, 0.0)
    val dx = c1.lon() - c0.lon()
    val dy = c1.lat() - c0.lat()
    val n = sqrt(dx * dx + dy * dy)
    if (n < 1e-10) return Tangent(0.0, 0.0)
    return Tangent(dx / n, dy / n)
}
