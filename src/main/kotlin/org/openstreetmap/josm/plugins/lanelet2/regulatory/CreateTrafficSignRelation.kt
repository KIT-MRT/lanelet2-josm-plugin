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
 * Create a `type=regulatory_element` / `subtype=traffic_sign` relation.
 *
 * Tags: `type=regulatory_element`, `subtype=traffic_sign`.
 * Roles: `refers` (traffic signs), optional `ref_line`.
 *
 * One SequenceCommand. Port of `create_traffic_sign_relation.py`.
 *
 * With the collection-dialog setting, [run] is the Jython 3-step wizard
 * (optional ref_line, traffic signs, lanelets). Off: current selection.
 *
 * **lanelet2 C++:** `TrafficSign` also supports `cancels` / `cancel_line`. The
 * Jython never collects those; this port does not either.
 */
object CreateTrafficSignRelation {
    const val TITLE = "Create Traffic Sign Relation"
    const val SEQUENCE_NAME = "Create traffic sign regulatory element"
    const val SUBTYPE = "traffic_sign"

    const val HELP_TEXT = """Traffic Sign Regulatory Element (Lanelet2)

Tags: type=regulatory_element, subtype=traffic_sign

Roles:
- refers: Traffic sign(s) (type=traffic_sign) that form the rule.
- cancels (optional): Signs marking end of restriction (e.g. end of no-overtaking).
- ref_line, cancel_line (optional): Exact start/end of rule. If they intersect the lanelet, rule is valid from/to that point. Otherwise whole lanelet.

Traffic signs: type=traffic_sign, subtype=region+number (e.g. de206, usR1-1).
All affected lanelets must reference this regulatory element."""

    val HELP_LINKS: List<Pair<String, String>> = listOf(
        "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
        "LinestringTagging" to "LinestringTagging.md",
    )

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
        if (CollectionLogic.shouldOpenCollectionDialog()) {
            CollectionDialog.clearSelection(data)
            CollectionDialog.showStep(
                title = "Traffic Sign - Step 1/3",
                message = "Select ref_line (stop line, type=stop_line).\n\nLeave selection empty to skip. Click OK when done.",
                onOk = {
                    val (refLine, err) = RegulatoryElements.extractRefLine(data.selected)
                    if (err != null) {
                        ui.warn(err, TITLE)
                        return@showStep
                    }
                    CollectionDialog.clearSelection(data)
                    CollectionDialog.showStepMulti(
                        title = "Traffic Sign - Step 2/3",
                        message = "Select traffic sign(s) (type=traffic_sign).\n\nClick Add to add them, Done when finished.",
                        data = data,
                        extract = { extractTrafficSigns(it) },
                        onDone = { signs ->
                            CollectionDialog.clearSelection(data)
                            CollectionDialog.showLaneletCollection(
                                data = data,
                                onDone = { lanelets ->
                                    apply(data, refLine, signs.filterIsInstance<Way>(), lanelets, layer)
                                    ui.info(createdMessage(lanelets.size), TITLE)
                                },
                                title = "Traffic Sign - Step 3/3",
                                message = "Select lanelets or linestrings (lane boundaries). Linestrings infer lanelets (Mode B).",
                                minCount = 1,
                                helpTitle = "Traffic Sign - Lanelet2 Tagging",
                                helpText = HELP_TEXT,
                                helpLinks = HELP_LINKS,
                                ui = ui,
                            )
                        },
                        minCount = 1,
                        itemNamePlural = "traffic sign(s)",
                        highlight = "traffic sign(s)",
                        ui = ui,
                    )
                },
                highlight = "ref_line",
                helpTitle = "Traffic Sign - Lanelet2 Tagging",
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
        ui.info(createdMessage(lanelets.size), TITLE)
    }

    fun createdMessage(laneletCount: Int): String =
        "Created traffic sign regulatory element.\n" +
            "Added to $laneletCount lanelet(s).\n" +
            "Review in Properties dialog (Alt+O if not visible)."
}
