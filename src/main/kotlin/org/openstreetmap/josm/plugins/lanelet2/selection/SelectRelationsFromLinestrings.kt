package org.openstreetmap.josm.plugins.lanelet2.selection

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.plugins.lanelet2.edit.TypeSubtype
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.io.File

/**
 * Select relations of a chosen type/subtype from the current selection (direct
 * or inferred from member ways) and persist IDs to
 * `~/.lanelet2_selected_relations_{type}_{subtype}.txt`.
 *
 * Port of `select_relations_from_linestrings.py`. Type/subtype is collected via
 * [TypeSubtype.prompt] (same lists as the Jython dialog). The collection wizard
 * is not ported; empty selection restores the previous tmp-file IDs.
 */
object SelectRelationsFromLinestrings {
    const val TITLE = "Select Relations from Linestrings"
    const val TYPE_DIALOG_TITLE = "Select Relation Type"
    const val DIALOG_TITLE = "Select Relations"

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
        val extracted = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, choice.type, choice.subtype,
        )
        apply(data, extracted, choice.type, choice.subtype, ui = ui)
    }
}
