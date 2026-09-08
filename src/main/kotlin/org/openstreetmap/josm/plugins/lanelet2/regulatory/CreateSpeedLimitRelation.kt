package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
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
 * With the collection-dialog setting, [run] is the Jython 3-step wizard
 * (optional ref_line, signs or sign_type, lanelets). Off: current selection;
 * if no traffic-sign ways are selected, [UserPrompts.ask] collects `sign_type`.
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

    const val HELP_TEXT = """Speed Limit Regulatory Element (Lanelet2)

Tags: type=regulatory_element, subtype=speed_limit

Two modes:
1) From traffic sign: refers to traffic sign(s) (type=traffic_sign). TrafficRules interpret speed from sign subtype.
2) Without sign: sign_type tag, e.g. "50 km/h". Unit optional (km/h assumed). mph, mps also supported.

Optional: ref_line (start of restriction), cancels (signs marking end).

Traffic signs: type=traffic_sign, subtype=region+number (e.g. de206, usR1-1).
All affected lanelets must reference this regulatory element."""

    val HELP_LINKS: List<Pair<String, String>> = listOf(
        "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
        "LinestringTagging" to "LinestringTagging.md",
    )

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
        if (CollectionLogic.shouldOpenCollectionDialog()) {
            CollectionDialog.clearSelection(data)
            CollectionDialog.showStep(
                title = "Speed Limit - Step 1/3",
                message = "Select ref_line (stop line, type=stop_line).\n\nLeave selection empty to skip. Click OK when done.",
                onOk = {
                    val (refLine, err) = RegulatoryElements.extractRefLine(data.selected)
                    if (err != null) {
                        ui.warn(err, TITLE)
                        return@showStep
                    }
                    CollectionDialog.clearSelection(data)
                    CollectionDialog.showStepMulti(
                        title = "Speed Limit - Step 2/3",
                        message = "Select traffic sign(s) (type=traffic_sign).\n\n" +
                            "Or leave empty and click Done to enter sign_type manually (e.g. 50 km/h).",
                        data = data,
                        extract = { extractTrafficSigns(it) },
                        onDone = { signs ->
                            val signType = if (signs.isNotEmpty()) {
                                null
                            } else {
                                val entered = ui.ask(
                                    SIGN_TYPE_TITLE,
                                    "Enter sign_type (e.g. 50 km/h). Unit optional, km/h assumed if omitted.",
                                )
                                if (entered.isNullOrBlank()) {
                                    ui.warn("Need either traffic sign(s) or sign_type.", TITLE)
                                    return@showStepMulti
                                }
                                entered.trim()
                            }
                            CollectionDialog.clearSelection(data)
                            CollectionDialog.showLaneletCollection(
                                data = data,
                                onDone = { lanelets ->
                                    apply(data, refLine, signs.filterIsInstance<Way>(), signType, lanelets, layer)
                                    ui.info(createdMessage(lanelets.size, signType), TITLE)
                                },
                                title = "Speed Limit - Step 3/3",
                                message = "Select lanelets or linestrings (lane boundaries). Linestrings infer lanelets (Mode B).",
                                minCount = 1,
                                helpTitle = "Speed Limit - Lanelet2 Tagging",
                                helpText = HELP_TEXT,
                                helpLinks = HELP_LINKS,
                                ui = ui,
                            )
                        },
                        minCount = 0,
                        itemNamePlural = "traffic sign(s)",
                        highlight = "traffic sign(s)",
                        ui = ui,
                    )
                },
                highlight = "ref_line",
                helpTitle = "Speed Limit - Lanelet2 Tagging",
                helpText = HELP_TEXT,
                helpLinks = HELP_LINKS,
            )
            return
        }
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
        ui.info(createdMessage(lanelets.size, signType), TITLE)
    }

    fun createdMessage(laneletCount: Int, signType: String?): String {
        val signLine = if (signType != null) "sign_type: $signType\n" else ""
        return "Created speed limit regulatory element.\n" +
            "Added to $laneletCount lanelet(s).\n" +
            signLine +
            "Review in Properties dialog (Alt+O if not visible)."
    }
}
