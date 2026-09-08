package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList
import kotlin.math.sqrt

/**
 * Merge the closest pair of borders of two lanelets that were drawn twice.
 *
 * One SequenceCommand: rewrite the relation that referenced the discarded way,
 * delete that way, then delete nodes that have no remaining referrers.
 * Port of `merge_shared_border_lanelets.py`.
 *
 * With the collection-dialog setting, the action collects exactly two lanelets
 * interactively; otherwise it expects them (or their borders) in the current
 * selection.
 */
object MergeSharedBorder {
    const val TITLE = "Merge Shared Border"
    const val SEQUENCE_NAME = "Merge shared border lanelets"
    const val HELP_TEXT = """Merge Shared Border

Select exactly two lanelets whose closest pair of borders should become one way.
The discarded way is deleted after its relation is rewritten."""

    data class BorderPair(
        val way1: Way,
        val role1: String,
        val way2: Way,
        val role2: String,
        val distance: Double,
    )

    /** (lat, lon) like the Jython `way_points`. Distance is invariant to axis swap. */
    fun wayPoints(way: Way): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (n in way.nodes) {
            val c = n.coor ?: continue
            out.add(c.lat() to c.lon())
        }
        return out
    }

    fun distPointToSegmentSq(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val px = p.first
        val py = p.second
        val ax = a.first
        val ay = a.second
        val bx = b.first
        val by = b.second
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val ab2 = abx * abx + aby * aby
        if (ab2 < 1e-20) return apx * apx + apy * apy
        var t = (apx * abx + apy * aby) / ab2
        t = t.coerceIn(0.0, 1.0)
        val qx = ax + t * abx
        val qy = ay + t * aby
        val dx = px - qx
        val dy = py - qy
        return dx * dx + dy * dy
    }

    fun wayPairDistance(wayA: Way, wayB: Way): Double {
        val ptsA = wayPoints(wayA)
        val ptsB = wayPoints(wayB)
        if (ptsA.isEmpty() || ptsB.isEmpty()) return 1e30
        var total = 0.0
        for (p in ptsA) {
            var d2Min = 1e30
            for (i in 0 until ptsB.size - 1) {
                val d2 = distPointToSegmentSq(p, ptsB[i], ptsB[i + 1])
                if (d2 < d2Min) d2Min = d2
            }
            if (ptsB.size == 1) {
                val dx = p.first - ptsB[0].first
                val dy = p.second - ptsB[0].second
                d2Min = dx * dx + dy * dy
            }
            total += sqrt(d2Min)
        }
        return total / ptsA.size
    }

    fun findClosestBorderPair(ll1: Relation, ll2: Relation): BorderPair? {
        val (l1Left, l1Right) = Lanelet.extractLeftRightWays(ll1)
        val (l2Left, l2Right) = Lanelet.extractLeftRightWays(ll2)
        if (l1Left == null || l1Right == null || l2Left == null || l2Right == null) return null
        val candidates = listOf(
            Quad(l1Left, "left", l2Left, "left"),
            Quad(l1Left, "left", l2Right, "right"),
            Quad(l1Right, "right", l2Left, "left"),
            Quad(l1Right, "right", l2Right, "right"),
        )
        var best: BorderPair? = null
        var bestDist = 1e30
        for (c in candidates) {
            if (c.w1 === c.w2) continue
            val d1 = wayPairDistance(c.w1, c.w2)
            val d2 = wayPairDistance(c.w2, c.w1)
            val d = (d1 + d2) / 2.0
            if (d < bestDist) {
                bestDist = d
                best = BorderPair(c.w1, c.r1, c.w2, c.r2, d)
            }
        }
        return best
    }

    fun commandsFor(data: DataSet, ll1: Relation, ll2: Relation): MergePlan {
        val result = findClosestBorderPair(ll1, ll2) ?: return MergePlan.MissingBounds
        if (result.way1 === result.way2) return MergePlan.AlreadyShared
        val keepWay = if (result.way1.nodesCount >= result.way2.nodesCount) result.way1 else result.way2
        val deleteWay = if (keepWay === result.way1) result.way2 else result.way1
        val updateLl = if (ll1.members.any { it.member === deleteWay }) ll1 else ll2

        val commands = ArrayList<Command>()
        val newRel = Relation(updateLl)
        newRel.removeMembersFor(deleteWay)
        var roleToRestore: String? = null
        for (m in updateLl.members) {
            if (m.member === deleteWay) {
                roleToRestore = m.role
                break
            }
        }
        // Latent: restores at index 0 if role is "left", else 1 — not the original
        // member index, and not "right" specifically.
        if (roleToRestore != null) {
            val idx = if (roleToRestore == "left") 0 else 1
            newRel.addMember(idx, RelationMember(roleToRestore, keepWay))
        }
        commands.add(ChangeCommand(updateLl, newRel))
        val deleteNodes = ArrayList(deleteWay.nodes)
        commands.add(DeleteCommand(data, deleteWay))
        for (n in deleteNodes) {
            try {
                val refs = n.referrers
                val otherRefs = refs.filter { it !== deleteWay }
                if (otherRefs.isEmpty()) {
                    commands.add(DeleteCommand(data, n))
                }
            } catch (_: Exception) {
            }
        }
        return MergePlan.Ready(commands, keepWay, updateLl)
    }

    fun apply(
        data: DataSet,
        ll1: Relation,
        ll2: Relation,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): MergePlan {
        val plan = commandsFor(data, ll1, ll2)
        if (plan is MergePlan.Ready) {
            applySequence(SEQUENCE_NAME, plan.commands, layer, undo)
            requestRoutingRefresh()
        }
        return plan
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        if (org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic.shouldOpenCollectionDialog()) {
            collectTwo(data, ui) { a, b -> finishMerge(data, a, b, layer, ui) }
            return
        }
        val collected = LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected)
        if (collected.size != 2) {
            ui.warn("Select exactly 2 lanelets to merge. Use X to remove extras.", TITLE)
            return
        }
        finishMerge(data, collected[0], collected[1], layer, ui)
    }

    private fun collectTwo(
        data: org.openstreetmap.josm.data.osm.DataSet,
        ui: UserPrompts,
        initial: List<org.openstreetmap.josm.data.osm.Relation> = emptyList(),
        onPair: (org.openstreetmap.josm.data.osm.Relation, org.openstreetmap.josm.data.osm.Relation) -> Unit,
    ) {
        org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog.showLaneletCollection(
            data = data,
            onDone = { collected ->
                if (collected.size != 2) {
                    ui.warn("Select exactly 2 lanelets to merge. Use X to remove extras.", TITLE)
                    collectTwo(data, ui, collected, onPair)
                    return@showLaneletCollection
                }
                onPair(collected[0], collected[1])
            },
            title = "Merge Shared Border - Select 2 Lanelets",
            message = "Select 2 lanelets whose shared border should be merged. Add / Select (S) / Remove (X) / Done.",
            minCount = 2,
            initialCollected = initial,
            helpTitle = TITLE,
            helpText = HELP_TEXT,
            helpLinks = listOf("LaneletAndAreaTagging" to "LaneletAndAreaTagging.md"),
            ui = ui,
        )
    }

    private fun finishMerge(
        data: org.openstreetmap.josm.data.osm.DataSet,
        ll1: org.openstreetmap.josm.data.osm.Relation,
        ll2: org.openstreetmap.josm.data.osm.Relation,
        layer: org.openstreetmap.josm.gui.layer.OsmDataLayer,
        ui: UserPrompts,
    ) {
        when (val plan = apply(data, ll1, ll2, layer)) {
            MergePlan.MissingBounds ->
                ui.warn("Could not find left/right ways for both lanelets.", TITLE)
            MergePlan.AlreadyShared ->
                ui.info("The two lanelets already share a border (same way).", TITLE)
            is MergePlan.Ready ->
                ui.infoAutoClose(
                    "Merged borders. Kept way ${plan.keepWay.uniqueId}, deleted duplicate. Updated lanelet ${plan.updateLl.uniqueId}.",
                    TITLE,
                    1000,
                )
        }
    }

    sealed class MergePlan {
        data object MissingBounds : MergePlan()
        data object AlreadyShared : MergePlan()
        data class Ready(
            val commands: List<Command>,
            val keepWay: Way,
            val updateLl: Relation,
        ) : MergePlan()
    }

    private data class Quad(val w1: Way, val r1: String, val w2: Way, val r2: String)
}
