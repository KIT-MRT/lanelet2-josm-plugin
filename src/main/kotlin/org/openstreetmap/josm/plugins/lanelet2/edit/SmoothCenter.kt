package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.AddCommand
import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.Tangent
import org.openstreetmap.josm.plugins.lanelet2.infra.WayView
import org.openstreetmap.josm.plugins.lanelet2.infra.wayViewPoints
import org.openstreetmap.josm.plugins.lanelet2.infra.wayViewTangentAtEnd
import org.openstreetmap.josm.plugins.lanelet2.infra.wayViewTangentAtStart
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Cubic Hermite smoothing of a center lanelet using entry/exit tangents.
 *
 * Driving direction comes from [Lanelet] geometric [WayView] alignment (not
 * [Lanelet.invert]). Entry attaches at first nodes of the center wayview, exit
 * at last nodes.
 *
 * Each successful smooth is one [org.openstreetmap.josm.command.SequenceCommand]
 * (Jython `do_smooth` / `do_smooth_one_border`); a batch of N centers is N
 * undo entries.
 *
 * Port of `smooth_center_lanelet.py`. [SmoothCenterLanelet.run] opens the
 * collection dialog when that setting is on; otherwise it uses the current
 * selection.
 */
object SmoothCenter {
    const val TITLE = "Smooth Center Lanelet"
    const val TITLE_BATCH = "Smooth Center Lanelet (Batch)"
    const val SEQUENCE_BOTH = "Smooth center lanelet borders"
    const val SEQUENCE_ONE = "Smooth center lanelet one border"
    const val N_SAMPLES = 25

    /** (lat, lon) matching Jython `way_view_points`. */
    data class LatLonPt(val lat: Double, val lon: Double)

    /** (dlon, dlat) matching Jython `way_view_tangent_*`. */
    data class TangentPt(val dlon: Double, val dlat: Double)

    /**
     * Python 2 `round`: half away from zero. Kotlin / Python 3 use banker's
     * rounding; the Jython production path is Python 2. Used only for piecewise
     * sample allocation (`int(round(float(n_samples) * seg / total))`).
     */
    fun py2Round(x: Double): Int {
        return if (x >= 0.0) floor(x + 0.5).toInt() else ceil(x - 0.5).toInt()
    }

    fun latLonToMeters(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cos(lat0 * Math.PI / 180.0)
        val x = (lon - lon0) * mPerDegLon
        val y = (lat - lat0) * mPerDegLat
        return Pair(x, y)
    }

    fun metersToLatLon(x: Double, y: Double, lat0: Double, lon0: Double): LatLonPt {
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cos(lat0 * Math.PI / 180.0)
        val lon = lon0 + x / mPerDegLon
        val lat = lat0 + y / mPerDegLat
        return LatLonPt(lat, lon)
    }

    /**
     * Cubic Hermite spline from [p0] to [p1] with tangents [t0], [t1].
     * P: (lat, lon). T: (dlon, dlat) unit vectors.
     *
     * `float(i) / (n_samples - 1)` in the original; Kotlin `/` on Double matches.
     */
    fun hermiteSpline(
        p0: LatLonPt,
        p1: LatLonPt,
        t0: TangentPt,
        t1: TangentPt,
        nSamples: Int,
        tangentScale: Double = 1.0,
    ): List<LatLonPt> {
        val lat0 = 0.5 * (p0.lat + p1.lat)
        val lon0 = 0.5 * (p0.lon + p1.lon)
        val p0m = latLonToMeters(p0.lat, p0.lon, lat0, lon0)
        val p1m = latLonToMeters(p1.lat, p1.lon, lat0, lon0)
        val chordM = sqrt((p1m.first - p0m.first) * (p1m.first - p0m.first) + (p1m.second - p0m.second) * (p1m.second - p0m.second))
        if (chordM < 1e-6) {
            return if (nSamples > 0) List(nSamples) { p0 } else emptyList()
        }

        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cos(lat0 * Math.PI / 180.0)
        var t0x = t0.dlon * mPerDegLon
        var t0y = t0.dlat * mPerDegLat
        val n0 = sqrt(t0x * t0x + t0y * t0y)
        // Exact `!= 0` comparison, matching Jython.
        if (n0 != 0.0) {
            t0x /= n0
            t0y /= n0
        }
        var t1x = t1.dlon * mPerDegLon
        var t1y = t1.dlat * mPerDegLat
        val n1 = sqrt(t1x * t1x + t1y * t1y)
        if (n1 != 0.0) {
            t1x /= n1
            t1y /= n1
        }

        val scaleM = chordM * tangentScale
        val t0mx = t0x * scaleM
        val t0my = t0y * scaleM
        val t1mx = t1x * scaleM
        val t1my = t1y * scaleM

        val out = ArrayList<LatLonPt>(nSamples)
        for (i in 0 until nSamples) {
            val t = if (nSamples > 1) i.toDouble() / (nSamples - 1).toDouble() else 1.0
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2 * t3 - 3 * t2 + 1
            val h10 = t3 - 2 * t2 + t
            val h01 = -2 * t3 + 3 * t2
            val h11 = t3 - t2
            val xM = h00 * p0m.first + h10 * t0mx + h01 * p1m.first + h11 * t1mx
            val yM = h00 * p0m.second + h10 * t0my + h01 * p1m.second + h11 * t1my
            out.add(metersToLatLon(xM, yM, lat0, lon0))
        }
        return out
    }

