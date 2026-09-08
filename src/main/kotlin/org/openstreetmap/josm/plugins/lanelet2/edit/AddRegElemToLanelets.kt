package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts

/**
 * Add an existing regulatory element as a `regulatory_element` member of lanelets.
 *
 * One SequenceCommand covering every lanelet that did not already reference it.
 * Port of `add_reg_elem_to_lanelets.py`.
 *
 * The Jython 2-step collection wizard is not ported; the action uses the current
 * selection (one regulatory element plus lanelets or their border linestrings).
 */
object AddRegElemToLanelets {
    const val TITLE = "Add Regulatory Element to Lanelets"
    const val SEQUENCE_NAME = "Add regulatory element to lanelets"

    fun extractRegulatoryElement(selection: Iterable<OsmPrimitive?>): Relation? {
        for (prim in selection) {
            if (prim != null && prim is Relation) {
                val t = prim.get("type")
                if (t != null && t.lowercase() == "regulatory_element") {
                    return prim
                }
            }
        }
        return null
    }

    fun commandsFor(regElem: Relation, lanelets: List<Relation>): List<Command> {
        val commands = ArrayList<Command>()
        for (lanelet in lanelets) {
            val already = lanelet.members.any { m ->
                m.role == "regulatory_element" && m.member == regElem
            }
            if (already) continue
            val newLanelet = Relation(lanelet)
            newLanelet.addMember(
                newLanelet.membersCount,
                RelationMember("regulatory_element", regElem),
            )
            commands.add(ChangeCommand(lanelet, newLanelet))
        }
        return commands
    }

    fun apply(
        regElem: Relation,
        lanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Int {
        val commands = commandsFor(regElem, lanelets)
        if (commands.isEmpty()) return 0
        applySequence(SEQUENCE_NAME, commands, layer, undo)
        return commands.size
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val selection = data.selected
        val regElem = extractRegulatoryElement(selection)
        if (regElem == null) {
            ui.warn("Select exactly 1 regulatory element (type=regulatory_element).", TITLE)
            return
        }
        val lanelets = LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
            .filter { it !== regElem }
        if (lanelets.isEmpty()) {
            ui.warn("Select at least one lanelet (or its border linestrings).", TITLE)
            return
        }
        val n = apply(regElem, lanelets, layer)
        if (n == 0) {
            ui.info("All selected lanelets already have this regulatory element.", TITLE)
            return
        }
        ui.info("Added regulatory element to $n lanelet(s).", TITLE)
    }
}
