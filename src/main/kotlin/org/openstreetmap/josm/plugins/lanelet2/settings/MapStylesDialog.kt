package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles.MapPaintStylesUpdateListener
import org.openstreetmap.josm.gui.mappaint.StyleSource
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.MapStyles
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Map Styles settings sub-dialog. Port of `core/settings_ui/map_styles_dialog.py`. */
object MapStylesDialog {
    fun show(parent: java.awt.Component? = null) {
        if (Dialogs.isHeadless()) return
        val owner = parent ?: try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        MapStylesDialogImpl(owner).isVisible = true
    }
}

private class MapStylesDialogImpl(parent: java.awt.Component?) : JDialog(
    parent as? java.awt.Frame,
    "Lanelet2 Map Styles",
    true,
) {
    private val listener = StylesUpdateListener(this)
    private val model = StylesTableModel()
    private var building = false
    private val table: JTable
    private val chkAuto: JCheckBox

    init {
        layout = BorderLayout()

        val top = JPanel(FlowLayout(FlowLayout.LEFT))
        val btnRestore = JButton("Restore LL2 editing defaults")
        btnRestore.toolTipText =
            "Enable LL2_Lines_Style + Lanelet2 Notes + Lanelet2 File Boundaries; " +
                "disable all other styles; restore shipped catalog order."
        btnRestore.addActionListener {
            MapStyles.applyPreset(LaneletSettings.MAPSTYLE_PRESET_LL2_EDITING)
            refreshTable()
        }
        top.add(btnRestore)
        add(top, BorderLayout.NORTH)

        table = JTable(model)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.tableHeader = null
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(evt: MouseEvent) {
                if (evt.button != MouseEvent.BUTTON1) return
                val row = table.rowAtPoint(evt.point)
                val col = table.columnAtPoint(evt.point)
                if (row < 0 || col != 0) return
                if (MapStyles.toggleStyleAtIndex(row)) refreshTable()
            }
        })
        try {
            val col0 = table.columnModel.getColumn(0)
            col0.maxWidth = 28
            col0.setResizable(false)
            col0.cellRenderer = CheckBoxRenderer()
        } catch (_: Exception) {
        }
        table.columnModel.getColumn(1).cellRenderer = StyleTitleRenderer()
        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(460, 280)
        add(scroll, BorderLayout.CENTER)

        val side = JPanel()
        side.layout = BoxLayout(side, BoxLayout.Y_AXIS)
        val btnUp = JButton("Up")
        btnUp.addActionListener { moveSelected(-1) }
        val btnDown = JButton("Down")
        btnDown.addActionListener { moveSelected(1) }
        side.add(btnUp)
        side.add(btnDown)
        add(side, BorderLayout.EAST)

        val bottom = JPanel()
        bottom.layout = BoxLayout(bottom, BoxLayout.Y_AXIS)
        val autoRow = JPanel(FlowLayout(FlowLayout.LEFT))
        chkAuto = JCheckBox(
            "Apply LL2 editing defaults when launcher runs",
            LaneletSettings.getMapstyleAutoApplyOnLaunch(),
        )
        autoRow.add(chkAuto)
        bottom.add(autoRow)
        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT))
        val btnClose = JButton("Close")
        btnClose.addActionListener { closeDialog() }
        btnRow.add(btnClose)
        bottom.add(btnRow)
        add(bottom, BorderLayout.SOUTH)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                saveAutoApply()
                destroyListener()
            }
        })

        setSize(560, 420)
        setLocationRelativeTo(parent)
        refreshTable()
    }

    private fun refreshTable() {
        building = true
        try {
            model.refresh()
            table.repaint()
        } finally {
            building = false
        }
    }

    private fun onStylesUpdated() {
        if (building) return
        refreshTable()
    }

    private fun saveAutoApply() {
        LaneletSettings.setMapstyleAutoApplyOnLaunch(chkAuto.isSelected)
    }

    private fun destroyListener() {
        try {
            MapPaintStyles.removeMapPaintStylesUpdateListener(listener)
        } catch (_: Exception) {
        }
    }

    private fun closeDialog() {
        saveAutoApply()
        destroyListener()
        isVisible = false
        dispose()
    }

    private fun moveSelected(delta: Int) {
        val row = table.selectedRow
        if (row < 0) return
        try {
            val sel = intArrayOf(row)
            if (MapPaintStyles.canMoveStyles(sel, delta)) {
                MapPaintStyles.moveStyles(sel, delta)
                val newRow = row + delta
                table.selectionModel.setSelectionInterval(newRow, newRow)
                LaneletSettings.setMapstyleLastPreset(LaneletSettings.MAPSTYLE_PRESET_CUSTOM)
            }
        } catch (_: Exception) {
        }
        refreshTable()
    }

    private inner class StylesUpdateListener(private val dialog: MapStylesDialogImpl) :
        MapPaintStylesUpdateListener {
        init {
            MapPaintStyles.addMapPaintStylesUpdateListener(this)
        }

        override fun mapPaintStylesUpdated() {
            dialog.onStylesUpdated()
        }

        override fun mapPaintStyleEntryUpdated(index: Int) {
            dialog.onStylesUpdated()
        }
    }
}

private class StylesTableModel : AbstractTableModel() {
    private var rows: List<StyleSource> = emptyList()

    fun refresh() {
        rows = MapStyles.getCurrentStyleRows()
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 2

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean.TYPE else StyleSource::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= rows.size) return null
        val src = rows[rowIndex]
        return if (columnIndex == 0) {
            val sources = MapStyles.getCurrentStyleRows()
            if (rowIndex < sources.size) sources[rowIndex].active else src.active
        } else {
            src
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

private class CheckBoxRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): java.awt.Component {
        val cb = JCheckBox()
        cb.horizontalAlignment = JCheckBox.CENTER
        cb.isSelected = value as? Boolean ?: false
        cb.isEnabled = table.isEnabled
        cb.background = table.background
        return cb
    }
}

private class StyleTitleRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): java.awt.Component {
        val label = super.getTableCellRendererComponent(
            table,
            value,
            isSelected,
            hasFocus,
            row,
            column,
        ) as DefaultTableCellRenderer
        if (value !is StyleSource) return label
        label.text = try {
            value.displayString
        } catch (_: Exception) {
            value.toString()
        }
        try {
            val icon = value.getIcon()
            if (icon != null) label.icon = icon
        } catch (_: Exception) {
        }
        return label
    }
}