    fun chordTangent(pPrev: LatLonPt, pNext: LatLonPt): TangentPt {
        val dlon = pNext.lon - pPrev.lon
        val dlat = pNext.lat - pPrev.lat
        val n = sqrt(dlon * dlon + dlat * dlat)
        if (n < 1e-10) return TangentPt(0.0, 0.0)
        return TangentPt(dlon / n, dlat / n)
    }

    fun smoothBorder(
        p0: LatLonPt,
        p1: LatLonPt,
        t0: TangentPt,
        t1: TangentPt,
        nSamples: Int = N_SAMPLES,
        constraintPoints: List<LatLonPt>? = null,
    ): List<LatLonPt> {
        if (constraintPoints.isNullOrEmpty()) {
            return hermiteSpline(p0, p1, t0, t1, nSamples, tangentScale = 1.0)
        }
        val fullPts = ArrayList<LatLonPt>(constraintPoints.size + 2)
        fullPts.add(p0)
        fullPts.addAll(constraintPoints)
        fullPts.add(p1)
        val nSeg = fullPts.size - 1
        if (nSeg < 1) {
            return hermiteSpline(p0, p1, t0, t1, nSamples, tangentScale = 1.0)
        }
        val lat0 = 0.5 * (p0.lat + p1.lat)
        val lon0 = 0.5 * (p0.lon + p1.lon)
        val segLengths = DoubleArray(nSeg)
        for (i in 0 until nSeg) {
            val a = latLonToMeters(fullPts[i].lat, fullPts[i].lon, lat0, lon0)
            val b = latLonToMeters(fullPts[i + 1].lat, fullPts[i + 1].lon, lat0, lon0)
            segLengths[i] = sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
        }
        val total = segLengths.sum()
        if (total < 1e-6) {
            return hermiteSpline(p0, p1, t0, t1, nSamples, tangentScale = 1.0)
        }
        val nPerSeg = IntArray(nSeg) { i ->
            maxOf(2, py2Round(nSamples.toDouble() * segLengths[i] / total))
        }
        val result = ArrayList<LatLonPt>()
        result.add(p0)
        for (i in 0 until nSeg) {
            val pa = fullPts[i]
            val pb = fullPts[i + 1]
            val ta = if (i == 0) t0 else chordTangent(fullPts[i - 1], fullPts[i + 1])
            val tb = if (i == nSeg - 1) t1 else chordTangent(fullPts[i], fullPts[i + 2])
            val seg = hermiteSpline(pa, pb, ta, tb, nPerSeg[i], tangentScale = 1.0)
            if (seg.size > 1) result.addAll(seg.subList(1, seg.size))
        }
        return result
    }

