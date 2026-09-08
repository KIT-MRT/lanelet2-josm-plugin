package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Merge pairs of lanelets that share the same two border ways with swapped
 * left/right roles. Keep the lower uniqueId, set `one_way=no`, retarget
 * referrers, delete the duplicate.
 *
 * One SequenceCommand. Port of `merge_lanelets_swapped_borders.py`.
 */
object MergeSwappedBorders {
    const val TITLE = "Merge swapped-border lanelets"
    const val SEQUENCE_NAME = "Merge lanelets (swapped borders, one_way=no)"

    fun dedupeLanelets(lanelets: Iterable<Relation?>): List<Relation> {
        val out = ArrayList<Relation>()
        val seen = HashSet<Long>()
        for (ll in lanelets) {
            if (ll == null) continue
            val uid = try {
                ll.uniqueId
            } catch (_: Exception) {
                continue
            }
            if (uid !in seen) {
                seen.add(uid)
                out.add(ll)
            }
        }
        return out
    }

    /**
     * Each lanelet is in at most one pair. Keep is the lower [OsmPrimitive.getUniqueId].
     * For new (negative-id) primitives the generator goes more negative, so "lower"
     * can be the *newer* object — same as the Jython.
     */
    fun findSwappedBorderPairs(lanelets: List<Relation>): List<Pair<Relation, Relation>> {
        val list = dedupeLanelets(lanelets)
        val assigned = HashSet<Long>()
        val pairs = ArrayList<Pair<Relation, Relation>>()
        for (i in list.indices) {
            val a = list[i]
            val aid = a.uniqueId
            if (aid in assigned) continue
            val (la, ra) = Lanelet.extractLeftRightWays(a)
            if (la == null || ra == null) continue
            for (j in i + 1 until list.size) {
                val b = list[j]
                val bid = b.uniqueId
                if (bid in assigned) continue
                val (lb, rb) = Lanelet.extractLeftRightWays(b)
                if (lb == null || rb == null) continue
                if (la === rb && ra === lb) {
                    if (aid < bid) {
                        pairs.add(Pair(a, b))
                    } else {
                        pairs.add(Pair(b, a))
                    }
                    assigned.add(aid)
                    assigned.add(bid)
                    break
                }
            }
        }
        return pairs
    }

    fun collectReferrerRelations(deletedSet: Set<Relation>): Set<Relation> {
        val refs = HashSet<Relation>()
        for (d in deletedSet) {
            try {
                for (ref in d.referrers) {
                    if (ref is Relation && ref !in deletedSet) {
                        refs.add(ref)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return refs
    }

    /**
     * [deleteToKeep] is looked up with [OsmPrimitive.equals] (id), matching the
     * Jython dict. The `while mem in delete_to_keep` chain is preserved; pairs
     * are disjoint so it cannot loop on well-formed input.
     */
    fun replaceDeletedMembers(
        ref: Relation,
        deleteToKeep: Map<Relation, Relation>,
    ): Pair<Relation, Boolean> {
        val newRef = Relation(ref)
        val newMembers = ArrayList<RelationMember>()
        val seen = HashSet<Pair<String, Long>>()
        var changed = false
        for (m in ref.members) {
            val role = m.role
            var mem: OsmPrimitive = m.member ?: continue
            while (true) {
                val next = if (mem is Relation) deleteToKeep[mem] else null
                if (next == null) break
                mem = next
                changed = true
            }
            val key = try {
                Pair(role, mem.uniqueId)
            } catch (_: Exception) {
                continue
            }
            if (key in seen) continue
            seen.add(key)
            newMembers.add(RelationMember(role, mem))
        }
        newRef.setMembers(newMembers)
        return Pair(newRef, changed)
    }

    fun commandsFor(data: DataSet, pairs: List<Pair<Relation, Relation>>): List<Command> {
        if (pairs.isEmpty()) return emptyList()
        val deleteToKeep = HashMap<Relation, Relation>()
        val deletes = ArrayList<Relation>()
        val keepsToTag = ArrayList<Relation>()
        for ((keep, delete) in pairs) {
            deleteToKeep[delete] = keep
            deletes.add(delete)
            keepsToTag.add(keep)
        }
        val deletedSet = deletes.toSet()
        val commands = ArrayList<Command>()
        for (ref in collectReferrerRelations(deletedSet)) {
            val (newRef, changed) = replaceDeletedMembers(ref, deleteToKeep)
            if (changed) commands.add(ChangeCommand(ref, newRef))
        }
        // Latent: each keep is cloned from the *original*, so a keep that is
        // also a referrer of a deleted lanelet loses the member rewrite when
        // this second ChangeCommand runs. Matches the Jython order.
        for (keep in dedupeLanelets(keepsToTag)) {
            val newKeep = Relation(keep)
            newKeep.put("one_way", "no")
            commands.add(ChangeCommand(keep, newKeep))
        }
        commands.add(DeleteCommand(data, ArrayList(deletes)))
        return commands
    }

    fun apply(
        data: DataSet,
        pairs: List<Pair<Relation, Relation>>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Int {
        val commands = commandsFor(data, pairs)
        if (commands.isEmpty()) return 0
        applySequence(SEQUENCE_NAME, commands, layer, undo)
        requestRoutingRefresh()
        return pairs.size
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val lanelets = dedupeLanelets(
            LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected),
        )
        if (lanelets.size < 2) {
            ui.warn(
                "Select at least two lanelets (or their border linestrings) to search for mergeable pairs.",
                TITLE,
            )
            return
        }
        val pairs = findSwappedBorderPairs(lanelets)
        if (pairs.isEmpty()) {
            ui.info(
                "No pair found: two lanelets with the same left/right ways and roles swapped.",
                TITLE,
            )
            return
        }
        val n = apply(data, pairs, layer)
        ui.infoAutoClose(
            "Merged $n pair(s). Kept lanelet(s) set to one_way=no. Referrers updated; debug routing graph refreshed if available.",
            TITLE,
            2800,
        )
    }
}
