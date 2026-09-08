package org.openstreetmap.josm.plugins.lanelet2.tools

import kotlin.math.sqrt

/**
 * Approximate concave hull used by [HighlightFileBoundaries].
 *
 * Port of the grid / dilate / flood-fill / ring-trace / Douglas-Peucker
 * pipeline in `core/josm_tools/highlight_file_boundaries.py`.
 *
 * Collections are insertion-ordered ([LinkedHashSet] / [LinkedHashMap]) so
 * ring start vertices are stable. Jython 2.7 `set`/`dict` iteration is
 * hash-ordered and can start a ring at a different vertex, which can change
 * the Douglas-Peucker keep-set. See AGENTS.md.
 */
object FileBoundaryHull {
    const val DILATE_R = 2
    const val DP_EPS_CELLS = 1.3
    const val MIN_CELL_M = 0.5
    const val MAX_CELLS = 6_000_000

    const val FILL_OPACITY = 0.16
    const val STROKE_WIDTH = 3
    const val HUE_STEP = 0.6180339887498949
    const val HUE_START = 0.11
    const val SAT = 0.65
    const val VAL = 0.95

    const val STYLE_TITLE = "Lanelet2 File Boundaries"

    fun hsvToHex(h: Double, s: Double, v: Double): String {
        var i = (h * 6.0).toInt()
        val f = h * 6.0 - i
        val p = v * (1.0 - s)
        val q = v * (1.0 - f * s)
        val t = v * (1.0 - (1.0 - f) * s)
        i %= 6
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return "#%02x%02x%02x".format((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    fun palette(n: Int): List<String> =
        (0 until n).map { i -> hsvToHex((HUE_START + i * HUE_STEP) % 1.0, SAT, VAL) }

    fun chooseCell(minE: Double, minN: Double, maxE: Double, maxN: Double, cellM: Double): Double {
        var cell = maxOf(cellM, MIN_CELL_M)
        val nx = (maxE - minE) / cell + 1.0
        val ny = (maxN - minN) / cell + 1.0
        if (nx * ny > MAX_CELLS) {
            val scale = sqrt((nx * ny) / MAX_CELLS.toDouble())
            cell *= scale
        }
        return cell
    }

    fun occupiedCells(points: List<Pair<Double, Double>>, minE: Double, minN: Double, cell: Double): LinkedHashSet<Pair<Int, Int>> {
        val occ = LinkedHashSet<Pair<Int, Int>>()
        for ((e, n) in points) {
            val ix = ((e - minE) / cell).toInt()
            val iy = ((n - minN) / cell).toInt()
            occ.add(Pair(ix, iy))
        }
        return occ
    }

    fun dilate(occ: Set<Pair<Int, Int>>, r: Int): LinkedHashSet<Pair<Int, Int>> {
        if (r <= 0) return LinkedHashSet(occ)
        val out = LinkedHashSet<Pair<Int, Int>>()
        val rng = -r..r
        for ((ix, iy) in occ) {
            for (dx in rng) {
                for (dy in rng) {
                    out.add(Pair(ix + dx, iy + dy))
                }
            }
        }
        return out
    }

    fun fillHoles(occ: Set<Pair<Int, Int>>): LinkedHashSet<Pair<Int, Int>> {
        if (occ.isEmpty()) return LinkedHashSet(occ)
        val xs = occ.map { it.first }
        val ys = occ.map { it.second }
        val minx = xs.minOrNull()!! - 1
        val maxx = xs.maxOrNull()!! + 1
        val miny = ys.minOrNull()!! - 1
        val maxy = ys.maxOrNull()!! + 1
        val exterior = LinkedHashSet<Pair<Int, Int>>()
        val stack = ArrayList<Pair<Int, Int>>()
        for (x in minx..maxx) {
            for (y in listOf(miny, maxy)) {
                val c = Pair(x, y)
                if (c !in occ && c !in exterior) {
                    exterior.add(c)
                    stack.add(c)
                }
            }
        }
        for (y in miny..maxy) {
            for (x in listOf(minx, maxx)) {
                val c = Pair(x, y)
                if (c !in occ && c !in exterior) {
                    exterior.add(c)
                    stack.add(c)
                }
            }
        }
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.lastIndex)
            for (c in listOf(Pair(x + 1, y), Pair(x - 1, y), Pair(x, y + 1), Pair(x, y - 1))) {
                if (c.first in minx..maxx && c.second in miny..maxy && c !in occ && c !in exterior) {
                    exterior.add(c)
                    stack.add(c)
                }
            }
        }
        val result = LinkedHashSet(occ)
        for (x in minx..maxx) {
            for (y in miny..maxy) {
                val c = Pair(x, y)
                if (c !in occ && c !in exterior) {
                    result.add(c)
                }
            }
        }
        return result
    }

    fun pointSegDist2(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (ax, ay) = a
        val (bx, by) = b
        val (px, py) = p
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            val ex = px - ax
            val ey = py - ay
            return ex * ex + ey * ey
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        if (t < 0.0) t = 0.0
        else if (t > 1.0) t = 1.0
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return ex * ex + ey * ey
    }

    fun dpSimplify(pts: List<Pair<Int, Int>>, eps: Double): List<Pair<Int, Int>> {
        val n = pts.size
        if (n < 3) return pts
        val eps2 = eps * eps
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<Pair<Int, Int>>()
        stack.add(Pair(0, n - 1))
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeAt(stack.lastIndex)
            if (j <= i + 1) continue
            val a = Pair(pts[i].first.toDouble(), pts[i].second.toDouble())
            val b = Pair(pts[j].first.toDouble(), pts[j].second.toDouble())
            var dmax = -1.0
            var idx = -1
            for (k in i + 1 until j) {
                val pk = Pair(pts[k].first.toDouble(), pts[k].second.toDouble())
                val d = pointSegDist2(pk, a, b)
                if (d > dmax) {
                    dmax = d
                    idx = k
                }
            }
            if (idx != -1 && dmax > eps2) {
                keep[idx] = true
                stack.add(Pair(i, idx))
                stack.add(Pair(idx, j))
            }
        }
        return pts.indices.filter { keep[it] }.map { pts[it] }
    }

