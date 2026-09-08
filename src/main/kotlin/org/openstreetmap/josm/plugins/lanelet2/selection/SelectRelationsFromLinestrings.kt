package org.openstreetmap.josm.plugins.lanelet2.selection

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.plugins.lanelet2.edit.TypeSubtype
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.io.File

/**
 * Select relations of a chosen type/subtype (direct or inferred from member
 * ways) and persist IDs to
 * `~/.lanelet2_selected_relations_{type}_{subtype}.txt`.
 *
 * Port of `select_relations_from_linestrings.py`. Type/subtype is collected via
 * [TypeSubtype.prompt]. With the collection-dialog setting the Jython collector
 * is used; otherwise the current selection (or previous tmp-file IDs) is applied.
 */
object SelectRelationsFromLinestrings {
    const val TITLE = "Select Relations from Linestrings"
    const val TYPE_DIALOG_TITLE = "Select Relation Type"
    const val DIALOG_TITLE = "Select Relations"

    const val HELP_TEXT = """Select Relations from Linestrings (Lanelet2)

Relations (lanelets, regulatory elements, areas) reference ways as members.
This script finds relations that contain your selected ways.

Examples:
- Select traffic light ways or stop lines -> find traffic_light regulatory elements
- Select lane boundary linestrings -> find lanelets
- Select outer/inner ways -> find multipolygon areas

Choose type and optionally subtype from the list, or enter custom values.
See RegulatoryElementTagging, LaneletAndAreaTagging, LinestringTagging for details."""

    /** Matches `RELATION_TYPES` in the Jython (lanelet first). */
    val RELATION_TYPES: List<String> = listOf(
        "lanelet", "regulatory_element", "multipolygon", "area", "Custom",
    )

    fun apply(
        data: DataSet,
        extracted: List<Relation>,
        relType: String,
        relSubtype: String?,
        path: File = SelectRelations.getTmpFilePath(relType, relSubtype),
        ui: UserPrompts = Dialogs,
    ): Int {
        val collected = SelectFromLinestrings.resolveCollected(data, extracted, relType, relSubtype, path)
        return SelectFromLinestrings.keepAndSave(data, collected, path, DIALOG_TITLE, ui)
    }

    fun run(ui: UserPrompts = Dialogs, typeSubtype: TypeSubtype.Choice? = null) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val choice = typeSubtype
            ?: TypeSubtype.prompt(ui, RELATION_TYPES, TYPE_DIALOG_TITLE)
            ?: return
        if (CollectionLogic.shouldOpenCollectionDialog()) {
            val path = SelectRelations.getTmpFilePath(choice.type, choice.subtype)
            val initial = SelectFromLinestrings.initialFromFile(data, choice.type, choice.subtype, path)
            val subtypeStr = choice.subtype ?: "(any)"
            CollectionDialog.showRelationCollection(
                data = data,
                onDone = { collected -> apply(data, collected, choice.type, choice.subtype, path, ui) },
                relType = choice.type,
                relSubtype = choice.subtype,
                title = "Select Relations: ${choice.type} / $subtypeStr",
                message = "Select ways (e.g. traffic lights, stop lines) or relations of type ${choice.type}.",
                minCount = 0,
                initialCollected = initial,
                helpTitle = "Select Relations - Lanelet2 Tagging",
                helpText = HELP_TEXT,
                helpLinks = listOf(
                    "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
                    "LaneletAndAreaTagging" to "LaneletAndAreaTagging.md",
                    "LinestringTagging" to "LinestringTagging.md",
                ),
                ui = ui,
            )
            return
        }
        val extracted = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, choice.type, choice.subtype,
        )
        apply(data, extracted, choice.type, choice.subtype, ui = ui)
    }
}
