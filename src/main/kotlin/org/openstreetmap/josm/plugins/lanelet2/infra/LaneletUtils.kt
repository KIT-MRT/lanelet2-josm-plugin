package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import java.awt.KeyboardFocusManager
import javax.swing.text.JTextComponent

/**
 * Shared JOSM access helpers used by editing actions.
 *
 * Port of `lanelet2_utils.py` minus `show_message_auto_close` (GUI dialogs
 * are a separate task).
 */
object LaneletUtils {
    fun getEditLayer(): OsmDataLayer? = MainApplication.getLayerManager().editLayer

    fun getUndo(): UndoRedoHandler = UndoRedoHandler.getInstance()

    /** True when keyboard focus is in a Swing text field/area (user is typing). */
    fun focusInTextComponent(): Boolean {
        return try {
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            focused is JTextComponent
        } catch (_: Exception) {
            false
        }
    }
}