    fun extractRings(occ: Set<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
        val edges = LinkedHashMap<Pair<Int, Int>, ArrayList<Pair<Int, Int>>>()
        fun add(a: Pair<Int, Int>, b: Pair<Int, Int>) {
            edges.getOrPut(a) { ArrayList() }.add(b)
        }
        for ((ix, iy) in occ) {
            if (Pair(ix, iy - 1) !in occ) add(Pair(ix, iy), Pair(ix + 1, iy))
            if (Pair(ix + 1, iy) !in occ) add(Pair(ix + 1, iy), Pair(ix + 1, iy + 1))
            if (Pair(ix, iy + 1) !in occ) add(Pair(ix + 1, iy + 1), Pair(ix, iy + 1))
            if (Pair(ix - 1, iy) !in occ) add(Pair(ix, iy + 1), Pair(ix, iy))
        }
        val rings = ArrayList<List<Pair<Int, Int>>>()
        while (edges.isNotEmpty()) {
            val start = edges.keys.first()
            val ring = ArrayList<Pair<Int, Int>>()
            var cur = start
            var guard = 0
            val limit = 8 * occ.size + 16
            while (true) {
                ring.add(cur)
                val outs = edges[cur]
                if (outs == null || outs.isEmpty()) break
                val nxt = outs.removeAt(outs.lastIndex)
                if (outs.isEmpty()) edges.remove(cur)
                cur = nxt
                guard++
                if (cur == start || guard > limit) break
            }
            if (ring.size >= 3) rings.add(ring)
        }
        return rings
    }

    fun hullRingsFor(
        points: List<Pair<Double, Double>>,
        minE: Double,
        minN: Double,
        cell: Double,
    ): List<List<Pair<Double, Double>>> {
        var occ: Set<Pair<Int, Int>> = occupiedCells(points, minE, minN, cell)
        if (occ.isEmpty()) return emptyList()
        occ = dilate(occ, DILATE_R)
        occ = fillHoles(occ)
        val rings = extractRings(occ)
        val out = ArrayList<List<Pair<Double, Double>>>()
        for (ring in rings) {
            val simplified = dpSimplify(ring, DP_EPS_CELLS)
            if (simplified.size < 3) continue
            out.add(simplified.map { (vx, vy) -> Pair(minE + vx * cell, minN + vy * cell) })
        }
        return out
    }

    fun globalExtent(groups: Map<String, List<Pair<Double, Double>>>): Quadruple? {
        var minE: Double? = null
        var minN: Double? = null
        var maxE: Double? = null
        var maxN: Double? = null
        for (pts in groups.values) {
            for ((e, n) in pts) {
                if (minE == null || e < minE) minE = e
                if (maxE == null || e > maxE) maxE = e
                if (minN == null || n < minN) minN = n
                if (maxN == null || n > maxN) maxN = n
            }
        }
        val e0 = minE ?: return null
        val n0 = minN ?: return null
        val e1 = maxE ?: return null
        val n1 = maxN ?: return null
        return Quadruple(e0, n0, e1, n1)
    }

    fun generateMapcss(order: List<String>, colors: List<String>): String {
        val lines = ArrayList<String>()
        lines.add("/* Auto-generated by highlight_file_boundaries.py - do not edit. */")
        lines.add("meta { title: \"$STYLE_TITLE\"; }")
        for (idx in order.indices) {
            val hexc = colors[idx]
            lines.add(
                "way[ll2_boundary][ll2_bnd=\"$idx\"] { " +
                    "color: $hexc; width: $STROKE_WIDTH; fill-color: $hexc; fill-opacity: $FILL_OPACITY; " +
                    "text: eval(tag(\"name\")); text-color: $hexc; }",
            )
        }
        return lines.joinToString("\n")
    }
}

data class Quadruple(
    val minE: Double,
    val minN: Double,
    val maxE: Double,
    val maxN: Double,
)