    fun smoothBorder(centerView: WayView, entryView: WayView, exitView: WayView, nSamples: Int = N_SAMPLES, constraintPoints: List<LatLonPt>? = null): List<LatLonPt>? {
        val ptsCenter = wayViewPoints(centerView)
        if (ptsCenter.size < 2) return null
        val p0 = LatLonPt(ptsCenter.first().lat(), ptsCenter.first().lon())
        val p1 = LatLonPt(ptsCenter.last().lat(), ptsCenter.last().lon())
        val t0 = tangentPt(wayViewTangentAtEnd(entryView))
        val t1 = tangentPt(wayViewTangentAtStart(exitView))
        return smoothBorder(p0, p1, t0, t1, nSamples, constraintPoints)
    }

    fun constraintPtsFromNodes(wayView: WayView, constraintNodes: Collection<Node>, lat0: Double, lon0: Double): List<LatLonPt> {
        if (constraintNodes.isEmpty()) return emptyList()
        val nds = wayView.getNodes()
        if (nds.size < 3) return emptyList()
        val pts = wayViewPoints(wayView)
        val cumul = ArrayList<Double>(pts.size)
        cumul.add(0.0)
        for (i in 1 until pts.size) {
            val a = latLonToMeters(pts[i - 1].lat(), pts[i - 1].lon(), lat0, lon0)
            val b = latLonToMeters(pts[i].lat(), pts[i].lon(), lat0, lon0)
            val d = sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
            cumul.add(cumul.last() + d)
        }
        val nodeToDist = HashMap<Long, Pair<Double, LatLonPt?>>()
        for ((idx, n) in nds.withIndex()) {
            val uid = n.uniqueId
            if (idx in 1..(nds.size - 2)) {
                val c = n.coor
                val pt = if (c != null) LatLonPt(c.lat(), c.lon()) else null
                nodeToDist[uid] = Pair(cumul[idx], pt)
            }
        }
        val out = ArrayList<Pair<Double, LatLonPt>>()
        for (n in constraintNodes) {
            if (n.coor == null) continue
            val hit = nodeToDist[n.uniqueId] ?: continue
            val pt = hit.second ?: continue
            out.add(Pair(hit.first, pt))
        }
        out.sortBy { it.first }
        return out.map { it.second }
    }

    fun sameNode(a: Node?, b: Node?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return try {
            a.uniqueId == b.uniqueId
        } catch (_: Exception) {
            false
        }
    }

    fun laneletLastNodesMatch(lanelet: Relation, leftTarget: Node?, rightTarget: Node?): Boolean {
        val ll = Lanelet(lanelet)
        val lv = ll.leftBound() ?: return false
        val rv = ll.rightBound() ?: return false
        val ln = lv.getNodes().lastOrNull()
        val rn = rv.getNodes().lastOrNull()
        return sameNode(ln, leftTarget) && sameNode(rn, rightTarget)
    }

    fun laneletFirstNodesMatch(lanelet: Relation, leftTarget: Node?, rightTarget: Node?): Boolean {
        val ll = Lanelet(lanelet)
        val lv = ll.leftBound() ?: return false
        val rv = ll.rightBound() ?: return false
        val ln = lv.getNodes().firstOrNull()
        val rn = rv.getNodes().firstOrNull()
        return sameNode(ln, leftTarget) && sameNode(rn, rightTarget)
    }

