package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.RegulatoryElements
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts

/**
 * Create a `type=regulatory_element` / `subtype=traffic_sign` relation.
 *
 * Tags: `type=regulatory_element`, `subtype=traffic_sign`.
 * Roles: `refers` (traffic signs), optional `ref_line`.
 *
 * One SequenceCommand. Port of `create_traffic_sign_relation.py`.
 *
 * The Jython 3-step collection wizard is not ported; [run] uses the current
 * selection.
 *
 * **lanelet2 C++:** `TrafficSign` also supports `cancels` / `cancel_line`. The
 * Jython never collects those; this port does not either.
 */
object CreateTrafficSignRelation {
    const val TITLE = "Create Traffic Sign Relation"
    const val SEQUENCE_NAME = "Create traffic sign regulatory element"
    const val SUBTYPE = "traffic_sign"

    fun extractTrafficSigns(selection: Iterable<OsmPrimitive?>): List<Way> {
        return RegulatoryCreate.extractWaysByType(selection, setOf("traffic_sign"))
    }

    fun buildRelation(refLine: Way?, trafficSigns: List<Way>): Relation {
        val rel = RegulatoryCreate.newRelation(SUBTYPE)
        for (ts in trafficSigns) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_REFERS, ts))
        }
        if (refLine != null) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_REF_LINE, refLine))
        }
        return rel
    }

    fun apply(
        data: DataSet,
        refLine: Way?,
        trafficSigns: List<Way>,
        lanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Relation {
        val rel = buildRelation(refLine, trafficSigns)
        return RegulatoryCreate.apply(data, rel, lanelets, SEQUENCE_NAME, layer, undo)
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val selection = data.selected
        val (refLine, err) = RegulatoryElements.extractRefLine(selection)
        if (err != null) {
            ui.warn(err, TITLE)
            return
        }
        val signs = extractTrafficSigns(selection)
        if (signs.isEmpty()) {
            ui.warn("Select at least 1 traffic sign (type=traffic_sign).", TITLE)
            return
        }
        val lanelets = LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
        if (lanelets.isEmpty()) {
            ui.warn("Select at least one lanelet (or its border linestrings).", TITLE)
            return
        }
        apply(data, refLine, signs, lanelets, layer)
        ui.info(
            "Created traffic sign regulatory element.\n" +
                "Added to ${lanelets.size} lanelet(s).\n" +
                "Review in Properties dialog (Alt+O if not visible).",
            TITLE,
        )
    }
}
