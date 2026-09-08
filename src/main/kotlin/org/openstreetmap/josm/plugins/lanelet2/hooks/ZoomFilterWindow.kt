package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import javax.swing.JOptionPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Configuration UI from `zoom_filter_window.py`. Slot `hooks.zoom_filter_window`.
 */
object ZoomFilterWindow {
    const val TITLE = "Zoom Filter Hook"

    fun run() {
        if (Dialogs.isHeadless()) return
        val parent = Dialogs.parent()
        val panel = ZoomFilterSettingsPanel.create()
        val result = JOptionPane.showConfirmDialog(
            parent, panel.component, TITLE,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return
        panel.save()
        val enabled = panel.enabledBox.isSelected
        val threshold = panel.threshold()
        val filters = panel.readFilters()
        val msg = if (enabled) {
            val n = filters.count { it.enabled }
            "Zoom filtering ON. At zoom <= ${formatG(threshold)}, $n filter(s) are enabled."
        } else {
            "Zoom filtering OFF."
        }
        Dialogs.infoAutoClose(msg, TITLE, 2500)
    }

    /** Python `%g` for the confirmation toast. */
    internal fun formatG(value: Double): String {
        val s = String.format(java.util.Locale.US, "%g", value)
        return s
    }

    internal fun readFilters(model: DefaultTableModel, table: JTable): List<ZoomFilterLogic.ZoomFilterSpec> {
        try {
            if (table.isEditing) {
                table.cellEditor.stopCellEditing()
            }
        } catch (_: Exception) {
        }
        val filters = ArrayList<ZoomFilterLogic.ZoomFilterSpec>()
        for (r in 0 until model.rowCount) {
            val text = (model.getValueAt(r, 3)?.toString() ?: "").trim()
            if (text.isEmpty()) continue
            filters.add(
                ZoomFilterLogic.ZoomFilterSpec(
                    enabled = ZoomFilterLogic.truthy(model.getValueAt(r, 0)),
                    hiding = ZoomFilterLogic.truthy(model.getValueAt(r, 1)),
                    inverted = ZoomFilterLogic.truthy(model.getValueAt(r, 2)),
                    text = text,
                ),
            )
        }
        return filters
    }
}