    /**
     * Find entry and exit lanelets for [centerLl] using undirected successor
     * search, then a topology fallback. The fallback runs when *either* side is
     * missing and **overwrites** both (Jython latent: a way-based entry is
     * replaced if exit was not found).
     */
    fun findEntryExitForCenter(centerLl: Relation, data: DataSet): Pair<Relation?, Relation?> {
        val center = Lanelet(centerLl)
        val (leftFirst, rightFirst) = center.getFirstNodesInDrivingDirection()
        val (leftLast, rightLast) = center.getLastNodesInDrivingDirection()
        if (leftFirst == null || rightFirst == null || leftLast == null || rightLast == null) {
            return Pair(null, null)
        }
        val (leftPred, rightPred) = CreateLaneletRelation.findPredecessorWays(data, center, useDirected = false)
        val (leftSucc, rightSucc) = CreateLaneletRelation.findSuccessorWays(data, center, useDirected = CreateLaneletRelation.useDirectedSuccessor())

        var entryLl: Relation? = null
        var exitLl: Relation? = null

        if (leftPred != null && rightPred != null && leftSucc != null && rightSucc != null) {
            val predIds = setOf(leftPred.uniqueId, rightPred.uniqueId)
            val succIds = setOf(leftSucc.uniqueId, rightSucc.uniqueId)
            if (predIds.size >= 2 && succIds.size >= 2) {
                for (ll in LaneletSelection.findLaneletsContainingLinestrings(data, listOf(leftPred, rightPred))) {
                    if (ll === centerLl) continue
                    val (lw, rw) = Lanelet.extractLeftRightWays(ll)
                    if (lw == null || rw == null) continue
                    if (setOf(lw.uniqueId, rw.uniqueId) == predIds) {
                        entryLl = ll
                        break
                    }
                }
                for (ll in LaneletSelection.findLaneletsContainingLinestrings(data, listOf(leftSucc, rightSucc))) {
                    if (ll === centerLl) continue
                    val (lw, rw) = Lanelet.extractLeftRightWays(ll)
                    if (lw == null || rw == null) continue
                    if (setOf(lw.uniqueId, rw.uniqueId) == succIds) {
                        exitLl = ll
                        break
                    }
                }
            }
        }

        // Latent: runs if either is missing and overwrites both from topology.
        if (entryLl == null || exitLl == null) {
            for (prim in data.relations) {
                if (prim == null || prim.get("type") != "lanelet" || prim === centerLl) continue
                if (laneletLastNodesMatch(prim, leftFirst, rightFirst)) {
                    entryLl = prim
                    break
                }
            }
            for (prim in data.relations) {
                if (prim == null || prim.get("type") != "lanelet" || prim === centerLl) continue
                if (laneletFirstNodesMatch(prim, leftLast, rightLast)) {
                    exitLl = prim
                    break
                }
            }
        }
        return Pair(entryLl, exitLl)
    }

    fun commandsForBoth(
        data: DataSet,
        entryLl: Relation,
        centerLl: Relation,
        exitLl: Relation,
    ): List<Command>? {
        val entry = Lanelet(entryLl)
        val center = Lanelet(centerLl)
        val exit = Lanelet(exitLl)
        val entryLeft = entry.leftBound() ?: return null
        val entryRight = entry.rightBound() ?: return null
        val centerLeft = center.leftBound() ?: return null
        val centerRight = center.rightBound() ?: return null
        val exitLeft = exit.leftBound() ?: return null
        val exitRight = exit.rightBound() ?: return null

        val leftPts = smoothBorder(centerLeft, entryLeft, exitLeft) ?: return null
        val rightPts = smoothBorder(centerRight, entryRight, exitRight) ?: return null
        if (leftPts.size < 3 || rightPts.size < 3) return null

        val centerLeftWay = centerLeft.way ?: return null
        val centerRightWay = centerRight.way ?: return null
        val centerLeftNodes = centerLeft.getNodes()
        val centerRightNodes = centerRight.getNodes()
        val entryNodeLeft = centerLeftNodes.first()
        val exitNodeLeft = centerLeftNodes.last()
        val entryNodeRight = centerRightNodes.first()
        val exitNodeRight = centerRightNodes.last()

        val newLeftNodes = ArrayList<Node>()
        newLeftNodes.add(entryNodeLeft)
        for (i in 1 until leftPts.size - 1) {
            val pt = leftPts[i]
            newLeftNodes.add(Node(LatLon(pt.lat, pt.lon)))
        }
        newLeftNodes.add(exitNodeLeft)

        val newRightNodes = ArrayList<Node>()
        newRightNodes.add(entryNodeRight)
        for (i in 1 until rightPts.size - 1) {
            val pt = rightPts[i]
            newRightNodes.add(Node(LatLon(pt.lat, pt.lon)))
        }
        newRightNodes.add(exitNodeRight)

        val oldLeftInterior = centerLeftNodes.subList(1, centerLeftNodes.size - 1).toList()
        val oldRightInterior = centerRightNodes.subList(1, centerRightNodes.size - 1).toList()

        val commands = ArrayList<Command>()
        for (n in newLeftNodes.subList(1, newLeftNodes.size - 1)) {
            commands.add(AddCommand(data, n))
        }
        for (n in newRightNodes.subList(1, newRightNodes.size - 1)) {
            commands.add(AddCommand(data, n))
        }
        val newCenterLeft = Way(centerLeftWay)
        newCenterLeft.setNodes(newLeftNodes)
        commands.add(ChangeCommand(centerLeftWay, newCenterLeft))
        val newCenterRight = Way(centerRightWay)
        newCenterRight.setNodes(newRightNodes)
        commands.add(ChangeCommand(centerRightWay, newCenterRight))
        for (n in oldLeftInterior + oldRightInterior) {
            try {
                val refs = n.referrers
                val otherRefs = refs.filter { it !== centerLeftWay && it !== centerRightWay }
                if (otherRefs.isEmpty()) {
                    commands.add(DeleteCommand(data, n))
                }
            } catch (_: Exception) {
            }
        }
        return commands
    }

