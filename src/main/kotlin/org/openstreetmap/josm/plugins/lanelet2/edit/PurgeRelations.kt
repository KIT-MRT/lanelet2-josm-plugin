package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Delete relations, then member ways and nodes used only by those relations.
 *
 * One SequenceCommand: referrer ChangeCommands, DeleteCommand(relations),
 * DeleteCommand(ways), DeleteCommand(nodes). Port of `purge_relations_with_members.py`.
 *
 * The collection wizard is not ported; type/subtype filters the current selection.
 */
object PurgeRelations {
    const val TITLE = "Purge Relations"
    const val SEQUENCE_NAME = "Purge relations with members"

    data class Result(
        val deletedRelations: Int,
        val referrersUpdated: Int,
        val deletedWays: Int,
        val deletedNodes: Int,
    )

    fun memberWays(relations: Iterable<Relation?>): Set<Way> {
        val ways = HashSet<Way>()
        for (rel in relations) {
            if (rel == null) continue
            for (m in rel.members) {
                val mem = m.member
                if (mem is Way) ways.add(mem)
            }
        }
        return ways
    }

    fun wayNodes(ways: Iterable<Way?>): Set<Node> {
        val nodes = HashSet<Node>()
        for (w in ways) {
            if (w == null) continue
            for (n in w.nodes) {
                if (n != null) nodes.add(n)
            }
        }
        return nodes
    }

    fun commandsFor(data: DataSet, toDelete: List<Relation>): Pair<List<Command>, Result> {
        if (toDelete.isEmpty()) return Pair(emptyList(), Result(0, 0, 0, 0))
        val toDeleteSet = toDelete.toSet()
        val toDeleteIds = HashSet<Long>()
        for (r in toDelete) {
            try {
                toDeleteIds.add(r.uniqueId)
            } catch (_: Exception) {
            }
        }
        val commands = ArrayList<Command>()
        val referrers = HashSet<Relation>()
        for (rel in toDelete) {
            try {
                for (ref in rel.referrers) {
                    if (ref is Relation && ref !in toDeleteSet) {
                        referrers.add(ref)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val toDeleteJava = ArrayList(toDelete)
        for (ref in referrers) {
            val newRef = Relation(ref)
            val beforeCount = newRef.membersCount
            newRef.removeMembersFor(toDeleteJava)
            if (newRef.membersCount < beforeCount) {
                commands.add(ChangeCommand(ref, newRef))
            }
        }

        val waysToDelete = ArrayList<Way>()
        for (w in memberWays(toDelete)) {
            try {
                val refs = w.referrers
                if (refs.filterNotNull().all { it.uniqueId in toDeleteIds }) {
                    waysToDelete.add(w)
                }
            } catch (_: Exception) {
            }
        }
        val waysToDeleteIds = HashSet<Long>()
        for (w in waysToDelete) {
            try {
                waysToDeleteIds.add(w.uniqueId)
            } catch (_: Exception) {
            }
        }

        val nodesToDelete = ArrayList<Node>()
        for (n in wayNodes(waysToDelete)) {
            try {
                val refs = n.referrers
                if (refs.filterNotNull().all { it.uniqueId in waysToDeleteIds }) {
                    nodesToDelete.add(n)
                }
            } catch (_: Exception) {
            }
        }

        commands.add(DeleteCommand(data, toDeleteJava))
        if (waysToDelete.isNotEmpty()) {
            commands.add(DeleteCommand(data, ArrayList<OsmPrimitive>(waysToDelete)))
        }
        if (nodesToDelete.isNotEmpty()) {
            commands.add(DeleteCommand(data, ArrayList<OsmPrimitive>(nodesToDelete)))
        }
        val referrersUpdated = commands.count { it is ChangeCommand }
        return Pair(
            commands,
            Result(toDelete.size, referrersUpdated, waysToDelete.size, nodesToDelete.size),
        )
    }

    fun apply(
        data: DataSet,
        toDelete: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Result {
        val (commands, result) = commandsFor(data, toDelete)
        if (commands.isEmpty()) return Result(0, 0, 0, 0)
        applySequence(SEQUENCE_NAME, commands, layer, undo)
        requestRoutingRefresh()
        return result
    }

    fun run(
        ui: UserPrompts = Dialogs,
        typeSubtype: TypeSubtype.Choice? = null,
    ) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val choice = typeSubtype
            ?: TypeSubtype.prompt(ui, TypeSubtype.PURGE_RELATION_TYPES, "Purge Relations - Select Type")
            ?: return
        val collected = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, choice.type, choice.subtype,
        )
        if (collected.isEmpty()) {
            ui.infoAutoClose("No relations to purge.", TITLE, 1000)
            return
        }
        if (!ui.confirm(
                "Purge ${collected.size} relation(s) including their member ways and nodes?\n\n" +
                    "Relations will be removed from referrers. Member ways and nodes that " +
                    "are exclusively used by these relations will also be deleted.",
                TITLE,
            )
        ) {
            return
        }
        val result = apply(data, collected, layer)
        ui.infoAutoClose(
            "Purged ${result.deletedRelations} relation(s). Updated ${result.referrersUpdated} referrer(s).\n" +
                "Deleted ${result.deletedWays} way(s) and ${result.deletedNodes} node(s).",
            TITLE,
            3000,
        )
    }
}
