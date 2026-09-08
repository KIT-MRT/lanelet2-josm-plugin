package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletFactory
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.infra.WayView
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletCreateTags
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.tools.Logging
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.ArrayList
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Create lanelet relations from selected border ways.
 *
 * Undo granularity matches the Jython: each left/right pair is one undo command
 * ([org.openstreetmap.josm.command.AddCommand] for a new relation, or one
 * [ChangeCommand] to complete/retag an existing one). A batch of N pairs is
 * therefore N undo entries, not one [org.openstreetmap.josm.command.SequenceCommand].
 *
 * Driving direction for successor/predecessor search comes from [Lanelet]
 * geometric [WayView] alignment, not from [Lanelet.invert]. Selection order is
 * western left-to-right (first way = left, second = right) and is **not**
 * geometrically realigned at create time.
 *
 * Port of `create_lanelet_relation.py`.
 */
object CreateLaneletRelation {
    const val TITLE = "Create Lanelet"
    const val TITLE_WARNINGS = "Create Lanelet - Warnings"
    const val TITLE_BATCH = "Create Lanelet - Batch"
    const val TITLE_CHAIN = "Create Lanelet - Chain"

    const val OUTCOME_PARTIAL = "partial"
    const val OUTCOME_EXISTING_SAME = "existing_same"
    const val OUTCOME_EXISTING_REVERTED = "existing_reverted"
    const val OUTCOME_CREATED = "created"

    /**
     * When non-null, overrides the chain-dialog checkbox. Tests set this; the
     * live dialog writes through the checkbox when present. Default (both null)
     * is undirected, matching the Jython.
     */
    var useDirectedSuccessorOverride: Boolean? = null

    internal val chainIds = ArrayList<Long>()

    private var directedSuccessorCb: JCheckBox? = null
    private var chainDialog: JDialog? = null
    private var chainCountLabel: JLabel? = null
    private var chainStatusLabel: JLabel? = null

    data class WayCandidate(val way: Way, val startsAtNode: Boolean)

    data class PairResult(
        val outcome: String,
        val rel: Relation,
        val role: String? = null,
    )

    data class ExistingHit(val kind: String, val rel: Relation)

    data class PartialHit(val rel: Relation, val roleToAdd: String, val wayToAdd: Way)

    fun useDirectedSuccessor(): Boolean {
        val cb = directedSuccessorCb
        if (cb != null) {
            try {
                return cb.isSelected
            } catch (_: Exception) {
            }
        }
        return useDirectedSuccessorOverride ?: false
    }

    fun appendToChain(relId: Long) {
        chainIds.add(relId)
    }

    fun loadChainIds(): List<Long> = chainIds.toList()

    fun clearChain() {
        chainIds.clear()
    }

    fun nodeCoords(node: Node?): Pair<Double, Double>? {
        if (node == null) return null
        val c = node.coor ?: return null
        return Pair(c.lon(), c.lat())
    }