    fun commandsForOneBorder(
        data: DataSet,
        entryLl: Relation,
        centerLl: Relation,
        exitLl: Relation,
        whichBorder: String,
        constraintNodes: Collection<Node> = emptyList(),
    ): List<Command>? {
        val entry = Lanelet(entryLl)
        val center = Lanelet(centerLl)
        val exit = Lanelet(exitLl)
        val entryLeft = entry.leftBound() ?: return null
        val entryRight = entry.rightBound() ?: return null
        val centerLeft = center.leftBound() ?: return null
        val centerRight = center.rightBound() ?: return null
        val exitLeft = exit.leftBound() ?: return null
        val exitRight = exit.rightBound() ?: return null

        val centerView: WayView
        val entryView: WayView
        val exitView: WayView
        if (whichBorder == "left") {
            centerView = centerLeft
            entryView = entryLeft
            exitView = exitLeft
        } else {
            centerView = centerRight
            entryView = entryRight
            exitView = exitRight
        }
        val ptsRef = wayViewPoints(centerView)
        if (ptsRef.size < 2) return null
        val constraintPts = constraintPtsFromNodes(
            centerView,
            constraintNodes,
            ptsRef[0].lat(),
            ptsRef[0].lon(),
        )
        val pts = smoothBorder(
            centerView,
            entryView,
            exitView,
            nSamples = N_SAMPLES,
            constraintPoints = constraintPts.ifEmpty { null },
        ) ?: return null
        if (pts.size < 3) return null

        val way = centerView.way ?: return null
        val centerNodes = centerView.getNodes()
        val entryNode = centerNodes.first()
        val exitNode = centerNodes.last()
        val newNodes = ArrayList<Node>()
        newNodes.add(entryNode)
        for (i in 1 until pts.size - 1) {
            val pt = pts[i]
            newNodes.add(Node(LatLon(pt.lat, pt.lon)))
        }
        newNodes.add(exitNode)
        val oldInterior = centerNodes.subList(1, centerNodes.size - 1).toList()
        val commands = ArrayList<Command>()
        for (n in newNodes.subList(1, newNodes.size - 1)) {
            commands.add(AddCommand(data, n))
        }
        val newWay = Way(way)
        newWay.setNodes(newNodes)
        commands.add(ChangeCommand(way, newWay))
        for (n in oldInterior) {
            try {
                val refs = n.referrers
                val otherRefs = refs.filter { it !== way }
                if (otherRefs.isEmpty()) {
                    commands.add(DeleteCommand(data, n))
                }
            } catch (_: Exception) {
            }
        }
        return commands
    }

    fun applyBoth(
        data: DataSet,
        layer: OsmDataLayer?,
        entryLl: Relation,
        centerLl: Relation,
        exitLl: Relation,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        ui: UserPrompts? = null,
    ): Boolean {
        val entry = Lanelet(entryLl)
        val center = Lanelet(centerLl)
        val exit = Lanelet(exitLl)
        if (entry.leftBound() == null || entry.rightBound() == null ||
            center.leftBound() == null || center.rightBound() == null ||
            exit.leftBound() == null || exit.rightBound() == null
        ) {
            ui?.warn("Lanelets have invalid left/right bounds.", TITLE)
            return false
        }
        val leftPts = smoothBorder(center.leftBound()!!, entry.leftBound()!!, exit.leftBound()!!)
        val rightPts = smoothBorder(center.rightBound()!!, entry.rightBound()!!, exit.rightBound()!!)
        if (leftPts == null || rightPts == null) {
            ui?.warn(
                "Could not compute spline. Check that entry/exit borders have nodes with coordinates.",
                TITLE,
            )
            return false
        }
        if (leftPts.size < 3 || rightPts.size < 3) {
            ui?.warn(
                "Spline produced too few points (${leftPts.size} left, ${rightPts.size} right). Need at least 3.",
                TITLE,
            )
            return false
        }
        val commands = commandsForBoth(data, entryLl, centerLl, exitLl) ?: return false
        applySequence(SEQUENCE_BOTH, commands, layer, undo)
        return true
    }

