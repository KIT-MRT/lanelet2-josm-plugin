package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.coor.ILatLon
import org.openstreetmap.josm.data.osm.BBox
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round

/**
 * Node heights (`ele`, metres): interpolation along a way, the nearest known
 * height for new nodes, and detection of height jumps between neighbours.
 * Pure over JOSM primitives, so it runs headless.
 */
object HeightTools {
    const val ELE_KEY = "ele"

    /**
     * Heights beyond this (metres, either sign) are no heights. Some tools
     * write -FLT_MAX (`-340282349999999991754788743781432688640`) for "unknown".
     */
    const val MAX_ABS_ELE_M = 100_000.0
    private const val EPS_M = 0.0005
    private const val M_PER_DEG_LAT = 111_320.0

    fun eleOf(n: Node): Double? = parseEle(n.get(ELE_KEY))

    /** An `ele` value in metres, or null when absent, not a number or implausible. */
    fun parseEle(value: String?): Double? =
        value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= MAX_ABS_ELE_M }

    /** Millimetres, trailing zeros dropped: `110`, `110.5`, `-2.125`. */
    fun formatEle(z: Double): String {
        val s = String.format(Locale.US, "%.3f", round(z * 1000.0) / 1000.0).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    /**
     * Heights for the vertices between consecutive anchors, linear in the
     * distance along the line. [cumDist] is the distance from the first vertex
     * per vertex, [anchors] vertex index -> height (at least two). Vertices
     * outside the first..last anchor range are not returned.
     */
    fun interpolate(cumDist: DoubleArray, anchors: Map<Int, Double>): Map<Int, Double> {
        val idx = anchors.keys.sorted()
        require(idx.size >= 2) { "need two anchors" }
        val out = LinkedHashMap<Int, Double>()
        for (k in 0 until idx.size - 1) {
            val i0 = idx[k]
            val i1 = idx[k + 1]
            val z0 = anchors.getValue(i0)
            val z1 = anchors.getValue(i1)
            val span = cumDist[i1] - cumDist[i0]
            for (i in i0 + 1 until i1) {
                val t = if (span > 1e-9) (cumDist[i] - cumDist[i0]) / span else (i - i0).toDouble() / (i1 - i0)
                out[i] = z0 + (z1 - z0) * t
            }
        }
        return out
    }

    /** New heights to write ([changes]), or why there are none ([error]). */
    data class Plan(val changes: List<Pair<Node, Double>>, val error: String? = null)

    /**
     * Interpolate heights along [way] between [anchorNodes] (two or more of
     * its nodes), or between its two ends when fewer are given. Anchors must
     * have an `ele`; only nodes whose height actually changes are returned.
     */
    fun planInterpolation(way: Way, anchorNodes: Collection<Node>): Plan {
        val nodes = way.nodes
        val chosen = anchorNodes.filter { it in nodes }
        if (way.isClosed && chosen.size < 2) {
            return Plan(emptyList(), "way/${way.uniqueId} is closed: select at least two of its nodes as anchors")
        }
        val anchorIdx = if (chosen.size >= 2) chosen.map { nodes.indexOf(it) }.distinct() else listOf(0, nodes.size - 1)
        if (anchorIdx.size < 2 || nodes.size < 3) {
            return Plan(emptyList(), "nothing lies between the anchors of way/${way.uniqueId}")
        }
        val anchors = LinkedHashMap<Int, Double>()
        for (i in anchorIdx) {
            val z = eleOf(nodes[i]) ?: return Plan(emptyList(), "anchor node/${nodes[i].uniqueId} has no ele height")
            anchors[i] = z
        }
        val cum = DoubleArray(nodes.size)
        for (i in 1 until nodes.size) {
            val a = nodes[i - 1].coor ?: return Plan(emptyList(), "node/${nodes[i - 1].uniqueId} has no position")
            val b = nodes[i].coor ?: return Plan(emptyList(), "node/${nodes[i].uniqueId} has no position")
            cum[i] = cum[i - 1] + a.greatCircleDistance(b)
        }
        val anchorNodeSet = anchorIdx.map { nodes[it] }.toSet()
        val done = HashSet<Node>()
        val changes = ArrayList<Pair<Node, Double>>()
        for ((i, z) in interpolate(cum, anchors)) {
            val n = nodes[i]
            if (n in anchorNodeSet || !done.add(n)) continue // a node listed twice keeps its first value
            val cur = eleOf(n)
            if (cur == null || abs(cur - z) > EPS_M) changes.add(n to z)
        }
        return Plan(changes)
    }

    /**
     * The nearest node with an `ele` to [at], searching outward from 5 m up to
     * [maxRadiusM] with JOSM's spatial index. [exclude] never counts.
     */
    fun nearestWithEle(ds: DataSet, at: ILatLon, exclude: Set<Node>, maxRadiusM: Double = 500.0): Pair<Node, Double>? {
        var r = 5.0
        while (true) {
            val dLat = r / M_PER_DEG_LAT
            val dLon = r / (M_PER_DEG_LAT * cos(Math.toRadians(at.lat())).coerceAtLeast(1e-6))
            val box = BBox(at.lon() - dLon, at.lat() - dLat, at.lon() + dLon, at.lat() + dLat)
            var best: Node? = null
            var bestZ = 0.0
            var bestD = Double.MAX_VALUE
            for (n in ds.searchNodes(box)) {
                if (n.isDeleted || n in exclude) continue
                val z = eleOf(n) ?: continue
                val c = n.coor ?: continue
                val d = at.greatCircleDistance(c)
                // Only within the circle: a node outside it could be beaten by
                // one just beyond this box.
                if (d <= r && d < bestD) {
                    best = n
                    bestZ = z
                    bestD = d
                }
            }
            if (best != null) return best to bestZ
            if (r >= maxRadiusM) return null
            r = minOf(r * 4, maxRadiusM)
        }
    }

    /**
     * Heights for [newNodes] (created without `ele`):
     * - a node with a known height along its ways in two directions (inserted
     *   into a way, the joint of a new way and the way it continues, or inside
     *   a new way drawn between existing nodes) is interpolated by distance
     *   between the two nearest of them;
     * - a free end of a way, or an orphan, takes the height of the nearest node.
     * The other new nodes never count as known heights, so a new way between
     * two existing nodes gets one straight profile between them.
     */
    fun planNewNodeHeights(ds: DataSet, newNodes: Collection<Node>): Plan =
        planHeights(ds, newNodes.filter { !it.hasKey(ELE_KEY) }, newNodes.toHashSet())

    /**
     * The same rule for [nodes] whether or not they have a height yet: none of
     * [unknown] counts as a known height (re-planning heights this plugin
     * guessed earlier must not anchor on other guesses). Only nodes whose
     * height changes are returned.
     */
    fun planHeights(ds: DataSet, nodes: Collection<Node>, unknown: Set<Node>): Plan {
        val changes = ArrayList<Pair<Node, Double>>()
        for (n in nodes) {
            if (n.isDeleted || n.dataSet !== ds) continue
            val c = n.coor ?: continue
            val z = heightAlongWays(n, unknown) ?: nearestWithEle(ds, c, unknown)?.second ?: continue
            val cur = eleOf(n)
            if (cur == null || abs(cur - z) > EPS_M) changes.add(n to z)
        }
        return Plan(changes)
    }

    /**
     * Interpolated height of [n] between the two nearest known heights found
     * walking from it toward the ends of its ways, or null when fewer than two
     * directions lead to one (an end node or an orphan).
     */
    fun heightAlongWays(n: Node, unknown: Set<Node>): Double? {
        val found = knownHeightsAlongWays(n, unknown).sortedBy { it.first }
        if (found.size < 2) return null
        val (d1, z1) = found[0]
        val (d2, z2) = found[1]
        return if (d1 + d2 < 1e-9) (z1 + z2) / 2 else z1 + (z2 - z1) * d1 / (d1 + d2)
    }

    /** (distance along the way, height) of the first known height in each direction. */
    private fun knownHeightsAlongWays(n: Node, unknown: Set<Node>): List<Pair<Double, Double>> {
        val start = n.coor ?: return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (w in n.referrers) {
            if (w !is Way || w.isDeleted) continue
            val ns = w.nodes
            for (i in ns.indices) {
                if (ns[i] !== n) continue
                for (step in intArrayOf(-1, 1)) {
                    var j = i + step
                    var d = 0.0
                    var prev: ILatLon = start
                    while (j in ns.indices) {
                        val m = ns[j]
                        if (m === n) break // a closed way leading back to the node
                        val c = m.coor ?: break
                        d += prev.greatCircleDistance(c)
                        prev = c
                        val z = if (m in unknown) null else eleOf(m)
                        if (z != null) {
                            out.add(d to z)
                            break
                        }
                        j += step
                    }
                }
            }
        }
        return out
    }

    data class Jump(val way: Way, val a: Node, val b: Node, val dz: Double)

    /**
     * Neighbouring nodes on ways through [nodes] whose heights differ by more
     * than [thresholdM], where at least one of the pair is in [nodes]. Nodes
     * without `ele` have no height to compare. Largest first.
     */
    fun heightJumps(nodes: Collection<Node>, thresholdM: Double): List<Jump> {
        val set = nodes.toHashSet()
        val seen = HashSet<Pair<Long, Long>>()
        val out = ArrayList<Jump>()
        for (n in nodes) {
            for (w in n.referrers) {
                if (w !is Way || w.isDeleted) continue
                val ns = w.nodes
                for (i in 0 until ns.size - 1) {
                    val a = ns[i]
                    val b = ns[i + 1]
                    if (a !in set && b !in set) continue
                    if (!seen.add(minOf(a.uniqueId, b.uniqueId) to maxOf(a.uniqueId, b.uniqueId))) continue
                    val za = eleOf(a) ?: continue
                    val zb = eleOf(b) ?: continue
                    if (abs(zb - za) > thresholdM) out.add(Jump(w, a, b, abs(zb - za)))
                }
            }
        }
        return out.sortedByDescending { it.dz }
    }

    /** One-line summary for a notification, or null when there are no jumps. */
    fun describeJumps(jumps: List<Jump>, thresholdM: Double): String? {
        if (jumps.isEmpty()) return null
        val j = jumps.first()
        val more = if (jumps.size > 1) " (and ${jumps.size - 1} more)" else ""
        return "Height jump of ${formatEle(j.dz)} m between node/${j.a.uniqueId} and node/${j.b.uniqueId} " +
            "on way/${j.way.uniqueId}$more, above the ${formatEle(thresholdM)} m threshold"
    }
}
