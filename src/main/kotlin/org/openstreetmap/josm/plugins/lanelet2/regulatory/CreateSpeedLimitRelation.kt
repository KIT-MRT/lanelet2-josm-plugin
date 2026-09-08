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
 * Create a `type=regulatory_element` / `subtype=speed_limit` relation.
 *
 * Tags: `type=regulatory_element`, `subtype=speed_limit`, optional `sign_type`.
 * Roles: `refers` (traffic signs) and/or `sign_type` tag; optional `ref_line`.
 *
 * One SequenceCommand. Port of `create_speed_limit_relation.py`.
 *
 * The Jython 3-step collection wizard is not ported; [run] uses the current
 * selection. If no traffic-sign ways are selected, [UserPrompts.ask] collects
 * `sign_type` (e.g. `"50 km/h"`).
 *
 * **lanelet2 C++:** `SpeedLimit` extends `TrafficSign` and also supports
 * `cancels` / `cancel_line`. The Jython never collects those. C++ `sign_type`
 * is the same tag (`AttributeNamesString::SignType`).
 */
object CreateSpeedLimitRelation {
    const val TITLE = "Create Speed Limit Relation"
    const val SEQUENCE_NAME = "Create speed limit regulatory element"
    const val SUBTYPE = "speed_limit"
    const val SIGN_TYPE_TITLE = "Speed Limit - sign_type"

    fun extractTrafficSigns(selection: Iterable<OsmPrimitive?>): List<Way> =
        CreateTrafficSignRelation.extractTrafficSigns(selection)

    fun buildRelation(refLine: Way?, trafficSigns: List<Way>, signType: String?): Relation {
        val rel = RegulatoryCreate.newRelation(SUBTYPE)
        for (ts in trafficSigns) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_REFERS, ts))
        }
        if (signType != null) {
            rel.put("sign_type", signType.trim())
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
        signType: String?,
        lanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Relation {
        val rel = buildRelation(refLine, trafficSigns, signType)
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
        val signType = if (signs.isNotEmpty()) {
            null
        } else {
            val entered = ui.ask(
                SIGN_TYPE_TITLE,
                "Enter sign_type (e.g. 50 km/h). Unit optional, km/h assumed if omitted.",
            )
            if (entered.isNullOrBlank()) {
                ui.warn("Need either traffic sign(s) or sign_type.", TITLE)
                return
            }
            entered.trim()
        }
        val lanelets = LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
        if (lanelets.isEmpty()) {
            ui.warn("Select at least one lanelet (or its border linestrings).", TITLE)
            return
        }
        apply(data, refLine, signs, signType, lanelets, layer)
        val signLine = if (signType != null) "sign_type: $signType\n" else ""
        ui.info(
            "Created speed limit regulatory element.\n" +
                "Added to ${lanelets.size} lanelet(s).\n" +
                signLine +
                "Review in Properties dialog (Alt+O if not visible).",
            TITLE,
        )
    }
}
