package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

/**
 * Configuration UI from `zoom_filter_window.py`. Slot `hooks.zoom_filter_window`.
 */
object ZoomFilterWindow {
    const val TITLE = "Zoom Filter Hook"

    fun run() {
        if (Dialogs.isHeadless()) return
        val parent = Dialogs.parent()
        val (panel, enabledBox, spinner, table, model) = buildPanel()
        val result = JOptionPane.showConfirmDialog(
            parent, panel, TITLE,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return
        val enabled = enabledBox.isSelected
        val threshold = try {
            (spinner.value as Number).toDouble()
        } catch (_: Exception) {
            ZoomFilterLogic.DEFAULT_THRESHOLD
        }
        val filters = readFilters(model, table)
        ZoomFilterHook.saveConfig(enabled, threshold, filters)
        ZoomFilterHook.reinstallFromSettings()
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

    private fun buildPanel(): PanelBits {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val enabledBox = JCheckBox("Enable zoom-based filtering", ZoomFilterHook.isEnabled())
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        top.add(JLabel("Apply filters at zoom level <="))
        val spinner = JSpinner(
            SpinnerNumberModel(ZoomFilterHook.getThreshold(), 1.0, 25.0, 1.0),
        )
        spinner.preferredSize = Dimension(70, 26)
        top.add(spinner)
        val north = JPanel(BorderLayout(4, 4))
        north.add(enabledBox, BorderLayout.NORTH)
        north.add(top, BorderLayout.SOUTH)
        panel.add(north, BorderLayout.NORTH)
        val model = FiltersTableModel()
        for (entry in ZoomFilterHook.getFiltersConfig()) {
            model.addRow(
                arrayOf<Any>(
                    entry.enabled,
                    entry.hiding,
                    entry.inverted,
                    entry.text,
                ),
            )
        }
        val table = JTable(model)
        table.columnModel.getColumn(0).maxWidth = 70
        table.columnModel.getColumn(1).maxWidth = 60
        table.columnModel.getColumn(2).maxWidth = 60
        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(460, 150)
        panel.add(scroll, BorderLayout.CENTER)
        val south = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val newText = JTextField(22)
        newText.toolTipText = "e.g. type:node  or  highway=residential"
        south.add(JLabel("Filter:"))
        south.add(newText)
        val addBtn = JButton("Add (Enable+Hide)")
        addBtn.addActionListener {
            val txt = newText.text.trim()
            if (txt.isEmpty()) return@addActionListener
            model.addRow(arrayOf<Any>(true, true, false, txt))
            newText.text = ""
        }
        south.add(addBtn)
        val removeBtn = JButton("Remove selected")
        removeBtn.addActionListener {
            val rows = table.selectedRows.sortedDescending()
            for (r in rows) {
                if (r in 0 until model.rowCount) model.removeRow(r)
            }
        }
        south.add(removeBtn)
        panel.add(south, BorderLayout.SOUTH)
        return PanelBits(panel, enabledBox, spinner, table, model)
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

    private data class PanelBits(
        val panel: JPanel,
        val enabledBox: JCheckBox,
        val spinner: JSpinner,
        val table: JTable,
        val model: DefaultTableModel,
    )

    private class FiltersTableModel : DefaultTableModel(
        arrayOf("Enabled", "Hide", "Invert", "Filter (JOSM search)"),
        0,
    ) {
        override fun getColumnClass(col: Int): Class<*> =
            if (col < 3) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, col: Int): Boolean = true
    }
}
