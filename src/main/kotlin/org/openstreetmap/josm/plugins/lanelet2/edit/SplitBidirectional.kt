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
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Centerline
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.LonLat
import org.openstreetmap.josm.plugins.lanelet2.infra.wayViewPointsLonLat
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Split bidirectional lanelets (`one_way=no` or `one_way=0`) into two one-way
 * lanelets sharing a virtual centerline.
 *
 * Centerline vertices come from [Centerline.calculateCenterlinePoints] (the
 * already-verified C++/Jython port). Bounds are taken from [Lanelet] geometric
 * [org.openstreetmap.josm.plugins.lanelet2.infra.WayView] alignment; we do
 * **not** call [Lanelet.invert] and we do not reverse point order ourselves.
 *
 * One [org.openstreetmap.josm.command.SequenceCommand] for the whole selection.
 * Port of `split_bidirectional_lanelets_on_centerline.py`.
 */
object SplitBidirectional {
    const val TITLE = "Split bidirectional on centerline"
    const val SEQUENCE_NAME = "Split bidirectional lanelets on centerline"

    sealed class Plan {
        data object NothingToSplit : Plan()
        data class Abort(val message: String) : Plan()
        data class Ready(
            val commands: List<Command>,
            val deleted: Int,
            val created: Int,
            val sharedKeys: Int,
            val newRelations: List<Relation>,
        ) : Plan()
    }

    fun isBidirectional(rel: Relation): Boolean {
        val v = rel.get("one_way") ?: return false
        val s = v.toString().trim().lowercase()
        return s == "no" || s == "0"
    }

    fun pairKeyNodeIds(na: Node?, nb: Node?): Set<Long>? {
        if (na == null || nb == null) return null
        return setOf(na.uniqueId, nb.uniqueId)
    }

