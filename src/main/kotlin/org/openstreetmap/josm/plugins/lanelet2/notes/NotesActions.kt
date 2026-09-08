package org.openstreetmap.josm.plugins.lanelet2.notes

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.tools.Logging
import java.awt.event.ActionEvent

/**
 * Registers `notes.notes_dialog` into [ActionRegistry] and installs the
 * dockable [NotesToggleDialog] on the [MapFrame].
 *
 * Slot id is already in [ActionRegistry.BASE_UTILS_ORDER]. Metadata matches
 * `core/launcher/registry.py`.
 */
object NotesActions {
    const val SLOT_ID = "notes.notes_dialog"
    const val DISPLAY_NAME = "Notes (custom geolocated notes)"
    const val TOOLBAR_LABEL = "Notes"
    const val ICON_PATH = "icons/notes.svg"

    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(SLOT_ID to MenuId.UTILS)

    private var dialog: NotesToggleDialog? = null
    private var installedOn: MapFrame? = null

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = SLOT_ID,
            displayName = DISPLAY_NAME,
            shortcutKey = null,
            toolbarLabel = TOOLBAR_LABEL,
            iconPath = ICON_PATH,
            menu = MenuId.UTILS,
        ) { run() },
    )

    fun installDialog(frame: MapFrame) {
        if (dialog != null && installedOn === frame) return
        uninstallDialog()
        try {
            val dlg = NotesToggleDialog()
            frame.addToggleDialog(dlg)
            dialog = dlg
            installedOn = frame
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to install notes dialog")
            Logging.error(e)
        }
    }

    fun uninstallDialog() {
        val dlg = dialog
        val frame = installedOn
        dialog = null
        installedOn = null
        if (dlg == null) return
        try {
            dlg.hideNotify()
        } catch (_: Exception) {
        }
        if (frame != null) {
            try {
                frame.removeToggleDialog(dlg)
            } catch (_: Exception) {
            }
        }
        try {
            dlg.destroy()
        } catch (_: Exception) {
        }
    }

    fun run() {
        val map = try {
            MainApplication.getMap()
        } catch (_: Exception) {
            null
        }
        if (map == null) {
            Dialogs.warn(
                "Open a map first (File -> New or open an .osm file).",
                PANEL_NAME,
            )
            return
        }
        if (dialog == null || installedOn !== map) {
            installDialog(map)
        }
        val dlg = dialog ?: return
        activateNotesPanel(dlg, map)
        dlg.ensureManager()
        dlg.reloadRows()
    }

    private fun slot(
        id: String,
        displayName: String,
        shortcutKey: String?,
        toolbarLabel: String?,
        iconPath: String?,
        menu: MenuId,
        run: () -> Unit,
    ): ActionSlot {
        val action = object : LaneletAction(displayName, iconPath, displayName, shortcutKey) {
            override fun actionPerformed(e: ActionEvent) = run()
        }
        return ActionSlot(
            id = id,
            action = action,
            toolbarLabel = toolbarLabel,
            iconName = iconPath,
            menu = menu,
        )
    }
}
