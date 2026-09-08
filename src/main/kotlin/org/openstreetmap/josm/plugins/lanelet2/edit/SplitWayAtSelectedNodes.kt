package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.AddCommand
import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Split ways at selected interior nodes.
 *
 * For lanelets: keep the relation and replace the split left/right border with
 * the first segment in **driving direction** (from [Lanelet] geometric
 * [org.openstreetmap.josm.plugins.lanelet2.infra.WayView] alignment, not
 * [Lanelet.invert]). Other segments become orphaned.
 *
 * For other relations: remove the original way; new ways are not added.
 *
 * One [org.openstreetmap.josm.command.SequenceCommand]. Port of
 * `split_way_at_selected_nodes.py`.
 *
 * Latent: [laneletExtraRemovals] are only applied when that relation is also in
 * [laneletReplacements] (a way that is a lanelet member but neither left nor
 * right is ignored unless the same lanelet also had a left/right split).
 */
object SplitWayAtSelectedNodes {
    const val TITLE = "Split Way at Selected Nodes"
    const val SEQUENCE_NAME = "Split way at selected nodes"

    data class SplitPlan(
        val commands: List<Command>,
        val newWays: List<Way>,
        val waysSplit: Int,
        val hasLanelet: Boolean,
    )

    fun splitWayAtNodes(way: Way, splitNodeIds: Set<Long>): List<List<Node>>? {
        val nodes = way.nodes
        if (nodes.size < 2) return null
        val splitIndices = ArrayList<Int>()
        for ((i, n) in nodes.withIndex()) {
            try {
                if (n.uniqueId in splitNodeIds && i > 0 && i < nodes.size - 1) {
                    splitIndices.add(i)
                }
            } catch (_: Exception) {
            }
        }
        if (splitIndices.isEmpty()) return null
        val segments = ArrayList<List<Node>>()
        var start = 0
        for (idx in splitIndices) {
            val seg = nodes.subList(start, idx + 1).toList()
            if (seg.size >= 2) segments.add(seg)
            start = idx
        }
        val tail = nodes.subList(start, nodes.size).toList()
        if (tail.size >= 2) segments.add(tail)
        return if (segments.size >= 2) segments else null
    }

    fun copyWayTags(src: Way, dst: Way) {
        for (key in src.keySet()) {
            val value = src.get(key)
            if (!value.isNullOrEmpty()) dst.put(key, value)
        }
    }

