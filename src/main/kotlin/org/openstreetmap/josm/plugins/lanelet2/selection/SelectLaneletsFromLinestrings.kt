package org.openstreetmap.josm.plugins.lanelet2.selection

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.io.File
import java.util.ArrayList

/**
 * Shared keep/save/restore for the two select-from-linestrings actions.
 *
 * Port of `run_select_relations_flow` minus the collection dialog: operate on
 * the current selection, and if that is empty restore IDs saved in the tmp file
 * (the Jython's "on next run, reselect" behaviour).
 */
internal object SelectFromLinestrings {
    fun resolveCollected(
        data: DataSet,
        extracted: List<Relation>,
        relType: String,
        relSubtype: String?,
        path: File,
    ): List<Relation> {
        if (extracted.isNotEmpty()) return extracted
        val ids = SelectRelations.loadIdsFromFile(path)
        if (ids.isEmpty()) return emptyList()
        return SelectRelations.findRelationsByIds(data, ids, relType, relSubtype)
    }

    fun keepAndSave(
        data: DataSet,
        collected: List<Relation>,
        path: File,
        dialogTitle: String,
        ui: UserPrompts,
    ): Int {
        try {
            data.setSelected(ArrayList(collected))
        } catch (_: Exception) {
        }
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
        SelectRelations.saveRelationsToFile(collected, path)
        ui.infoAutoClose(
            "Kept ${collected.size} relation(s) selected. IDs saved to $path",
            dialogTitle,
            1000,
        )
        return collected.size
    }
}

/**
 * Select lanelets from the current selection (direct relations or inferred from
 * selected border linestrings) and persist IDs to `~/.lanelet2_selected_lanelets.txt`.
 *
 * Port of `select_lanelets_from_linestrings.py`. The collection wizard is not
 * ported; empty selection restores the previous tmp-file IDs.
 */
object SelectLaneletsFromLinestrings {
    const val TITLE = "Select Lanelets from Linestrings"
    const val DIALOG_TITLE = "Select Lanelets"

    fun apply(
        data: DataSet,
        extracted: List<Relation>,
        path: File = SelectRelations.getTmpFilePath("lanelet", null),
        ui: UserPrompts = Dialogs,
    ): Int {
        val collected = SelectFromLinestrings.resolveCollected(data, extracted, "lanelet", null, path)
        return SelectFromLinestrings.keepAndSave(data, collected, path, DIALOG_TITLE, ui)
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val extracted = LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected)
        apply(data, extracted, ui = ui)
    }
}
