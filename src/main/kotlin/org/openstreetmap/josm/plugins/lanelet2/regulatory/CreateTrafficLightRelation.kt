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
 * Create a `type=regulatory_element` / `subtype=traffic_light` relation.
 *
 * Tags: `type=regulatory_element`, `subtype=traffic_light`.
 * Roles: `ref_line` (stop line, required), then `refers` (traffic lights).
 * Each affected lanelet gets a `regulatory_element` member.
 *
 * One SequenceCommand. Port of `create_traffic_light_relation.py`.
 *
 * With the collection-dialog setting, [run] is the Jython 3-step wizard
 * (stop line, traffic lights, lanelets). Off: current selection.
 *
 * **lanelet2 C++:** `TrafficLight` puts `refers` first and treats the stop line
 * (`ref_line`) as optional. The Jython requires a stop line and emits `ref_line`
 * before `refers`. That order and the required stop line are preserved.
 *
 * **Latent Jython bug (replicated in spirit):** `extract_traffic_lights` was
 * `t and t.lower()=="traffic_light" or t.lower()=="traffic_light_bicycle" or ...`
 * so a missing `type` would throw. Null types are skipped here (same observable
 * result on tagged ways); the three type strings are still accepted.
 */
object CreateTrafficLightRelation {
    const val TITLE = "Create Traffic Light Relation"
    const val SEQUENCE_NAME = "Create traffic light regulatory element"
    const val SUBTYPE = "traffic_light"

    const val HELP_TEXT = """Traffic Light Regulatory Element (Lanelet2)

Tags: type=regulatory_element, subtype=traffic_light

Roles:
- ref_line: Stop line (type=stop_line) where vehicles stop. If absent, implicitly at lanelet end.
- refers: Traffic light(s) (type=traffic_light) that vehicles must observe.

Traffic lights: type=traffic_light, optionally subtype (red_yellow_green, red_yellow, red, etc.).
Stop line: type=stop_line (symbol on road).

All affected lanelets must reference this regulatory element."""

    val HELP_LINKS: List<Pair<String, String>> = listOf(
        "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
        "LinestringTagging" to "LinestringTagging.md",
    )

    /**
     * Ways tagged `traffic_light`, `traffic_light_bicycle`, or
     * `traffic_light_pedestrian`. Note the convert action uses
     * `traffic_light_bikes` (different string) — that mismatch is in the Jython.
     */
    fun extractTrafficLights(selection: Iterable<OsmPrimitive?>): List<Way> {
        return RegulatoryCreate.extractWaysByType(
            selection,
            setOf("traffic_light", "traffic_light_bicycle", "traffic_light_pedestrian"),
        )
    }

    fun buildRelation(stopLine: Way, trafficLights: List<Way>): Relation {
        val rel = RegulatoryCreate.newRelation(SUBTYPE)
        rel.addMember(RelationMember(RegulatoryCreate.ROLE_REF_LINE, stopLine))
        for (tl in trafficLights) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_REFERS, tl))
        }
        return rel
    }

    fun apply(
        data: DataSet,
        stopLine: Way,
        trafficLights: List<Way>,
        lanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Relation {
        val rel = buildRelation(stopLine, trafficLights)
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
                title = "Traffic Light - Step 1/3",
                message = "Select the ref_line (stop line, type=stop_line).\n\nClick OK when done.",
                onOk = {
                    val (stopLine, err) = RegulatoryElements.extractStopLine(data.selected)
                    if (err != null || stopLine == null) {
                        ui.warn(err ?: "Select exactly 1 stop line (type=stop_line).", TITLE)
                        return@showStep
                    }
                    CollectionDialog.clearSelection(data)
                    CollectionDialog.showStepMulti(
                        title = "Traffic Light - Step 2/3",
                        message = "Select traffic lights (type=traffic_light).\n\nClick Add to add them, Done when finished.",
                        data = data,
                        extract = { extractTrafficLights(it) },
                        onDone = { lights ->
                            CollectionDialog.clearSelection(data)
                            CollectionDialog.showLaneletCollection(
                                data = data,
                                onDone = { lanelets ->
                                    apply(data, stopLine, lights.filterIsInstance<Way>(), lanelets, layer)
                                    ui.info(createdMessage(lanelets.size), TITLE)
                                },
                                title = "Traffic Light - Step 3/3",
                                message = "Select lanelets or linestrings (lane boundaries). Linestrings infer lanelets (Mode B).",
                                minCount = 1,
                                helpTitle = "Traffic Light - Lanelet2 Tagging",
                                helpText = HELP_TEXT,
                                helpLinks = HELP_LINKS,
                                ui = ui,
                            )
                        },
                        minCount = 1,
                        itemNamePlural = "traffic light(s)",
                        highlight = "traffic lights",
                        ui = ui,
                    )
                },
                highlight = "ref_line",
                helpTitle = "Traffic Light - Lanelet2 Tagging",
                helpText = HELP_TEXT,
                helpLinks = HELP_LINKS,
            )
            return
        }
        val selection = data.selected
        val (stopLine, err) = RegulatoryElements.extractStopLine(selection)
        if (err != null || stopLine == null) {
            ui.warn(err ?: "Select exactly 1 stop line (type=stop_line).", TITLE)
            return
        }
        val lights = extractTrafficLights(selection)
        if (lights.isEmpty()) {
            ui.warn("Select at least 1 traffic light (type=traffic_light).", TITLE)
            return
        }
        val lanelets = LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
        if (lanelets.isEmpty()) {
            ui.warn("Select at least one lanelet (or its border linestrings).", TITLE)
            return
        }
        apply(data, stopLine, lights, lanelets, layer)
        ui.info(createdMessage(lanelets.size), TITLE)
    }

    fun createdMessage(laneletCount: Int): String =
        "Created traffic light regulatory element.\n" +
            "Added to $laneletCount lanelet(s).\n" +
            "Review in Properties dialog (Alt+O if not visible)."
}
