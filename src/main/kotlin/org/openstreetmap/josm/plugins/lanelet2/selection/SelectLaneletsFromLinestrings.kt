package org.openstreetmap.josm.plugins.lanelet2.selection

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.io.File
import java.util.ArrayList

/**
 * Shared keep/save/restore for the two select-from-linestrings actions.
 *
 * Port of `run_select_relations_flow`. When the collection-dialog setting is
 * off, operate on the current selection (empty selection restores IDs saved
 * in the tmp file). When it is on, the collection dialog is seeded from that
 * file and [keepAndSave] runs on Done.
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

    fun initialFromFile(
        data: DataSet,
        relType: String,
        relSubtype: String?,
        path: File,
    ): List<Relation> {
        val ids = SelectRelations.loadIdsFromFile(path)
        if (ids.isEmpty()) return emptyList()
        val loaded = SelectRelations.findRelationsByIds(data, ids, relType, relSubtype)
        if (loaded.isNotEmpty()) {
            try {
                data.setSelected(ArrayList(loaded))
            } catch (_: Exception) {
            }
            try {
                MainApplication.getMap()?.mapView?.repaint()
            } catch (_: Exception) {
            }
        }
        return loaded
    }
}

/**
 * Select lanelets (direct relations or inferred from border linestrings) and
 * persist IDs to `~/.lanelet2_selected_lanelets.txt`.
 *
 * Port of `select_lanelets_from_linestrings.py`. With
 * [org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings.isCollectionDialogEnabled]
 * the Jython collection dialog is used; otherwise the current selection
 * (or the previous tmp-file IDs) is applied immediately.
 */
object SelectLaneletsFromLinestrings {
    const val TITLE = "Select Lanelets from Linestrings"
    const val DIALOG_TITLE = "Select Lanelets"

    const val HELP_TEXT = """Select Lanelets from Linestrings (Lanelet2)

Lanelets are relations with type=lanelet. Each has left and right bounds (linestrings).
This script infers lanelets from selected linestrings: any lanelet that uses a selected
linestring as its left or right bound is included.

Linestrings: Lane boundaries (line_thin/dashed, curbstone, road_border, etc.),
traffic signs (type=traffic_sign), traffic lights (type=traffic_light).
See LinestringTagging for types. Lanelets use subtype (road, highway, etc.) and location (urban, nonurban)."""

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
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        if (CollectionLogic.shouldOpenCollectionDialog()) {
            val path = SelectRelations.getTmpFilePath("lanelet", null)
            val initial = SelectFromLinestrings.initialFromFile(data, "lanelet", null, path)
            CollectionDialog.showRelationCollection(
                data = data,
                onDone = { collected -> apply(data, collected, path, ui) },
                relType = "lanelet",
                relSubtype = null,
                title = TITLE,
                message = "Select linestrings (lane boundaries) or lanelets. Add / Select / Done.",
                minCount = 0,
                initialCollected = initial,
                helpTitle = "Select Lanelets - Lanelet2 Tagging",
                helpText = HELP_TEXT,
                helpLinks = CollectionLogic.DEFAULT_LANELET_HELP_LINKS,
                ui = ui,
            )
            return
        }
        val extracted = LaneletSelection.extractLaneletsOrFromLinestrings(data, data.selected)
        apply(data, extracted, ui = ui)
    }
}