    fun applyOneBorder(
        data: DataSet,
        layer: OsmDataLayer?,
        entryLl: Relation,
        centerLl: Relation,
        exitLl: Relation,
        whichBorder: String,
        constraintNodes: Collection<Node> = emptyList(),
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        ui: UserPrompts? = null,
    ): Boolean {
        val entry = Lanelet(entryLl)
        val center = Lanelet(centerLl)
        val exit = Lanelet(exitLl)
        if (entry.leftBound() == null || entry.rightBound() == null ||
            center.leftBound() == null || center.rightBound() == null ||
            exit.leftBound() == null || exit.rightBound() == null
        ) {
            ui?.warn("Lanelets have invalid left/right bounds.", TITLE)
            return false
        }
        val commands = commandsForOneBorder(data, entryLl, centerLl, exitLl, whichBorder, constraintNodes)
        if (commands == null) {
            ui?.warn(
                "Could not compute spline. Check that entry/exit borders have nodes with coordinates.",
                TITLE,
            )
            return false
        }
        applySequence(SEQUENCE_ONE, commands, layer, undo)
        return true
    }

    fun applyBatch(
        data: DataSet,
        layer: OsmDataLayer?,
        centerLls: List<Relation>,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        ui: UserPrompts = Dialogs,
    ): Int {
        var smoothed = 0
        for (centerLl in centerLls) {
            val (entryLl, exitLl) = findEntryExitForCenter(centerLl, data)
            if (entryLl == null || exitLl == null) continue
            if (applyBoth(data, layer, entryLl, centerLl, exitLl, undo, ui)) {
                smoothed++
            }
        }
        if (smoothed == 0) {
            ui.warn(
                "No center lanelets could be smoothed.\n\n" +
                    "For each center, entry and exit lanelets must share boundary nodes.\n" +
                    "Selected ${centerLls.size} center(s).",
                TITLE_BATCH,
            )
        } else {
            ui.info("Smoothed $smoothed center lanelet(s).", TITLE_BATCH)
        }
        return smoothed
    }

    internal fun waysFromLanelets(entryLl: Relation, centerLl: Relation, exitLl: Relation): Set<Way> {
        val ways = LinkedHashSet<Way>()
        for (rel in listOf(entryLl, centerLl, exitLl)) {
            val ll = Lanelet(rel)
            for (bound in listOf(ll.leftBound(), ll.rightBound())) {
                val w = bound?.way
                if (w != null) ways.add(w)
            }
        }
        return ways
    }

    private fun tangentPt(t: Tangent) = TangentPt(t.dlon, t.dlat)
}

/**
 * Smooth selected center lanelets (batch).
 */
object SmoothCenterLanelet {
    const val HELP_TEXT = """Smooth the left and right bounds of a center lanelet using entry/exit tangents.

Select the CENTER lanelet(s). Entry and exit are found from shared boundary nodes.
Each successful smooth is one undo entry."""

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", SmoothCenter.TITLE)
            return
        }
        val data = layer.data
        if (org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic.shouldOpenCollectionDialog()) {
            org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog.showLaneletCollection(
                data = data,
                onDone = { collected ->
                    if (collected.isEmpty()) {
                        ui.warn("Select at least 1 center lanelet.", SmoothCenter.TITLE)
                        return@showLaneletCollection
                    }
                    SmoothCenter.applyBatch(data, layer, collected, ui = ui)
                },
                title = "Smooth Center Lanelet - Select CENTER",
                message = "Select center lanelets. Add / Select (S) / Remove (X) / Done.",
                minCount = 1,
                helpTitle = SmoothCenter.TITLE,
                helpText = HELP_TEXT,
                helpLinks = listOf("LaneletAndAreaTagging" to "LaneletAndAreaTagging.md"),
                ui = ui,
            )
            return
        }
        val collected = LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected)
        if (collected.isEmpty()) {
            ui.warn("Select at least 1 center lanelet.", SmoothCenter.TITLE)
            return
        }
        SmoothCenter.applyBatch(data, layer, collected, ui = ui)
    }
}

