package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.actions.SaveAction
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendScripts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.Sidecar
import org.openstreetmap.josm.tools.Logging
import java.io.File

/**
 * Make Positive IDs. Port of `ll2_make_positive_ids.py`.
 *
 * The backend always load+writes through lanelet2, so `positive_ids -i`
 * reformats the file even when every id is already positive. That is
 * upstream-writer behaviour, not a Jython bug; we keep passing `-i`.
 *
 * Data modification is a file overwrite + layer reload, matching the Jython
 * (no JOSM Command, so the conversion is not an undo step). The Jython tried
 * `addLayer(new, old_idx)` as a positional insert; see [OsmIo.reloadLayerFromFile].
 */
object MakePositiveIds {
    const val TITLE = "Make Positive IDs"

    fun inplaceArgs(filePath: String): List<String> = listOf("-i", filePath)

    fun convertFile(
        filePath: String,
        runner: BackendRunner = Sidecar.runner(),
    ): BackendResult = runner.run(BackendScripts.POSITIVE_IDS, inplaceArgs(filePath))

    fun run(ui: UserPrompts = Dialogs) {
        if (!Sidecar.ensureUsable(TITLE, ui)) return
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val assoc = layer.associatedFile
        if (assoc == null || !assoc.exists()) {
            ui.warn(
                "The active layer has no associated file. Save the map to a .osm file first.",
                TITLE,
            )
            return
        }
        val filePath = assoc.absolutePath
        saveIfDirty(layer)
        Thread {
            val result = convertFile(filePath)
            javax.swing.SwingUtilities.invokeLater {
                onDone(ui, layer, filePath, result)
            }
        }.apply { isDaemon = true; name = "lanelet2-positive-ids" }.start()
    }

    internal fun onDone(ui: UserPrompts, layer: OsmDataLayer, filePath: String, result: BackendResult) {
        if (!result.success) {
            ui.error("Make positive IDs failed:\n${result.error ?: "Unknown error"}", TITLE)
            return
        }
        if (!File(filePath).isFile) {
            ui.error("Output file not found: $filePath", TITLE)
            return
        }
        val ok = try {
            OsmIo.reloadLayerFromFile(layer, File(filePath))
        } catch (e: Exception) {
            Logging.warn("lanelet2: reload after positive ids failed: {0}", e.message)
            false
        }
        if (ok) {
            ui.infoAutoClose("Converted IDs to positive. Reloaded layer.", TITLE, 2000)
        } else {
            ui.warn(
                "Conversion succeeded but failed to reload layer. Reload the file manually.",
                TITLE,
            )
        }
    }

    private fun saveIfDirty(layer: OsmDataLayer) {
        try {
            if (layer.requiresSaveToFile()) {
                SaveAction.getInstance().doSave(layer, true)
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: save before positive ids failed: {0}", e.message)
        }
    }
}
