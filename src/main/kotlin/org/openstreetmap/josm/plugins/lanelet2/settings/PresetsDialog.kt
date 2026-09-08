package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.tagging.presets.TaggingPreset
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.TaggingPresetsInstaller
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Tagging Presets settings sub-dialog. Port of `core/settings_ui/presets_dialog.py`. */
object PresetsDialog {
    fun show(parent: java.awt.Component? = null) {
        if (Dialogs.isHeadless()) return
        val owner = parent ?: try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        PresetsDialogImpl(owner).isVisible = true
    }
}

private class PresetsDialogImpl(parent: java.awt.Component?) : JDialog(
    parent as? java.awt.Frame,
    "Lanelet2 Tagging Presets",
    true,
) {
    private val model = PresetsTableModel()
    private val table: JTable
    private val btnInstall: JButton
    private val btnUninstall: JButton
    private val filterField: JTextField
    private val chkAuto: JCheckBox

    init {
        layout = BorderLayout()

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT))
        btnInstall = JButton("Install LL2 presets")
        btnInstall.toolTipText =
            "Register ll2_editor_presets.xml with JOSM (Presets menu + toolbar support)."
        btnInstall.addActionListener {
            if (TaggingPresetsInstaller.installPresets()) {
                applyDefaultToolbar()
            } else {
                TaggingPresetsInstaller.applyMinimalJosmToolbar()
                TaggingPresetsInstaller.syncToolbarFromSettings()
            }
            refresh()
        }
        btnUninstall = JButton("Uninstall LL2 presets")
        btnUninstall.toolTipText =
            "Remove bundled presets from JOSM and clear their toolbar buttons."
        btnUninstall.addActionListener {
            TaggingPresetsInstaller.uninstallPresets()
            refresh()
        }
        val btnRestoreTb = JButton("Restore JOSM default toolbar")
        btnRestoreTb.toolTipText =
            "Restore the full default JOSM main toolbar (OSM preset groups, etc.). " +
                "Keeps enabled LL2 preset toolbar buttons at the end."
        btnRestoreTb.addActionListener {
            TaggingPresetsInstaller.restoreJosmDefaultToolbar(preserveLl2Presets = true)
            refresh()
        }
        btnRow.add(btnInstall)
        btnRow.add(btnUninstall)
        btnRow.add(btnRestoreTb)
        top.add(btnRow)

        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT))
        filterRow.add(JLabel("Filter:"))
        filterField = JTextField(28)
        filterField.toolTipText = "Filter presets by name (toolbar toggles apply to visible rows)."
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        filterRow.add(filterField)
        top.add(filterRow)

        add(top, BorderLayout.NORTH)

        table = JTable(model)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.tableHeader.reorderingAllowed = false
        try {
            val col0 = table.columnModel.getColumn(0)
            col0.maxWidth = 72
            col0.headerValue = "Toolbar"
            col0.cellRenderer = ToggleButtonRenderer()
        } catch (_: Exception) {
        }
        try {
            table.columnModel.getColumn(1).headerValue = "Preset"
        } catch (_: Exception) {
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(evt: MouseEvent) {
                if (!TaggingPresetsInstaller.isInstalled()) return
                if (evt.button != MouseEvent.BUTTON1) return
                val row = table.rowAtPoint(evt.point)
                val col = table.columnAtPoint(evt.point)
                if (row < 0 || col != 0) return
                val presets = model.getPresets()
                if (row >= presets.size) return
                val preset = presets[row]
                TaggingPresetsInstaller.setOnToolbar(preset, !TaggingPresetsInstaller.isOnToolbar(preset))
                persistToolbarNames()
                refresh()
            }
        })
        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(520, 320)
        add(scroll, BorderLayout.CENTER)

        val bottom = JPanel()
        bottom.layout = BoxLayout(bottom, BoxLayout.Y_AXIS)
        val autoRow = JPanel(FlowLayout(FlowLayout.LEFT))
        chkAuto = JCheckBox(
            "Install LL2 presets when launcher runs",
            LaneletSettings.getPresetsAutoInstallOnLaunch(),
        )
        autoRow.add(chkAuto)
        bottom.add(autoRow)
        val btnRow2 = JPanel(FlowLayout(FlowLayout.RIGHT))
        val btnClose = JButton("Close")
        btnClose.addActionListener { closeDialog() }
        btnRow2.add(btnClose)
        bottom.add(btnRow2)
        add(bottom, BorderLayout.SOUTH)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                saveAutoInstall()
            }
        })

        setSize(680, 520)
        setLocationRelativeTo(parent)
        refresh()
    }

    private fun refresh() {
        val installed = TaggingPresetsInstaller.isInstalled()
        btnInstall.isEnabled = !installed
        btnUninstall.isEnabled = installed
        table.isEnabled = installed
        model.refresh()
        table.repaint()
    }

    private fun applyFilter() {
        model.setFilter(filterField.text)
        table.repaint()
    }

    private fun saveAutoInstall() {
        LaneletSettings.setPresetsAutoInstallOnLaunch(chkAuto.isSelected)
    }

    private fun persistToolbarNames() {
        val enabled = mutableListOf<String>()
        for (preset in model.getAllPresets()) {
            if (TaggingPresetsInstaller.isOnToolbar(preset)) {
                preset.name?.let { enabled.add(it) }
            }
        }
        LaneletSettings.setToolbarPresetNames(enabled)
    }

    private fun applyDefaultToolbar() {
        LaneletSettings.setToolbarPresetNames(LaneletSettings.DEFAULT_TOOLBAR_PRESET_NAMES)
        TaggingPresetsInstaller.applyMinimalJosmToolbar()
        TaggingPresetsInstaller.syncToolbarFromSettings()
    }

    private fun closeDialog() {
        saveAutoInstall()
        isVisible = false
        dispose()
    }
}

private class PresetsTableModel : AbstractTableModel() {
    private val headers = arrayOf("Toolbar", "Preset")
    private var allRows: List<TaggingPreset> = emptyList()
    private var rows: List<TaggingPreset> = emptyList()
    private var filter: String = ""

    fun getAllPresets(): List<TaggingPreset> = allRows.toList()

    fun getPresets(): List<TaggingPreset> = rows.toList()

    fun setFilter(text: String?) {
        filter = text?.trim()?.lowercase().orEmpty()
        applyFilter()
    }

    fun refresh() {
        allRows = TaggingPresetsInstaller.listEditorPresets()
            .sortedBy { presetLabel(it).lowercase() }
        applyFilter()
    }

    private fun applyFilter() {
        rows = if (filter.isEmpty()) {
            allRows.toList()
        } else {
            allRows.filter { presetMatchesFilter(it, filter) }
        }
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = headers[column]

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean.TYPE else Any::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= rows.size) return null
        val preset = rows[rowIndex]
        return if (columnIndex == 0) {
            TaggingPresetsInstaller.isOnToolbar(preset)
        } else {
            presetLabel(preset)
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

private class ToggleButtonRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): java.awt.Component {
        val on = value as? Boolean ?: false
        val btn = JToggleButton(if (on) "On" else "Off")
        btn.isSelected = on
        btn.isEnabled = table.isEnabled
        btn.isFocusable = false
        return btn
    }
}
