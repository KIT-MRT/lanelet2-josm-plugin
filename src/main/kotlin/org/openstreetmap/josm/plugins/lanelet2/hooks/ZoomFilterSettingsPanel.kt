package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

/**
 * Zoom-filter widgets + persist path shared by [ZoomFilterWindow] and the
 * native preferences tab. Writes through [ZoomFilterHook.saveConfig] and
 * [ZoomFilterHook.reinstallFromSettings].
 */
class ZoomFilterSettingsPanel internal constructor(
    val component: JPanel,
    val enabledBox: JCheckBox,
    val spinner: JSpinner,
    val table: JTable,
    val model: DefaultTableModel,
) {
    fun save() {
        val enabled = enabledBox.isSelected
        val threshold = try {
            (spinner.value as Number).toDouble()
        } catch (_: Exception) {
            ZoomFilterLogic.DEFAULT_THRESHOLD
        }
        ZoomFilterHook.saveConfig(enabled, threshold, readFilters())
        ZoomFilterHook.reinstallFromSettings()
    }

    fun readFilters(): List<ZoomFilterLogic.ZoomFilterSpec> =
        ZoomFilterWindow.readFilters(model, table)

    fun setThreshold(value: Double) {
        spinner.value = value
    }

    fun threshold(): Double = (spinner.value as Number).toDouble()

    companion object {
        const val ID = "zoomfilter"
        val KEYS = setOf(
            LaneletSettings.KEY_ZOOMFILTER_ENABLED,
            LaneletSettings.KEY_ZOOMFILTER_THRESHOLD,
            LaneletSettings.KEY_ZOOMFILTER_FILTERS,
        )

        fun create(): ZoomFilterSettingsPanel {
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
            return ZoomFilterSettingsPanel(panel, enabledBox, spinner, table, model)
        }
    }

    private class FiltersTableModel : DefaultTableModel(
        arrayOf("Enabled", "Hide", "Invert", "Filter (JOSM search)"),
        0,
    ) {
        override fun getColumnClass(col: Int): Class<*> =
            if (col < 3) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, col: Int): Boolean = true
    }
}
