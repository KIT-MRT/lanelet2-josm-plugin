package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.command.AddCommand
import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import java.util.ArrayList

/**
 * Shared create + attach-to-lanelets command sequence used by the four
 * regulatory-element wizards.
 *
 * One [org.openstreetmap.josm.command.SequenceCommand]: [AddCommand] for the
 * new relation, then one [ChangeCommand] per lanelet (clone of the original,
 * matching the Jython). Never mutate primitives already in the dataset.
 */
internal object RegulatoryCreate {
    const val TYPE = "regulatory_element"
    const val ROLE_REG_ELEM = "regulatory_element"
    const val ROLE_REF_LINE = "ref_line"
    const val ROLE_REFERS = "refers"
    const val ROLE_YIELD = "yield"
    const val ROLE_RIGHT_OF_WAY = "right_of_way"

    fun newRelation(subtype: String): Relation {
        val rel = Relation()
        rel.put("type", TYPE)
        rel.put("subtype", subtype)
        return rel
    }

    fun extractWaysByType(
        selection: Iterable<OsmPrimitive?>,
        types: Set<String>,
    ): List<Way> {
        val lowered = types.map { it.lowercase() }.toSet()
        val out = ArrayList<Way>()
        for (prim in selection) {
            if (prim == null || prim !is Way) continue
            val t = prim.get("type") ?: continue
            if (t.lowercase() in lowered) out.add(prim)
        }
        return out
    }

    fun commandsFor(
        data: DataSet,
        newRelation: Relation,
        lanelets: List<Relation>,
    ): List<Command> {
        val commands = ArrayList<Command>(1 + lanelets.size)
        commands.add(AddCommand(data, newRelation))
        for (lanelet in lanelets) {
            val newLanelet = Relation(lanelet)
            newLanelet.addMember(
                newLanelet.membersCount,
                RelationMember(ROLE_REG_ELEM, newRelation),
            )
            commands.add(ChangeCommand(lanelet, newLanelet))
        }
        return commands
    }

    fun apply(
        data: DataSet,
        newRelation: Relation,
        lanelets: List<Relation>,
        sequenceName: String,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Relation {
        applySequence(sequenceName, commandsFor(data, newRelation, lanelets), layer, undo)
        try {
            data.setSelected(newRelation)
        } catch (_: Exception) {
        }
        tryShowProperties()
        return newRelation
    }

    /**
     * Jython called `MainApplication.getToggleDialogManager().showDialog(PropertiesDialog)`,
     * which does not exist on JOSM 19555 and was already swallowed. Best-effort
     * equivalent: unfurl the map's properties toggle if a map exists.
     */
    fun tryShowProperties() {
        try {
            MainApplication.getMap()?.propertiesDialog?.showDialog()
        } catch (_: Exception) {
        }
    }
}