    /**
     * Index of the segment that is first in driving direction for this lanelet.
     * Uses geometric [Lanelet] alignment: if the bound's first node is the OSM
     * way's last node, the last segment is first in driving direction.
     */
    fun firstSegmentIdxInDrivingDirection(way: Way, segments: List<List<Node>>, laneletRel: Relation): Int {
        val (leftW, rightW) = Lanelet.extractLeftRightWays(laneletRel)
        val bound = when {
            way === leftW -> Lanelet(laneletRel).leftBound()
            way === rightW -> Lanelet(laneletRel).rightBound()
            else -> return 0
        }
        val firstNode = bound?.getFirstNode() ?: return 0
        val firstId = try {
            firstNode.uniqueId
        } catch (_: Exception) {
            return 0
        }
        val origNodes = way.nodes
        if (origNodes.isEmpty()) return 0
        return try {
            when {
                origNodes.first().uniqueId == firstId -> 0
                origNodes.last().uniqueId == firstId -> segments.size - 1
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun commandsFor(data: DataSet, selectedNodes: Collection<Node>): SplitPlan? {
        val splitNodeIds = HashSet<Long>()
        for (n in selectedNodes) {
            try {
                splitNodeIds.add(n.uniqueId)
            } catch (_: Exception) {
            }
        }
        if (splitNodeIds.isEmpty()) return null

        val waysToSplit = ArrayList<Way>()
        for (way in data.ways) {
            if (way == null) continue
            val nodes = way.nodes
            for (n in nodes) {
                try {
                    if (n.uniqueId in splitNodeIds) {
                        waysToSplit.add(way)
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (waysToSplit.isEmpty()) return null

        val addCmds = ArrayList<Command>()
        val changeCmds = ArrayList<Command>()
        val deleteCmds = ArrayList<Command>()
        val newWays = ArrayList<Way>()
        var totalNew = 0
        val laneletReplacements = LinkedHashMap<Relation, MutableMap<String, Pair<Way, Way>>>()
        val laneletExtraRemovals = LinkedHashMap<Relation, MutableList<Way>>()
        val nonLaneletRemovals = LinkedHashMap<Relation, MutableList<Way>>()

        for (way in waysToSplit) {
            val segments = splitWayAtNodes(way, splitNodeIds) ?: continue
            val waySegmentWays = ArrayList<Way>()
            for (seg in segments) {
                val newWay = Way()
                copyWayTags(way, newWay)
                newWay.setNodes(ArrayList(seg))
                waySegmentWays.add(newWay)
                addCmds.add(AddCommand(data, newWay))
                newWays.add(newWay)
                totalNew++
            }
            for (ref in way.referrers) {
                if (ref !is Relation) continue
                if (ref.get("type") == "lanelet") {
                    val (leftW, rightW) = Lanelet.extractLeftRightWays(ref)
                    val role = when {
                        way === leftW -> "left"
                        way === rightW -> "right"
                        else -> {
                            laneletExtraRemovals.getOrPut(ref) { ArrayList() }.add(way)
                            continue
                        }
                    }
                    val idx = firstSegmentIdxInDrivingDirection(way, segments, ref)
                    val replacementWay = waySegmentWays[idx]
                    laneletReplacements.getOrPut(ref) { LinkedHashMap() }[role] = Pair(way, replacementWay)
                } else {
                    nonLaneletRemovals.getOrPut(ref) { ArrayList() }.add(way)
                }
            }
            deleteCmds.add(DeleteCommand(data, way))
        }

        for ((ref, roleMap) in laneletReplacements) {
            val waysToRemove = ArrayList<org.openstreetmap.josm.data.osm.OsmPrimitive>()
            for ((_, pair) in roleMap) {
                waysToRemove.add(pair.first)
            }
            for (w in laneletExtraRemovals[ref].orEmpty()) {
                waysToRemove.add(w)
            }
            val newRel = Relation(ref)
            newRel.removeMembersFor(waysToRemove)
            for (role in listOf("left", "right")) {
                val pair = roleMap[role] ?: continue
                val addIdx = if (role == "left") 0 else 1
                newRel.addMember(addIdx, RelationMember(role, pair.second))
            }
            changeCmds.add(ChangeCommand(ref, newRel))
        }
        for ((ref, ways) in nonLaneletRemovals) {
            val waysToRemove = ArrayList(ways)
            val newRel = Relation(ref)
            newRel.removeMembersFor(waysToRemove)
            if (newRel.membersCount < ref.membersCount) {
                changeCmds.add(ChangeCommand(ref, newRel))
            }
        }

        val commands = addCmds + changeCmds + deleteCmds
        if (commands.isEmpty()) return SplitPlan(emptyList(), emptyList(), 0, false)

        var hasLanelet = false
        for (way in waysToSplit) {
            for (ref in way.referrers) {
                if (ref is Relation && ref.get("type") == "lanelet") {
                    hasLanelet = true
                    break
                }
            }
            if (hasLanelet) break
        }
        return SplitPlan(commands, newWays, waysToSplit.size, hasLanelet)
    }

    fun apply(
        data: DataSet,
        selectedNodes: Collection<Node>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): SplitPlan? {
        val plan = commandsFor(data, selectedNodes) ?: return null
        if (plan.commands.isEmpty()) return plan
        applySequence(SEQUENCE_NAME, plan.commands, layer, undo)
        if (plan.newWays.isNotEmpty()) {
            data.setSelected(ArrayList(plan.newWays))
        }
        if (plan.hasLanelet) requestRoutingRefresh()
        return plan
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val selectedNodes = data.selected.mapNotNull { it as? Node }
        if (selectedNodes.isEmpty()) {
            ui.warn("Select nodes that lie on ways to split at those points.", TITLE)
            return
        }
        val plan = apply(data, selectedNodes, layer)
        if (plan == null) {
            ui.warn("No ways contain the selected nodes.", TITLE)
            return
        }
        if (plan.commands.isEmpty()) {
            ui.info("No valid splits. Select interior nodes (not first/last of a way).", TITLE)
            return
        }
        ui.infoAutoClose(
            "Split ${plan.waysSplit} way(s) into ${plan.newWays.size} new way(s). " +
                "Lanelets: kept first segment in driving direction. Other relations: removed.",
            TITLE,
            1500,
        )
    }
}
