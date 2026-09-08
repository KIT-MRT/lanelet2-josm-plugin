package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Configuration UI from `autotag_new_elements.py`. Slot
 * `hooks.autotag_new_elements`.
 */
object AutotagNewElements {
    const val TITLE = "Autotag New Elements"
    private const val COL_FILENAME = 0
    private const val COL_PATH = 1

    fun run() {
        if (Dialogs.isHeadless()) return
        val parent = Dialogs.parent()
        val (panel, enabledBox, textArea) = buildPanel()
        val result = JOptionPane.showConfirmDialog(
            parent, panel, TITLE,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return
        val pairs = AutotagLogic.textToTags(textArea.text ?: "")
        AutotagHook.setTags(pairs)
        AutotagHook.setEnabled(enabledBox.isSelected)
        val msg = when {
            enabledBox.isSelected && pairs.isNotEmpty() -> {
                val summary = pairs.joinToString(", ") { (k, v) -> "$k=$v" }
                "Autotagging ON. New elements get: $summary"
            }
            enabledBox.isSelected ->
                "Autotagging is ON but no tags are set - nothing will be applied."
            else -> "Autotagging is OFF."
        }
        Dialogs.infoAutoClose(msg, TITLE, 2500)
    }

    private fun buildPanel(): Triple<JPanel, JCheckBox, JTextArea> {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val enabledBox = JCheckBox(
            "Autotag newly created elements (nodes, ways, relations)",
            AutotagHook.isEnabled(),
        )
        val hint = JLabel(
            "<html>One <b>key=value</b> per line. Applied to elements you draw or " +
                "paste (negative id); loaded file elements are untouched.</html>",
        )
        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        for (comp in listOf<JComponent>(enabledBox, hint)) {
            comp.alignmentX = Component.LEFT_ALIGNMENT
            north.add(comp)
        }
        panel.add(north, BorderLayout.NORTH)
        val textArea = JTextArea(AutotagLogic.tagsToText(AutotagHook.getTags()), 6, 36)
        textArea.toolTipText = "e.g. file_origin=/path/to/your_map_ll2.osm"
        val scroll = JScrollPane(textArea)
        scroll.preferredSize = Dimension(420, 130)
        panel.add(scroll, BorderLayout.CENTER)
        val south = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        south.add(JLabel("file_origin:"))
        val choose = JButton("Choose file_origin...")
        choose.toolTipText = "Open a searchable table of file_origin values from the active layer"
        choose.addActionListener {
            val sel = showFileOriginPicker(Dialogs.parent(), AutotagHook.existingFileOrigins())
            if (sel != null) {
                applyFileOriginToText(textArea, sel)
            }
        }
        south.add(choose)
        panel.add(south, BorderLayout.SOUTH)
        return Triple(panel, enabledBox, textArea)
    }

    internal fun applyFileOriginToText(textArea: JTextArea, path: String) {
        val pairs = AutotagLogic.replaceFileOrigin(AutotagLogic.textToTags(textArea.text ?: ""), path)
        textArea.text = AutotagLogic.tagsToText(pairs)
    }

    internal fun showFileOriginPicker(parent: Component?, origins: List<String>): String? {
        if (Dialogs.isHeadless()) return null
        if (origins.isEmpty()) {
            JOptionPane.showMessageDialog(
                parent,
                "No file_origin tags found in the active layer.",
                "Choose file_origin",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return null
        }
        val result = arrayOfNulls<String>(1)
        val dlg = JDialog(parent as? java.awt.Frame, "Choose file_origin", true)
        dlg.layout = BorderLayout(8, 8)
        (dlg.contentPane as JComponent).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val hint = JLabel(
            "<html>All file_origin values in the active layer are listed below. " +
                "Type any substring of the filename or path to filter the table, " +
                "then select one row.</html>",
        )
        val searchTf = JTextField(40)
        val searchRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        searchRow.add(JLabel("Search:"))
        searchRow.add(searchTf)
        val top = JPanel(BorderLayout(6, 6))
        top.add(hint, BorderLayout.NORTH)
        top.add(searchRow, BorderLayout.SOUTH)
        val rows = origins.map { arrayOf<Any>(File(it).name, it) }.toTypedArray()
        val model = DefaultTableModel(rows, arrayOf("Filename", "file_origin"))
        val table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.tableHeader.reorderingAllowed = false
        val maxNameLen = origins.maxOf { File(it).name.length }
        val nameW = minOf(maxOf(120, maxNameLen * 7 + 24), 320)
        table.columnModel.getColumn(COL_FILENAME).preferredWidth = nameW
        val size = AutotagLogic.pickerDialogSize(
            origins.maxOf { maxOf(it.length, File(it).name.length) },
            origins.size,
            charWidth(parent),
        )
        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(size.width - 40, 240)

        fun applyFilter() {
            val q = searchTf.text
            if (!q.isNullOrBlank()) {
                sorter.rowFilter = FileOriginRowFilter(q)
            } else {
                sorter.rowFilter = null
            }
        }
        searchTf.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        fun selectedPath(): String? {
            val row = table.selectedRow
            if (row < 0) return null
            val modelRow = table.convertRowIndexToModel(row)
            return model.getValueAt(modelRow, COL_PATH)?.toString()
        }

        fun accept() {
            val sel = selectedPath()
            if (sel == null) {
                JOptionPane.showMessageDialog(
                    dlg,
                    "Select a row from the table.",
                    "Choose file_origin",
                    JOptionPane.WARNING_MESSAGE,
                )
                return
            }
            result[0] = sel
            dlg.isVisible = false
            dlg.dispose()
        }

        val ok = JButton("OK")
        ok.addActionListener { accept() }
        val cancel = JButton("Cancel")
        cancel.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        btnRow.add(ok)
        btnRow.add(cancel)
        dlg.add(top, BorderLayout.NORTH)
        dlg.add(scroll, BorderLayout.CENTER)
        dlg.add(btnRow, BorderLayout.SOUTH)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) accept()
            }
        })
        val im = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "acceptRow")
        table.actionMap.put(
            "acceptRow",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = accept()
            },
        )
        dlg.pack()
        dlg.setSize(Dimension(size.width, size.height))
        dlg.setLocationRelativeTo(parent)
        dlg.rootPane.defaultButton = ok
        searchTf.requestFocusInWindow()
        dlg.isVisible = true
        return result[0]
    }

    private fun charWidth(parent: Component?): Int {
        if (parent != null) {
            try {
                return maxOf(7, parent.getFontMetrics(parent.font).charWidth('M'))
            } catch (_: Exception) {
            }
        }
        return 8
    }

    private class FileOriginRowFilter(query: String) : RowFilter<DefaultTableModel, Int>() {
        private val q = query.trim().lowercase()

        override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
            if (q.isEmpty()) return true
            for (col in listOf(COL_FILENAME, COL_PATH)) {
                val v = entry.getValue(col)
                if (v != null && q in v.toString().lowercase()) return true
            }
            return false
        }
    }
}
