package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way

/**
 * Atomic primitive: holds the relation (canonical storage) and [WayView]s
 * (left, right). Mirrors C++ ConstLanelet / Lanelet. Bounds always in driving
 * direction after geometric alignment.
 *
 * Port of `Lanelet` in `lanelet_representation.py`.
 */
class Lanelet private constructor(
    val relation: Relation,
    private val storedLeft: WayView?,
    private val storedRight: WayView?,
    private val inverted: Boolean,
) {
    constructor(relation: Relation) : this(relation, viewsFrom(relation), inverted = false)

    private constructor(
        relation: Relation,
        views: Pair<WayView?, WayView?>,
        inverted: Boolean,
    ) : this(relation, views.first, views.second, inverted)

    /** Left border in driving direction. */
    fun leftBound(): WayView? = if (inverted) storedRight else storedLeft

    /** Right border in driving direction. */
    fun rightBound(): WayView? = if (inverted) storedLeft else storedRight

    /** (left_first, right_first) in driving direction. */
    fun getFirstNodesInDrivingDirection(): Pair<Node?, Node?> {
        val lv = leftBound()
        val rv = rightBound()
        if (lv == null || rv == null) return Pair(null, null)
        return Pair(lv.getFirstNode(), rv.getFirstNode())
    }

    /** (left_last, right_last) in driving direction. */
    fun getLastNodesInDrivingDirection(): Pair<Node?, Node?> {
        val lv = leftBound()
        val rv = rightBound()
        if (lv == null || rv == null) return Pair(null, null)
        return Pair(lv.getLastNode(), rv.getLastNode())
    }

    /**
     * Return a new [Lanelet] with inverted flag flipped (O(1) view).
     *
     * Matches the Jython: swaps left/right [WayView]s but does **not** reverse
     * their node order. (C++ `ConstLanelet::invert()` also reverses the
     * linestrings; this discrepancy is preserved.)
     */
    fun invert(): Lanelet = Lanelet(relation, storedLeft, storedRight, !inverted)

    companion object {
        internal fun extractLeftRightWays(relation: Relation?): Pair<Way?, Way?> {
            if (relation == null) return Pair(null, null)
            var leftW: Way? = null
            var rightW: Way? = null
            for (m in relation.members) {
                val role = m.role
                val mem = m.member ?: continue
                if (mem !is Way) continue
                when (role) {
                    "left" -> leftW = mem
                    "right" -> rightW = mem
                }
            }
            return Pair(leftW, rightW)
        }

        private fun viewsFrom(relation: Relation): Pair<WayView?, WayView?> {
            val (leftW, rightW) = extractLeftRightWays(relation)
            if (leftW == null || rightW == null) {
                return Pair(
                    if (leftW != null) WayView(leftW, false) else null,
                    if (rightW != null) WayView(rightW, false) else null,
                )
            }
            val (leftReversed, rightReversed) = LaneletGeometry.alignBounds(leftW, rightW)
            return Pair(WayView(leftW, leftReversed), WayView(rightW, rightReversed))
        }
    }
}
