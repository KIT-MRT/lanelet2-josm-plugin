package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
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
 * The Jython 3-step collection wizard is not ported. [run] cannot split "right of
 * way" vs "yield" from a mixed selection, so it is a no-op with a warning unless
 * callers use [apply] (tests / future wizard). Interactive users should wait for
 * the collection dialog; until then this action only reports that the wizard is
 * unavailable and does not guess roles.
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
        val selection = data.selected
        val (_, err) = RegulatoryElements.extractRefLine(selection)
        if (err != null) {
            ui.warn(err, TITLE)
            return
        }
        // Without the collection dialog there is no way to collect two distinct
        // lanelet groups (right_of_way vs yield) from one selection. Do not guess.
        // [apply] / [buildRelation] are the headless-testable port of do_create_relation.
        ui.warn(
            "The Right of Way wizard (separate right-of-way vs yield lanelet steps) " +
                "is not available yet. Creating the relation needs the collection dialog.",
            TITLE,
        )
    }
}