/**
 * Smooth from the current selection. Two modes matching the Jython:
 * 1) one way + nodes: constrain that border;
 * 2) otherwise: infer center lanelets and smooth both borders.
 */
object SmoothCenterFromCenter {
    const val TITLE = "Smooth Center from Center"

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer() ?: return
        val data = layer.data
        val sel = data.selected.toList()
        val ways = sel.mapNotNull { it as? Way }
        val nodes = sel.mapNotNull { it as? Node }

        if (ways.size == 1 && nodes.isNotEmpty()) {
            val selectedWay = ways[0]
            val centerLls = LaneletSelection.findLaneletsContainingLinestrings(data, listOf(selectedWay))
            if (centerLls.isEmpty() || centerLls.size > 1) {
                // Latent Jython precedence: empty selection yields only "Found 0 lanelets."
                val msg = if (centerLls.isNotEmpty()) {
                    "The selected way must be the left or right border of exactly one center lanelet.\n" +
                        "Found ${centerLls.size} lanelet(s)."
                } else {
                    "Found 0 lanelets."
                }
                ui.warn(msg, TITLE)
                return
            }
            val centerLl = centerLls[0]
            val (leftWay, rightWay) = Lanelet.extractLeftRightWays(centerLl)
            if (leftWay == null || rightWay == null) {
                ui.warn("Center lanelet has no left/right ways.", TITLE)
                return
            }
            val wid = selectedWay.uniqueId
            val whichBorder = when {
                leftWay.uniqueId == wid -> "left"
                rightWay.uniqueId == wid -> "right"
                else -> {
                    ui.warn("Selected way is not the left or right border of the center lanelet.", TITLE)
                    return
                }
            }
            val (entryLl, exitLl) = SmoothCenter.findEntryExitForCenter(centerLl, data)
            if (entryLl == null || exitLl == null) {
                ui.warn(
                    "Could not find entry and exit lanelets for this center.\n\n" +
                        "Entry and exit must share boundary nodes with the center.\n\n" +
                        "Check whether the routing graph is connected between your selection and the " +
                        "intended entry and exit lanelets, and that the directions of the routing graph " +
                        "arrows look correct.",
                    TITLE,
                )
                return
            }
            SmoothCenter.applyOneBorder(data, layer, entryLl, centerLl, exitLl, whichBorder, nodes, ui = ui)
            val usedWays = SmoothCenter.waysFromLanelets(entryLl, centerLl, exitLl)
            if (usedWays.isNotEmpty()) data.setSelected(ArrayList(usedWays))
            return
        }

        val collected = LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected)
        if (collected.isEmpty()) return

        val usedWays = LinkedHashSet<Way>()
        for (centerLl in collected) {
            val (entryLl, exitLl) = SmoothCenter.findEntryExitForCenter(centerLl, data)
            if (entryLl != null && exitLl != null) {
                SmoothCenter.applyBoth(data, layer, entryLl, centerLl, exitLl, ui = ui)
                usedWays.addAll(SmoothCenter.waysFromLanelets(entryLl, centerLl, exitLl))
            }
        }
        if (usedWays.isNotEmpty()) {
            data.setSelected(ArrayList(usedWays))
        } else if (collected.isNotEmpty()) {
            ui.warn(
                "Could not find entry and exit lanelets for the selected center(s).\n\n" +
                    "Entry and exit must share boundary nodes with each center.\n\n" +
                    "Check whether the routing graph is connected between your selection and the " +
                    "intended entry and exit lanelets, and that the directions of the routing graph " +
                    "arrows look correct.",
                TITLE,
            )
        }
    }
}
