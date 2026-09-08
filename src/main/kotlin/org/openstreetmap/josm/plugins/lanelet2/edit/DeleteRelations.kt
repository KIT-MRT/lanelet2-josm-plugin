package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Remove selected relations from every referrer, then delete them.
 *
 * One SequenceCommand: ChangeCommand per updated referrer, then one DeleteCommand
 * for all target relations. Port of `delete_relations_with_membership_removal.py`.
 *
 * The collection wizard is not ported; type/subtype filters the current selection
 * (direct relations plus parents of selected ways).
 */
object DeleteRelations {
    const val TITLE = "Delete Relations"
    const val SEQUENCE_NAME = "Delete relations and remove from referrers"

    data class Result(val deleted: Int, val referrersUpdated: Int)

    fun commandsFor(data: DataSet, toDelete: List<Relation>): Pair<List<Command>, Result> {
        if (toDelete.isEmpty()) return Pair(emptyList(), Result(0, 0))
        val toDeleteSet = toDelete.toSet()
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
        commands.add(DeleteCommand(data, toDeleteJava))
        val referrersUpdated = commands.count { it is ChangeCommand }
        return Pair(commands, Result(toDelete.size, referrersUpdated))
    }

    fun apply(
        data: DataSet,
        toDelete: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Result {
        val (commands, result) = commandsFor(data, toDelete)
        if (commands.isEmpty()) return Result(0, 0)
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
            ?: TypeSubtype.prompt(ui, TypeSubtype.DELETE_RELATION_TYPES, "Delete Relations - Select Type")
            ?: return
        val collected = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, choice.type, choice.subtype,
        )
        if (collected.isEmpty()) {
            ui.infoAutoClose("No relations to delete.", TITLE, 1000)
            return
        }
        if (!ui.confirm(
                "Delete ${collected.size} relation(s) and remove them from all referrers (e.g. lanelets)?",
                TITLE,
            )
        ) {
            return
        }
        val result = apply(data, collected, layer)
        ui.infoAutoClose(
            "Deleted ${result.deleted} relation(s). Updated ${result.referrersUpdated} referrer relation(s).",
            TITLE,
            3000,
        )
    }
}
