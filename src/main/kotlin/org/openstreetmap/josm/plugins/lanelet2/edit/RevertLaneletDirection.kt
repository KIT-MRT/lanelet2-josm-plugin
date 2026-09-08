package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Swap left/right member roles on selected lanelet relations.
 *
 * One [org.openstreetmap.josm.command.SequenceCommand] for the whole selection.
 * Port of `revert_lanelet_direction.py`.
 */
object RevertLaneletDirection {
    const val TITLE = "Revert Lanelet Direction"
    const val SEQUENCE_NAME = "Revert lanelet direction"

    fun revertCopy(rel: Relation): Relation {
        val newRel = Relation(rel)
        val newMembers = ArrayList<RelationMember>()
        for (m in newRel.members) {
            val newRole = when (m.role) {
                "left" -> "right"
                "right" -> "left"
                else -> m.role
            }
            newMembers.add(RelationMember(newRole, m.member))
        }
        newRel.setMembers(newMembers)
        return newRel
    }

    fun commandsFor(lanelets: List<Relation>): List<Command> {
        val commands = ArrayList<Command>(lanelets.size)
        for (ll in lanelets) {
            commands.add(ChangeCommand(ll, revertCopy(ll)))
        }
        return commands
    }

    fun apply(
        lanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Int {
        if (lanelets.isEmpty()) return 0
        applySequence(SEQUENCE_NAME, commandsFor(lanelets), layer, undo)
        return lanelets.size
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val lanelets = LaneletSelection.extractLanelets(layer.data.selected)
        if (lanelets.isEmpty()) {
            ui.warn("Select at least one lanelet relation to revert.", TITLE)
            return
        }
        val n = apply(lanelets, layer)
        ui.infoAutoClose("Reverted direction of $n lanelet(s).", TITLE, 1000)
    }
}
