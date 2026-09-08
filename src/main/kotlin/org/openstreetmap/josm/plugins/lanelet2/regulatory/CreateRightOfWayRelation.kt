package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.RegulatoryElements
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts

/**
 * Create a `type=regulatory_element` / `subtype=right_of_way` relation.
 *
 * Tags: `type=regulatory_element`, `subtype=right_of_way`.
 * Roles: `yield` (all yield lanelets), then `right_of_way`, then optional `ref_line`.
 * Every yield and right-of-way lanelet also gets a `regulatory_element` member.
 *
 * One SequenceCommand. Port of `create_right_of_way_relation.py`.
 *
 * [run] drives the Jython 3-step wizard: optional ref_line, then the
 * right-of-way lanelets, then the yielding ones. It needs the collector
 * regardless of the opt-in, since a flat selection cannot separate the two
 * roles. [apply] remains the headless-testable create path.
 *
 * **lanelet2 C++:** `RightOfWay` emits `right_of_way` then `yield` then optional
 * `ref_line`. The Jython emits `yield` then `right_of_way` then `ref_line`.
 * Jython order is preserved.
 *
 * **Latent Jython bug (replicated in [apply]):** `all_lanelets = yield + right_of_way`
 * clones each lanelet from the *original*. A lanelet listed in both groups gets
 * two ChangeCommands; the second overwrites the first, so it still gains only
 * one `regulatory_element` member.
 */
object CreateRightOfWayRelation {
    const val TITLE = "Create Right of Way Relation"
    const val SEQUENCE_NAME = "Create right of way regulatory element"
    const val SUBTYPE = "right_of_way"

    const val HELP_TEXT = """Right of Way Regulatory Element (Lanelet2)

Tags: type=regulatory_element, subtype=right_of_way

Roles:
- yield: Lanelets that must yield.
- right_of_way: Lanelets that have priority over the yielding ones.
- ref_line (optional): Lines where yielding vehicles must stop. If absent, end of yield lanelet.

By default, intersecting lanelets are "first come first served". This element overrides that.
Only one lanelet per lane chain needs to be referenced (typically the last before the intersection).
All lanelets in the element must reference it."""

    val HELP_LINKS: List<Pair<String, String>> = listOf(
        "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
    )

    fun createdMessage(yieldCount: Int, rightOfWayCount: Int, hasRefLine: Boolean): String {
        var msg = "Created right of way regulatory element.\n"
        msg += "Yield: $yieldCount, Right of way: $rightOfWayCount"
        if (hasRefLine) msg += ", ref_line: yes"
        msg += "\n\nReview in Properties dialog (Alt+O if not visible)."
        return msg
    }

    fun buildRelation(
        refLine: Way?,
        rightOfWayLanelets: List<Relation>,
        yieldLanelets: List<Relation>,
    ): Relation {
        val rel = RegulatoryCreate.newRelation(SUBTYPE)
        for (ll in yieldLanelets) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_YIELD, ll))
        }
        for (ll in rightOfWayLanelets) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_RIGHT_OF_WAY, ll))
        }
        if (refLine != null) {
            rel.addMember(RelationMember(RegulatoryCreate.ROLE_REF_LINE, refLine))
        }
        return rel
    }

    fun apply(
        data: DataSet,
        refLine: Way?,
        rightOfWayLanelets: List<Relation>,
        yieldLanelets: List<Relation>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Relation {
        val rel = buildRelation(refLine, rightOfWayLanelets, yieldLanelets)
        // Jython: all_lanelets = yield_lanelets + right_of_way_lanelets (yield first).
        val allLanelets = yieldLanelets + rightOfWayLanelets
        return RegulatoryCreate.apply(data, rel, allLanelets, SEQUENCE_NAME, layer, undo)
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        if (!CollectionLogic.collectionDialogRequired()) {
            // Headless only: there is no window to run the three steps in, and
            // a flat selection cannot say which lanelets yield.
            val (_, err) = RegulatoryElements.extractRefLine(data.selected)
            ui.warn(err ?: "The right of way wizard needs a display.", TITLE)
            return
        }
        CollectionDialog.clearSelection(data)
        CollectionDialog.showStep(
            title = "Right of Way - Step 1/3",
            message = "Select the ref_line (stop line, type=stop_line).\n\n" +
                "Leave selection empty to skip.\nClick OK when done.",
            onOk = {
                val (refLine, err) = RegulatoryElements.extractRefLine(data.selected)
                if (err != null) {
                    ui.warn(err, TITLE)
                    return@showStep
                }
                CollectionDialog.clearSelection(data)
                CollectionDialog.showLaneletCollection(
                    data = data,
                    onDone = { rightOfWay ->
                        CollectionDialog.clearSelection(data)
                        CollectionDialog.showLaneletCollection(
                            data = data,
                            onDone = { yieldLanelets ->
                                apply(data, refLine, rightOfWay, yieldLanelets, layer)
                                ui.info(
                                    createdMessage(yieldLanelets.size, rightOfWay.size, refLine != null),
                                    TITLE,
                                )
                            },
                            title = "Right of Way - Step 3/3",
                            message = "Select lanelets or linestrings (lane boundaries) that have to YIELD.",
                            minCount = 1,
                            helpTitle = "Right of Way - Lanelet2 Tagging",
                            helpText = HELP_TEXT,
                            helpLinks = HELP_LINKS,
                            ui = ui,
                        )
                    },
                    title = "Right of Way - Step 2/3",
                    message = "Select lanelets or linestrings (lane boundaries) that have RIGHT OF WAY.",
                    minCount = 1,
                    helpTitle = "Right of Way - Lanelet2 Tagging",
                    helpText = HELP_TEXT,
                    helpLinks = HELP_LINKS,
                    ui = ui,
                )
            },
            highlight = "ref_line",
            helpTitle = "Right of Way - Lanelet2 Tagging",
            helpText = HELP_TEXT,
            helpLinks = HELP_LINKS,
        )
    }
}