    fun collectSharedJunctionKeys(lanelets: List<Relation>): Set<Set<Long>> {
        val shared = HashSet<Set<Long>>()
        val n = lanelets.size
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val a = lanelets[i]
                val b = lanelets[j]
                val la = Lanelet(a)
                val lb = Lanelet(b)
                val (aEndL, aEndR) = la.getLastNodesInDrivingDirection()
                val (bStaL, bStaR) = lb.getFirstNodesInDrivingDirection()
                val kEnd = pairKeyNodeIds(aEndL, aEndR)
                val kSta = pairKeyNodeIds(bStaL, bStaR)
                if (kEnd != null && kSta != null && kEnd == kSta) shared.add(kEnd)
                val (aStaL, aStaR) = la.getFirstNodesInDrivingDirection()
                val (bEndL, bEndR) = lb.getLastNodesInDrivingDirection()
                val kAs = pairKeyNodeIds(aStaL, aStaR)
                val kBe = pairKeyNodeIds(bEndL, bEndR)
                if (kAs != null && kBe != null && kAs == kBe) shared.add(kAs)
            }
        }
        return shared
    }

    fun copyLaneletTagsExceptOneway(src: Relation, dst: Relation) {
        try {
            for (k in src.keySet()) {
                if (k == null) continue
                val ks = k.toString()
                if (ks == "one_way") continue
                dst.put(ks, src.get(k))
            }
        } catch (_: Exception) {
        }
        dst.put("type", "lanelet")
        dst.put("one_way", "yes")
    }

    fun regulatoryMembers(rel: Relation): List<org.openstreetmap.josm.data.osm.OsmPrimitive> {
        val out = ArrayList<org.openstreetmap.josm.data.osm.OsmPrimitive>()
        for (m in rel.members) {
            if (m.role == "regulatory_element") {
                val mem = m.member ?: continue
                out.add(mem)
            }
        }
        return out
    }

    fun expandReferrer(
        ref: Relation,
        replaceByUid: Map<Long, List<Relation>>,
        commands: MutableList<Command>,
    ) {
        var changed = false
        val newRef = Relation(ref)
        val newMembers = ArrayList<RelationMember>()
        val seen = HashSet<Pair<String, Long>>()
        for (m in ref.members) {
            val role = m.role
            val mem = m.member ?: continue
            val uid = try {
                mem.uniqueId
            } catch (_: Exception) {
                null
            }
            if (uid != null && uid in replaceByUid) {
                changed = true
                for (nr in replaceByUid[uid]!!) {
                    val key = Pair(role, nr.uniqueId)
                    if (key in seen) continue
                    seen.add(key)
                    newMembers.add(RelationMember(role, nr))
                }
            } else {
                val key = Pair(role, mem.uniqueId)
                if (key in seen) continue
                seen.add(key)
                newMembers.add(RelationMember(role, mem))
            }
        }
        if (!changed) return
        newRef.setMembers(newMembers)
        commands.add(ChangeCommand(ref, newRef))
    }

    fun nodeForVertex(
        xy: LonLat,
        junctionKey: Set<Long>?,
        sharedKeys: Set<Set<Long>>,
        junctionCache: MutableMap<Set<Long>, Node>,
        data: DataSet,
        commands: MutableList<Command>,
    ): Node {
        val lat = xy.lat
        val lon = xy.lon
        if (junctionKey != null && junctionKey in sharedKeys) {
            val cached = junctionCache[junctionKey]
            if (cached != null) return cached
            val n = Node(LatLon(lat, lon))
            commands.add(AddCommand(data, n))
            junctionCache[junctionKey] = n
            return n
        }
        val n = Node(LatLon(lat, lon))
        commands.add(AddCommand(data, n))
        return n
    }

    fun collectBidirectional(data: DataSet, selection: Iterable<org.openstreetmap.josm.data.osm.OsmPrimitive?>): List<Relation> {
        val lanelets = LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
        val bidir = ArrayList<Relation>()
        val seen = HashSet<Long>()
        for (rel in lanelets) {
            if (rel.uniqueId in seen) continue
            val t = rel.get("type") ?: continue
            if (t.lowercase() != "lanelet") continue
            if (!isBidirectional(rel)) continue
            seen.add(rel.uniqueId)
            bidir.add(rel)
        }
        return bidir
    }

    fun commandsFor(data: DataSet, bidir: List<Relation>): Plan {
        if (bidir.isEmpty()) return Plan.NothingToSplit
        val sharedKeys = collectSharedJunctionKeys(bidir)
        val junctionCache = HashMap<Set<Long>, Node>()
        val commands = ArrayList<Command>()
        val replaceByUid = HashMap<Long, List<Relation>>()
        val newRelations = ArrayList<Relation>()
        val toDelete = ArrayList<Relation>()

        for (old in bidir.sortedBy { it.uniqueId }) {
            val ll = Lanelet(old)
            val lv = ll.leftBound()
            val rv = ll.rightBound()
            if (lv == null || rv == null) {
                return Plan.Abort("Lanelet ${old.uniqueId} is missing left or right border; skipped.")
            }
            val leftWay = lv.way
            val rightWay = rv.way
            if (leftWay == null || rightWay == null) {
                return Plan.Abort("Lanelet ${old.uniqueId} is missing left or right border; skipped.")
            }
            val leftPts = wayViewPointsLonLat(lv)
            val rightPts = wayViewPointsLonLat(rv)
            if (leftPts.size < 2 || rightPts.size < 2) {
                return Plan.Abort("Lanelet ${old.uniqueId} has degenerate borders; skipped.")
            }
            val centerPts = Centerline.calculateCenterlinePoints(leftPts, rightPts)
            if (centerPts.size < 2) {
                return Plan.Abort("Centerline for lanelet ${old.uniqueId} is degenerate; skipped.")
            }

            val startKey = pairKeyNodeIds(lv.getFirstNode(), rv.getFirstNode())
            val endKey = pairKeyNodeIds(lv.getLastNode(), rv.getLastNode())
            val centerNodes = ArrayList<Node>(centerPts.size)
            val npt = centerPts.size
            for ((i, xy) in centerPts.withIndex()) {
                val jk = when (i) {
                    0 -> if (startKey != null && startKey in sharedKeys) startKey else null
                    npt - 1 -> if (endKey != null && endKey in sharedKeys) endKey else null
                    else -> null
                }
                centerNodes.add(nodeForVertex(xy, jk, sharedKeys, junctionCache, data, commands))
            }

            val centerWay = Way()
            centerWay.put("type", "virtual")
            centerWay.setNodes(centerNodes)
            commands.add(AddCommand(data, centerWay))

            val newR1 = Relation()
            copyLaneletTagsExceptOneway(old, newR1)
            newR1.addMember(0, RelationMember("left", centerWay))
            newR1.addMember(1, RelationMember("right", leftWay))
            var idx = 2
            for (reg in regulatoryMembers(old)) {
                newR1.addMember(idx, RelationMember("regulatory_element", reg))
                idx++
            }
            commands.add(AddCommand(data, newR1))

            val newR2 = Relation()
            copyLaneletTagsExceptOneway(old, newR2)
            newR2.addMember(0, RelationMember("left", centerWay))
            newR2.addMember(1, RelationMember("right", rightWay))
            idx = 2
            for (reg in regulatoryMembers(old)) {
                newR2.addMember(idx, RelationMember("regulatory_element", reg))
                idx++
            }
            commands.add(AddCommand(data, newR2))

            replaceByUid[old.uniqueId] = listOf(newR1, newR2)
            newRelations.add(newR1)
            newRelations.add(newR2)
            toDelete.add(old)
        }

        val allRefs = HashSet<Relation>()
        for (old in toDelete) {
            try {
                for (ref in old.referrers) {
                    if (ref is Relation) allRefs.add(ref)
                }
            } catch (_: Exception) {
            }
        }
        val delSet = toDelete.toSet()
        for (ref in allRefs) {
            if (ref in delSet) continue
            expandReferrer(ref, replaceByUid, commands)
        }
        commands.add(DeleteCommand(data, ArrayList(toDelete)))
        return Plan.Ready(commands, toDelete.size, newRelations.size, sharedKeys.size, newRelations)
    }

    fun apply(
        data: DataSet,
        bidir: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Plan {
        val plan = commandsFor(data, bidir)
        if (plan is Plan.Ready) {
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
        val bidir = collectBidirectional(data, data.selected)
        if (bidir.isEmpty()) {
            ui.warn(
                "No bidirectional lanelets (one_way=no or one_way=0) found in the selection.",
                TITLE,
            )
            return
        }
        when (val plan = apply(data, bidir, layer)) {
            Plan.NothingToSplit -> ui.warn(
                "No bidirectional lanelets (one_way=no or one_way=0) found in the selection.",
                TITLE,
            )
            is Plan.Abort -> ui.warn(plan.message, TITLE)
            is Plan.Ready -> ui.info(
                "Split ${plan.deleted} bidirectional lanelet(s) into ${plan.created} one-way lanelet(s) with shared virtual centerlines.\n" +
                    "Junction reuse: ${plan.sharedKeys} shared endpoint key(s) in this selection (same node where chain continues).\n" +
                    "Other intersections may need manual node merge for routing.",
                TITLE,
            )
        }
    }
}