    fun segmentDirection(nodeA: Node?, nodeB: Node?): Pair<Double, Double>? {
        val pa = nodeCoords(nodeA) ?: return null
        val pb = nodeCoords(nodeB) ?: return null
        val dx = pb.first - pa.first
        val dy = pb.second - pa.second
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 1e-20) return null
        val length = sqrt(lengthSq)
        return Pair(dx / length, dy / length)
    }

    fun directionDot(d1: Pair<Double, Double>?, d2: Pair<Double, Double>?): Double {
        if (d1 == null || d2 == null) return -2.0
        return d1.first * d2.first + d1.second * d2.second
    }

    /**
     * When angle is 90-180 deg: check if the candidate segment can be projected
     * onto the wayview. Returns true if we should REJECT (fold-back).
     *
     * Reversed case (same segment as the wayview): return false (allow).
     */
    fun candidateProjectsOntoWayview(
        wayView: WayView,
        way: Way,
        startsAtNode: Boolean,
        atWayviewEnd: Boolean = true,
    ): Boolean {
        val ndsWv = wayView.getNodes()
        val ndsCand = way.nodes
        if (ndsWv.size < 2 || ndsCand.size < 2) return false
        val shared: Node
        val segA: Node
        val farNd: Node
        if (atWayviewEnd) {
            shared = ndsWv.last()
            segA = ndsWv[ndsWv.size - 2]
            farNd = if (startsAtNode) ndsCand[1] else ndsCand[ndsCand.size - 2]
        } else {
            shared = ndsWv.first()
            segA = ndsWv[1]
            farNd = if (startsAtNode) ndsCand[1] else ndsCand[ndsCand.size - 2]
        }
        if (farNd.uniqueId == segA.uniqueId) return false
        val pa = nodeCoords(segA) ?: return false
        val ps = nodeCoords(shared) ?: return false
        val pn = nodeCoords(farNd) ?: return false
        val ax = pa.first - ps.first
        val ay = pa.second - ps.second
        val lenASq = ax * ax + ay * ay
        if (lenASq < 1e-20) return false
        val t = (ax * (pn.first - ps.first) + ay * (pn.second - ps.second)) / lenASq
        return t > 0.0 && t < 1.0
    }

    fun getLastSegmentDirection(wayView: WayView?): Pair<Double, Double>? {
        if (wayView == null) return null
        val nds = wayView.getNodes()
        if (nds.size < 2) return null
        return segmentDirection(nds[nds.size - 2], nds.last())
    }

    fun getFirstSegmentDirection(wayView: WayView?): Pair<Double, Double>? {
        if (wayView == null) return null
        val nds = wayView.getNodes()
        if (nds.size < 2) return null
        return segmentDirection(nds[0], nds[1])
    }

    fun getCandidateDirection(way: Way, startsAtNode: Boolean): Pair<Double, Double>? {
        val nds = way.nodes
        if (nds.size < 2) return null
        return if (startsAtNode) {
            segmentDirection(nds[0], nds[1])
        } else {
            segmentDirection(nds[nds.size - 2], nds.last())
        }
    }

    fun findAllWayCandidatesAtNode(data: DataSet, node: Node?, excludeWays: Collection<Way?>): List<WayCandidate> {
        if (node == null) return emptyList()
        val nid = try {
            node.uniqueId
        } catch (_: Exception) {
            return emptyList()
        }
        val excludeIds = HashSet<Long>()
        for (w in excludeWays) {
            if (w == null) continue
            try {
                excludeIds.add(w.uniqueId)
            } catch (_: Exception) {
            }
        }
        val candidates = ArrayList<WayCandidate>()
        for (way in data.ways) {
            if (way == null || way.uniqueId in excludeIds) continue
            val nds = way.nodes
            if (nds.size < 2) continue
            if (nds[0].uniqueId == nid) {
                candidates.add(WayCandidate(way, true))
            } else if (nds.last().uniqueId == nid) {
                candidates.add(WayCandidate(way, false))
            }
        }
        return candidates
    }

    fun pickBestSuccessor(
        wayView: WayView?,
        candidates: List<WayCandidate>,
        useDirected: Boolean,
        predecessor: Boolean = false,
    ): Way? {
        val refDir = if (predecessor) getFirstSegmentDirection(wayView) else getLastSegmentDirection(wayView)
        if (refDir == null) return candidates.firstOrNull()?.way
        if (wayView == null) return candidates.firstOrNull()?.way
        val atEnd = !predecessor
        var bestWay: Way? = null
        var bestScore = -2.0
        for (cand in candidates) {
            val candDir = getCandidateDirection(cand.way, cand.startsAtNode) ?: continue
            val dot = directionDot(refDir, candDir)
            val score = if (useDirected) {
                dot
            } else {
                // Latent: `dot >= -1.0` is always true for unit vectors; kept to match Jython.
                var s = abs(dot)
                if (dot >= -1.0 && dot < 0.0) {
                    if (candidateProjectsOntoWayview(wayView, cand.way, cand.startsAtNode, atWayviewEnd = atEnd)) {
                        s = -2.0
                    }
                }
                s
            }
            if (score > bestScore) {
                bestScore = score
                bestWay = cand.way
            }
        }
        return bestWay
    }

    /**
     * Successor ways at the downstream nodes of [lanelet] (driving direction from
     * geometric [WayView] alignment). When left and right pick the same way, the
     * right side retries excluding that way.
     */
    fun findSuccessorWays(data: DataSet, lanelet: Lanelet, useDirected: Boolean = useDirectedSuccessor()): Pair<Way?, Way?> {
        val (leftEnd, rightEnd) = lanelet.getLastNodesInDrivingDirection()
        if (leftEnd == null || rightEnd == null) return Pair(null, null)
        val leftView = lanelet.leftBound()
        val rightView = lanelet.rightBound()
        val leftW = leftView?.way
        val rightW = rightView?.way
        val exclude = listOfNotNull(leftW, rightW)
        val leftCands = findAllWayCandidatesAtNode(data, leftEnd, exclude)
        val rightCands = findAllWayCandidatesAtNode(data, rightEnd, exclude)
        var leftSucc = if (leftCands.isNotEmpty()) {
            pickBestSuccessor(leftView, leftCands, useDirected, predecessor = false)
        } else {
            null
        }
        var rightSucc = if (rightCands.isNotEmpty()) {
            pickBestSuccessor(rightView, rightCands, useDirected, predecessor = false)
        } else {
            null
        }
        if (leftSucc != null && rightSucc != null && leftSucc.uniqueId == rightSucc.uniqueId) {
            val rightCandsExcl = rightCands.filter { it.way.uniqueId != leftSucc!!.uniqueId }
            rightSucc = if (rightCandsExcl.isNotEmpty()) {
                pickBestSuccessor(rightView, rightCandsExcl, useDirected, predecessor = false)
            } else {
                null
            }
        }
        return Pair(leftSucc, rightSucc)
    }

    fun findPredecessorWays(data: DataSet, lanelet: Lanelet, useDirected: Boolean = false): Pair<Way?, Way?> {
        val (leftFirst, rightFirst) = lanelet.getFirstNodesInDrivingDirection()
        if (leftFirst == null || rightFirst == null) return Pair(null, null)
        val leftView = lanelet.leftBound()
        val rightView = lanelet.rightBound()
        val leftW = leftView?.way
        val rightW = rightView?.way
        val exclude = listOfNotNull(leftW, rightW)
        val leftCands = findAllWayCandidatesAtNode(data, leftFirst, exclude)
        val rightCands = findAllWayCandidatesAtNode(data, rightFirst, exclude)
        var leftPred = if (leftCands.isNotEmpty()) {
            pickBestSuccessor(leftView, leftCands, useDirected, predecessor = true)
        } else {
            null
        }
        var rightPred = if (rightCands.isNotEmpty()) {
            pickBestSuccessor(rightView, rightCands, useDirected, predecessor = true)
        } else {
            null
        }
        if (leftPred != null && rightPred != null && leftPred.uniqueId == rightPred.uniqueId) {
            val rightCandsExcl = rightCands.filter { it.way.uniqueId != leftPred!!.uniqueId }
            rightPred = if (rightCandsExcl.isNotEmpty()) {
                pickBestSuccessor(rightView, rightCandsExcl, useDirected, predecessor = true)
            } else {
                null
            }
        }
        return Pair(leftPred, rightPred)
    }

    fun getLeftRightWaysLists(rel: Relation?): Pair<List<Way>, List<Way>> {
        val leftList = ArrayList<Way>()
        val rightList = ArrayList<Way>()
        if (rel == null) return Pair(leftList, rightList)
        for (m in rel.members) {
            val role = m.role
            val mem = m.member ?: continue
            if (mem !is Way) continue
            when (role) {
                "left" -> leftList.add(mem)
                "right" -> rightList.add(mem)
            }
        }
        return Pair(leftList, rightList)
    }

    fun addMultiMemberWarning(
        warnings: MutableList<String>,
        seenRelIds: MutableSet<Long>,
        rel: Relation,
        leftCount: Int,
        rightCount: Int,
    ) {
        val rid = rel.uniqueId
        if (rid in seenRelIds) return
        seenRelIds.add(rid)
        val parts = ArrayList<String>()
        if (leftCount > 1) parts.add("$leftCount left")
        if (rightCount > 1) parts.add("$rightCount right")
        if (parts.isNotEmpty()) {
            warnings.add("Lanelet relation $rid has multiple border members: ${parts.joinToString(", ")}")
        }
    }

    /**
     * Type compared to `"lanelet"` exactly (not case-folded), matching the Jython.
     */
    fun findExistingLanelet(
        data: DataSet,
        leftWay: Way?,
        rightWay: Way?,
        warnings: MutableList<String>? = null,
        seenRelIds: MutableSet<Long>? = null,
    ): ExistingHit? {
        val leftId = leftWay?.uniqueId ?: return null
        val rightId = rightWay?.uniqueId ?: return null
        val seen = seenRelIds ?: HashSet()
        for (rel in data.relations) {
            if (rel == null || rel.get("type") != "lanelet") continue
            val (leftList, rightList) = getLeftRightWaysLists(rel)
            if (warnings != null) {
                addMultiMemberWarning(warnings, seen, rel, leftList.size, rightList.size)
            }
            val relLeft = leftList.firstOrNull() ?: continue
            val relRight = rightList.firstOrNull() ?: continue
            val rlId = relLeft.uniqueId
            val rrId = relRight.uniqueId
            if (leftId == rlId && rightId == rrId) return ExistingHit("same", rel)
            if (leftId == rrId && rightId == rlId) return ExistingHit("swapped", rel)
        }
        return null
    }

    fun findPartialLaneletToComplete(
        data: DataSet,
        leftWay: Way?,
        rightWay: Way?,
        warnings: MutableList<String>,
        seenRelIds: MutableSet<Long>,
    ): PartialHit? {
        val leftId = leftWay?.uniqueId ?: return null
        val rightId = rightWay?.uniqueId ?: return null
        for (rel in data.relations) {
            if (rel == null || rel.get("type") != "lanelet") continue
            val (leftList, rightList) = getLeftRightWaysLists(rel)
            addMultiMemberWarning(warnings, seenRelIds, rel, leftList.size, rightList.size)
            val hasLeft = leftList.isNotEmpty()
            val hasRight = rightList.isNotEmpty()
            if (hasLeft && hasRight) continue
            if (!hasLeft && !hasRight) continue
            if (hasLeft && !hasRight) {
                val roleToAdd = "right"
                for (w in leftList) {
                    if (w.uniqueId == leftId) return PartialHit(rel, roleToAdd, rightWay)
                    if (w.uniqueId == rightId) return PartialHit(rel, roleToAdd, leftWay)
                }
            } else {
                val roleToAdd = "left"
                for (w in rightList) {
                    if (w.uniqueId == leftId) return PartialHit(rel, roleToAdd, rightWay)
                    if (w.uniqueId == rightId) return PartialHit(rel, roleToAdd, leftWay)
                }
            }
        }
        return null
    }

    fun applyCreateTagsToRelationCopy(newRel: Relation, tags: LaneletCreateTags) {
        newRel.put("subtype", tags.subtype)
        val loc = tags.location
        if (loc != null) {
            newRel.put("location", loc)
        } else {
            try {
                newRel.remove("location")
            } catch (_: Exception) {
            }
        }
        val ow = tags.oneWay
        if (ow != null) {
            newRel.put("one_way", ow)
        } else {
            try {
                newRel.remove("one_way")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * If OSM left/right ways are the selection pair but swapped, swap roles to
     * match (first selection = left). Uses OSM member roles, not geometric
     * [Lanelet] alignment.
     */
    fun normalizeDirectionToSelection(newRel: Relation, leftWay: Way?, rightWay: Way?): Relation {
        val (osmLeft, osmRight) = Lanelet.extractLeftRightWays(newRel)
        if (osmLeft == null || osmRight == null || leftWay == null || rightWay == null) return newRel
        val lid = leftWay.uniqueId
        val rid = rightWay.uniqueId
        val olid = osmLeft.uniqueId
        val orid = osmRight.uniqueId
        if (olid == lid && orid == rid) return newRel
        if (olid == rid && orid == lid) return RevertLaneletDirection.revertCopy(newRel)
        return newRel
    }

    fun executePairCreateFlow(
        data: DataSet,
        layer: OsmDataLayer?,
        leftWay: Way,
        rightWay: Way,
        warnings: MutableList<String>,
        seenRelIds: MutableSet<Long>,
        tags: LaneletCreateTags,
        interactiveWarn: Boolean,
        ui: UserPrompts,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): PairResult {
        val partial = findPartialLaneletToComplete(data, leftWay, rightWay, warnings, seenRelIds)
        if (partial != null) {
            val newRel = Relation(partial.rel)
            newRel.addMember(RelationMember(partial.roleToAdd, partial.wayToAdd))
            val normalized = normalizeDirectionToSelection(newRel, leftWay, rightWay)
            applyCreateTagsToRelationCopy(normalized, tags)
            undo.add(ChangeCommand(partial.rel, normalized))
            invalidateAndRepaint(layer)
            return PairResult(OUTCOME_PARTIAL, normalized, partial.roleToAdd)
        }

        val existing = findExistingLanelet(data, leftWay, rightWay, warnings, seenRelIds)
        if (existing != null) {
            if (existing.kind == "same") {
                val newRel = Relation(existing.rel)
                applyCreateTagsToRelationCopy(newRel, tags)
                undo.add(ChangeCommand(existing.rel, newRel))
                invalidateAndRepaint(layer)
                return PairResult(OUTCOME_EXISTING_SAME, newRel)
            }
            val newRel = RevertLaneletDirection.revertCopy(existing.rel)
            applyCreateTagsToRelationCopy(newRel, tags)
            undo.add(ChangeCommand(existing.rel, newRel))
            invalidateAndRepaint(layer)
            return PairResult(OUTCOME_EXISTING_REVERTED, newRel)
        }

        if (interactiveWarn && warnings.isNotEmpty()) {
            ui.warn(warnings.joinToString("\n"), TITLE_WARNINGS)
        }

        val lanelet = LaneletFactory.create(
            data,
            layer,
            leftWay,
            rightWay,
            undo,
            tags.subtype,
            tags.location,
            tags.oneWay,
        )
        val rel = lanelet.relation
        Logging.info("Created lanelet relation: {0}", rel.uniqueId)
        invalidateAndRepaint(layer)
        return PairResult(OUTCOME_CREATED, rel)
    }

    fun wayViewForStripBorder(borderWays: List<Way>, relsPerLanelet: List<Relation>, k: Int): WayView? {
        val nLanelets = relsPerLanelet.size
        if (borderWays.isEmpty() || k < 0 || k >= borderWays.size || nLanelets != borderWays.size - 1) {
            return null
        }
        val w = borderWays[k]
        val wid = try {
            w.uniqueId
        } catch (_: Exception) {
            return null
        }
        if (k == 0) {
            val ll = Lanelet(relsPerLanelet[0])
            val v = ll.leftBound()
            if (v != null && v.way != null && v.way.uniqueId == wid) return v
            return null
        }
        if (k == nLanelets) {
            val ll = Lanelet(relsPerLanelet[nLanelets - 1])
            val v = ll.rightBound()
            if (v != null && v.way != null && v.way.uniqueId == wid) return v
            return null
        }
        val llPrev = Lanelet(relsPerLanelet[k - 1])
        val vPrev = llPrev.rightBound()
        if (vPrev != null && vPrev.way != null && vPrev.way.uniqueId == wid) return vPrev
        val llNext = Lanelet(relsPerLanelet[k])
        val vNext = llNext.leftBound()
        if (vNext != null && vNext.way != null && vNext.way.uniqueId == wid) return vNext
        return null
    }

    fun findStripSuccessorWays(
        data: DataSet,
        borderWays: List<Way>,
        relsPerLanelet: List<Relation>,
        useDirected: Boolean = useDirectedSuccessor(),
    ): List<Way?> {
        val nB = borderWays.size
        if (nB < 2 || relsPerLanelet.size != nB - 1) return List(nB) { null }
        val succ = ArrayList<Way?>(nB)
        for (k in 0 until nB) {
            val wayView = wayViewForStripBorder(borderWays, relsPerLanelet, k)
            if (wayView == null) {
                succ.add(null)
                continue
            }
            val lastNode = wayView.getLastNode()
            if (lastNode == null) {
                succ.add(null)
                continue
            }
            val exclude = listOf(borderWays[k])
            val cands = findAllWayCandidatesAtNode(data, lastNode, exclude)
            val s = if (cands.isNotEmpty()) {
                pickBestSuccessor(wayView, cands, useDirected, predecessor = false)
            } else {
                null
            }
            succ.add(s)
        }
        for (k in 0 until nB - 1) {
            val a = succ[k]
            val b = succ[k + 1]
            if (a != null && b != null && a.uniqueId == b.uniqueId) {
                val wayView = wayViewForStripBorder(borderWays, relsPerLanelet, k + 1) ?: continue
                val lastNode = wayView.getLastNode() ?: continue
                val border = borderWays[k + 1]
                val candsExcl = findAllWayCandidatesAtNode(data, lastNode, listOf(border))
                    .filter { it.way.uniqueId != a.uniqueId }
                succ[k + 1] = if (candsExcl.isNotEmpty()) {
                    pickBestSuccessor(wayView, candsExcl, useDirected, predecessor = false)
                } else {
                    null
                }
            }
        }
        return succ
    }

    fun stripSuccessorsSelectionOnly(succList: List<Way?>?, borderWays: List<Way>): List<Way>? {
        if (borderWays.isEmpty() || succList == null || succList.size != borderWays.size) return null
        return succList.mapNotNull { it }
    }

    fun stripSuccessorStatusSuffix(succList: List<Way?>?, borderWays: List<Way>): String {
        if (succList == null || succList.size != borderWays.size) return ""
        val nMiss = borderWays.indices.count { succList[it] == null }
        if (nMiss == 0) return ""
        return "  $nMiss border(s) had no successor; omitted from selection."
    }

    fun stripChainStatusMessage(outcomes: List<String>, roles: List<String?>): String? {
        val n = outcomes.size
        if (n == 0) return null
        for (i in outcomes.indices) {
            if (outcomes[i] == OUTCOME_PARTIAL) {
                return "Completed lanelet: added ${roles[i] ?: "missing"} border (direction/tags from defaults)."
            }
        }
        if (outcomes.all { it == OUTCOME_EXISTING_SAME }) {
            return "Strip: all $n pair(s) already existed (tags synced)."
        }
        if (outcomes.any { it == OUTCOME_EXISTING_REVERTED }) {
            return "Strip: at least one pair had swapped borders (reverted + defaults applied)."
        }
        val nc = outcomes.count { it == OUTCOME_CREATED }
        if (nc == n) return "Strip: created $nc lanelet(s)."
        if (nc > 0) return "Strip: created $nc of $n lanelet(s)."
        return null
    }

    data class ApplyResult(
        val outcomes: List<PairResult>,
        val nextSelection: List<org.openstreetmap.josm.data.osm.OsmPrimitive>,
        val statusMessage: String?,
        val warnings: List<String>,
        val createdIds: List<Long>,
    )

    fun applySinglePair(
        data: DataSet,
        layer: OsmDataLayer?,
        leftWay: Way,
        rightWay: Way,
        ui: UserPrompts = Dialogs,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        tags: LaneletCreateTags = LaneletSettings.getLaneletCreateTags(),
        showChainDialog: Boolean = false,
    ): ApplyResult {
        val warnings = ArrayList<String>()
        val seenRelIds = HashSet<Long>()
        val result = executePairCreateFlow(
            data, layer, leftWay, rightWay, warnings, seenRelIds, tags, true, ui, undo,
        )
        val lanelet = Lanelet(result.rel)
        val (leftSucc, rightSucc) = findSuccessorWays(data, lanelet)
        val nextWays = listOfNotNull(leftSucc, rightSucc)
        val nextSelection: List<org.openstreetmap.josm.data.osm.OsmPrimitive> = if (nextWays.isNotEmpty()) {
            data.setSelected(ArrayList(nextWays))
            zoomToPrimitives(nextWays)
            nextWays
        } else {
            data.setSelected(result.rel)
            zoomToPrimitives(listOf(result.rel))
            listOf(result.rel)
        }
        invalidateAndRepaint(layer)
        if (warnings.isNotEmpty() && result.outcome != OUTCOME_CREATED) {
            ui.warn(warnings.joinToString("\n"), TITLE_WARNINGS)
        }
        val createdIds = ArrayList<Long>()
        if (result.outcome == OUTCOME_CREATED) {
            try {
                createdIds.add(result.rel.uniqueId)
                appendToChain(result.rel.uniqueId)
            } catch (_: Exception) {
            }
        }
        var statusMsg: String? = when (result.outcome) {
            OUTCOME_PARTIAL ->
                "Completed lanelet: added ${result.role ?: "missing"} border; direction/tags from defaults."
            OUTCOME_EXISTING_SAME -> "Exists already (tags synced from defaults)."
            OUTCOME_EXISTING_REVERTED ->
                "Other direction exists: reverted borders to match selection (defaults applied)."
            else -> null
        }
        val succNotes = ArrayList<String>()
        if (leftSucc != null || rightSucc != null) {
            if (leftSucc == null) succNotes.add("Left border: no successor, omitted from selection.")
            if (rightSucc == null) succNotes.add("Right border: no successor, omitted from selection.")
        }
        if (succNotes.isNotEmpty()) {
            val extra = "  " + succNotes.joinToString("  ")
            statusMsg = if (statusMsg != null) statusMsg + extra else extra.trim()
        }
        if (showChainDialog) showChainDialog(data, statusMsg, ui)
        if (result.outcome in setOf(OUTCOME_PARTIAL, OUTCOME_CREATED, OUTCOME_EXISTING_SAME, OUTCOME_EXISTING_REVERTED)) {
            requestRoutingRefresh()
        }
        return ApplyResult(listOf(result), nextSelection, statusMsg, warnings, createdIds)
    }

    fun applyBatch(
        data: DataSet,
        layer: OsmDataLayer?,
        ways: List<Way>,
        ui: UserPrompts = Dialogs,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        tags: LaneletCreateTags = LaneletSettings.getLaneletCreateTags(),
    ): ApplyResult {
        // Jython 2: `len(ways) / 2` truncates; even counts only reach here.
        val nPairs = ways.size / 2
        val original = ArrayList(ways)
        val warnings = ArrayList<String>()
        val seenRelIds = HashSet<Long>()
        val outcomes = ArrayList<PairResult>()
        val counts = HashMap<String, Int>()
        for (i in ways.indices step 2) {
            val result = executePairCreateFlow(
                data, layer, ways[i], ways[i + 1], warnings, seenRelIds, tags, false, ui, undo,
            )
            outcomes.add(result)
            counts[result.outcome] = (counts[result.outcome] ?: 0) + 1
            invalidateAndRepaint(layer)
        }
        data.setSelected(original)
        invalidateAndRepaint(layer)
        val lines = ArrayList<String>()
        lines.add("Batch: $nPairs pair(s) processed.")
        lines.add("  Created: ${counts[OUTCOME_CREATED] ?: 0}")
        lines.add("  Completed partial: ${counts[OUTCOME_PARTIAL] ?: 0}")
        lines.add("  Already existed (tags synced): ${counts[OUTCOME_EXISTING_SAME] ?: 0}")
        lines.add("  Swapped borders fixed (revert + tags): ${counts[OUTCOME_EXISTING_REVERTED] ?: 0}")
        if (warnings.isNotEmpty()) {
            lines.add("")
            lines.add("Warnings:")
            lines.addAll(warnings)
        }
        val msg = lines.joinToString("\n")
        if (warnings.isNotEmpty()) ui.warn(msg, TITLE_BATCH) else ui.info(msg, TITLE_BATCH)
        requestRoutingRefresh()
        return ApplyResult(outcomes, original, msg, warnings, emptyList())
    }

    fun applyParallelStrip(
        data: DataSet,
        layer: OsmDataLayer?,
        borderWays: List<Way>,
        ui: UserPrompts = Dialogs,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
        tags: LaneletCreateTags = LaneletSettings.getLaneletCreateTags(),
        showChainDialog: Boolean = false,
    ): ApplyResult {
        val nPairs = borderWays.size - 1
        val warnings = ArrayList<String>()
        val seenRelIds = HashSet<Long>()
        val rels = ArrayList<Relation>()
        val outcomes = ArrayList<PairResult>()
        for (i in 0 until nPairs) {
            val result = executePairCreateFlow(
                data, layer, borderWays[i], borderWays[i + 1], warnings, seenRelIds, tags, false, ui, undo,
            )
            rels.add(result.rel)
            outcomes.add(result)
            invalidateAndRepaint(layer)
        }
        if (warnings.isNotEmpty()) {
            ui.warn(warnings.joinToString("\n"), TITLE_WARNINGS)
        }
        val succList = findStripSuccessorWays(data, borderWays, rels)
        val nextWays = stripSuccessorsSelectionOnly(succList, borderWays)
        val hasAnySucc = succList.size == borderWays.size && succList.any { it != null }
        val nextSelection: List<org.openstreetmap.josm.data.osm.OsmPrimitive>
        if (nextWays != null && hasAnySucc) {
            data.setSelected(ArrayList(nextWays))
            zoomToPrimitives(nextWays)
            nextSelection = nextWays
        } else {
            data.setSelected(ArrayList(rels))
            zoomToPrimitives(rels)
            nextSelection = rels
        }
        invalidateAndRepaint(layer)
        val createdIds = ArrayList<Long>()
        for (i in outcomes.indices) {
            if (outcomes[i].outcome == OUTCOME_CREATED) {
                try {
                    createdIds.add(rels[i].uniqueId)
                    appendToChain(rels[i].uniqueId)
                } catch (_: Exception) {
                }
            }
        }
        var statusMsg = stripChainStatusMessage(outcomes.map { it.outcome }, outcomes.map { it.role })
        if (succList.size == borderWays.size) {
            val sfx = stripSuccessorStatusSuffix(succList, borderWays)
            if (sfx.isNotEmpty()) {
                statusMsg = if (statusMsg != null) statusMsg + sfx else sfx.trim()
            }
        }
        if (showChainDialog) showChainDialog(data, statusMsg, ui)
        if (outcomes.any {
                it.outcome in setOf(OUTCOME_PARTIAL, OUTCOME_CREATED, OUTCOME_EXISTING_SAME, OUTCOME_EXISTING_REVERTED)
            }
        ) {
            requestRoutingRefresh()
        }
        return ApplyResult(outcomes, nextSelection, statusMsg, warnings, createdIds)
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val ways = data.selected.mapNotNull { it as? Way }
        if (ways.size < 2) {
            ui.warn(
                "Select at least 2 linestrings (ways).\n\n" +
                    "  Two ways: left border, then right border (chain mode: selection advances downstream).\n" +
                    "  Three or more ways: if default subtype is crosswalk (Lanelet2 Settings), batch mode\n" +
                    "    needs an even count L,R,L,R,... If default subtype is anything else, parallel strip\n" +
                    "    + chain: outer left, shared borders, outer right (N+1 ways, N lanelets).\n\n" +
                    "Selection order matters (western left-to-right reading direction).",
                TITLE,
            )
            return
        }
        val showDialog = !Dialogs.isHeadless()
        if (ways.size > 2) {
            val subtype = LaneletSettings.getLaneletDefaultSubtype()
            if (subtype == "crosswalk") {
                if (ways.size % 2 != 0) {
                    ui.warn(
                        "Default subtype is crosswalk: batch mode needs an even number of ways\n" +
                            "(left, right, left, right, ...). You selected ${ways.size} way(s).\n\n" +
                            "Change the default subtype in Lanelet2 Settings to use parallel strip + chain.",
                        TITLE,
                    )
                    return
                }
                applyBatch(data, layer, ways, ui)
                return
            }
            applyParallelStrip(data, layer, ways, ui, showChainDialog = showDialog)
            return
        }
        applySinglePair(data, layer, ways[0], ways[1], ui, showChainDialog = showDialog)
    }

    internal fun showChainDialog(data: DataSet, statusMessage: String?, ui: UserPrompts) {
        if (Dialogs.isHeadless()) return
        val ids = loadChainIds()
        val countText = "Chain: ${ids.size} lanelet(s) created"
        val statusText = statusMessage ?: ""
        val existing = chainDialog
        if (existing != null && existing.isVisible) {
            chainCountLabel?.text = countText
            chainStatusLabel?.text = statusText
            existing.toFront()
            return
        }
        val parent = Dialogs.parent()
        val dlg = JDialog(parent as? java.awt.Frame, TITLE_CHAIN, false)
        dlg.layout = BorderLayout()

        fun onDialogClosed() {
            clearChain()
            directedSuccessorCb = null
            chainDialog = null
            chainCountLabel = null
            chainStatusLabel = null
        }

        dlg.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) = onDialogClosed()
        })

        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        val countLbl = JLabel(countText)
        north.add(countLbl)
        val statusLbl = JLabel(statusText)
        north.add(statusLbl)
        val chkDirected = JCheckBox("Prefer directed successor (same direction only)", false)
        chkDirected.toolTipText =
            "Unchecked (default): 0 and 180 deg both good. Checked: only 0 deg (same direction) preferred."
        north.add(chkDirected)
        dlg.add(north, BorderLayout.NORTH)
        directedSuccessorCb = chkDirected

        val btnPanel = JPanel(FlowLayout())
        val btnSelect = JButton("Select all")
        btnSelect.toolTipText = "Select all lanelets in the chain for tag editing"
        btnSelect.addActionListener {
            val chain = loadChainIds()
            if (chain.isEmpty()) {
                ui.infoAutoClose("No lanelets in chain yet.", TITLE, 1000)
                return@addActionListener
            }
            val found = SelectRelations.findRelationsByIds(data, chain, "lanelet", null)
            if (found.isEmpty()) {
                ui.warn("No lanelets found for saved IDs (may have been deleted).", TITLE)
                return@addActionListener
            }
            data.setSelected(ArrayList(found))
            zoomToPrimitives(found)
            invalidateAndRepaint(null)
        }
        val btnClear = JButton("Clear chain")
        btnClear.toolTipText = "Clear the chain (start fresh)"
        btnClear.addActionListener {
            clearChain()
            countLbl.text = "Chain: 0 lanelet(s) created"
            statusLbl.text = ""
            dlg.pack()
        }
        val btnClose = JButton("Close")
        btnClose.addActionListener {
            onDialogClosed()
            dlg.isVisible = false
            dlg.dispose()
        }
        btnPanel.add(btnSelect)
        btnPanel.add(btnClear)
        btnPanel.add(btnClose)
        dlg.add(btnPanel, BorderLayout.CENTER)
        val hint = JLabel(
            "<html><i>Select all</i> selects the chain. Use Properties (Alt+Shift+P) to edit tags. Ctrl+Shift+K to create another lanelet.</html>",
        )
        dlg.add(hint, BorderLayout.SOUTH)

        val rootPane = dlg.rootPane
        val im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rootPane.actionMap
        im.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            "create_lanelet",
        )
        am.put(
            "create_lanelet",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = run(ui)
            },
        )

        dlg.pack()
        positionDialogUpperLeft(dlg, parent, 0.06, 0.15)
        chainDialog = dlg
        chainCountLabel = countLbl
        chainStatusLabel = statusLbl
        dlg.isVisible = true
    }

    /**
     * Jython `position_dialog_upper_left`. Python 2 `int(...)` truncates toward
     * zero; [toInt] matches for the non-negative screen coordinates used here.
     */
    internal fun positionDialogUpperLeft(
        dialog: JDialog,
        parent: java.awt.Component?,
        offsetXFrac: Double,
        offsetYFrac: Double,
    ) {
        if (parent == null) {
            dialog.setLocationRelativeTo(null)
            return
        }
        try {
            val loc = parent.locationOnScreen
            val pw = parent.width
            val ph = parent.height
            val dw = dialog.width
            val dh = dialog.height
            var x = (loc.x + pw * offsetXFrac).toInt()
            var y = (loc.y + ph * offsetYFrac).toInt()
            x = minOf(x, loc.x + pw - dw - 20)
            y = minOf(y, loc.y + ph - dh - 20)
            x = maxOf(x, loc.x + 10)
            y = maxOf(y, loc.y + 10)
            dialog.setLocation(x, y)
        } catch (_: Exception) {
            dialog.setLocationRelativeTo(parent)
        }
    }
}
