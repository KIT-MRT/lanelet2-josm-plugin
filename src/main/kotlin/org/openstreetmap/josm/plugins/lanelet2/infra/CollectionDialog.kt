package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.edit.zoomToPrimitives
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.util.ArrayList
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Incremental collector used by Select Lanelets / Relations and the
 * regulatory-element wizards.
 *
 * Port of `show_lanelet_collection_dialog` / `show_relation_collection_dialog`
 * (and the small step-dialog shells those wizards call first). Decision logic
 * is in [CollectionLogic]; this file is Swing only.
 */
object CollectionDialog {
    const val DEFAULT_LANELET_TITLE = "Select Lanelets"
    const val DEFAULT_RELATION_TITLE = "Select Relations"
    const val DEFAULT_LANELET_MESSAGE =
        "Select linestrings (lane boundaries) or lanelets. Add / Select / Done."
    const val DEFAULT_RELATION_MESSAGE =
        "Select ways (e.g. traffic lights, stop lines) or relations. Add / Select / Done."

    fun clearSelection(data: DataSet) {
        try {
            data.setSelected(emptyList())
        } catch (_: Exception) {
            try {
                data.clearSelection()
            } catch (_: Exception) {
            }
        }
    }

    fun showLaneletCollection(
        data: DataSet,
        onDone: (List<Relation>) -> Unit,
        title: String = DEFAULT_LANELET_TITLE,
        message: String = DEFAULT_LANELET_MESSAGE,
        minCount: Int = 1,
        initialCollected: List<Relation> = emptyList(),
        helpTitle: String? = null,
        helpText: String? = null,
        helpLinks: List<Pair<String, String>>? = null,
        ui: UserPrompts = Dialogs,
    ) {
        showCollection(
            data = data,
            onDone = onDone,
            title = title,
            message = message,
            highlight = CollectionLogic.HIGHLIGHT_LINESTRINGS,
            kind = CollectionLogic.KIND_LANELET,
            minCount = minCount,
            initialCollected = initialCollected,
            helpTitle = helpTitle,
            helpText = helpText,
            helpLinks = helpLinks ?: CollectionLogic.DEFAULT_LANELET_HELP_LINKS,
            modeALabel = "Select lanelets only (Mode A)",
            modeATip = "When unchecked (default): select linestrings to infer lanelets. " +
                "When checked: select lanelets directly.",
            emptyListLabel = "(no lanelets collected)",
            showTypeColumn = false,
            showSelectMembers = false,
            doneButtonText = "Done",
            extract = { sel, modeA -> CollectionLogic.extractLaneletsForAdd(data, sel, modeA) },
            ui = ui,
        )
    }

    fun showRelationCollection(
        data: DataSet,
        onDone: (List<Relation>) -> Unit,
        relType: String,
        relSubtype: String? = null,
        title: String = DEFAULT_RELATION_TITLE,
        message: String = DEFAULT_RELATION_MESSAGE,
        minCount: Int = 1,
        initialCollected: List<Relation> = emptyList(),
        helpTitle: String? = null,
        helpText: String? = null,
        helpLinks: List<Pair<String, String>>? = null,
        doneButtonText: String = "Done",
        ui: UserPrompts = Dialogs,
    ) {
        showCollection(
            data = data,
            onDone = onDone,
            title = title,
            message = message,
            highlight = CollectionLogic.HIGHLIGHT_WAYS,
            kind = CollectionLogic.KIND_RELATION,
            minCount = minCount,
            initialCollected = initialCollected,
            helpTitle = helpTitle,
            helpText = helpText,
            helpLinks = helpLinks ?: CollectionLogic.DEFAULT_RELATION_HELP_LINKS,
            modeALabel = "Select relations only (Mode A)",
            modeATip = "When unchecked (default): select ways to infer relations. " +
                "When checked: select relations directly.",
            emptyListLabel = "(no relations collected)",
            showTypeColumn = true,
            showSelectMembers = true,
            doneButtonText = doneButtonText,
            extract = { sel, modeA ->
                CollectionLogic.extractRelationsForAdd(data, sel, relType, relSubtype, modeA)
            },
            ui = ui,
        )
    }

    /**
     * Non-modal "OK when done" prompt. Port of `show_step_dialog`.
     * Used by the right-of-way (and other) create wizards for the first step.
     */
    fun showStep(
        title: String,
        message: String,
        onOk: () -> Unit,
        highlight: String? = null,
        helpTitle: String? = null,
        helpText: String? = null,
        helpLinks: List<Pair<String, String>>? = null,
    ) {
        if (Dialogs.isHeadless()) return
        SwingUtilities.invokeLater {
            val parent = Dialogs.parent()
            val dlg = JDialog(parent as? Frame, title, false)
            dlg.layout = BorderLayout()
            val html = if (highlight != null) {
                CollectionLogic.styledMessageHtml(message, highlight)
            } else {
                "<html>${message.replace("\n", "<br>")}</html>"
            }
            dlg.add(JLabel(html), BorderLayout.CENTER)
            val panel = JPanel(FlowLayout())
            val btn = JButton("OK")
            btn.addActionListener {
                dlg.isVisible = false
                dlg.dispose()
                onOk()
            }
            panel.add(btn)
            if (helpText != null) {
                val btnHelp = JButton("Help")
                btnHelp.addActionListener {
                    showHelp(
                        helpTitle ?: "Lanelet2 Help",
                        helpText,
                        helpLinks ?: listOf("RegulatoryElementTagging" to "RegulatoryElementTagging.md"),
                    )
                }
                panel.add(btnHelp)
            }
            dlg.add(panel, BorderLayout.SOUTH)
            dlg.pack()
            dlg.setSize(maxOf(400, dlg.width), maxOf(120, dlg.height))
            positionUpperLeft(dlg, parent)
            dlg.isVisible = true
        }
    }

    /**
     * Non-modal multi-item collector (Add / Select / Done). Port of
     * `show_step_dialog_multi` — used for traffic lights / signs, not lanelets.
     */
    fun showStepMulti(
        title: String,
        message: String,
        data: DataSet,
        extract: (Iterable<OsmPrimitive?>) -> List<OsmPrimitive>,
        onDone: (List<OsmPrimitive>) -> Unit,
        minCount: Int,
        itemNamePlural: String,
        highlight: String? = null,
        ui: UserPrompts = Dialogs,
    ) {
        if (Dialogs.isHeadless()) return
        SwingUtilities.invokeLater {
            val basket = CollectionLogic.Basket<OsmPrimitive>()
            val parent = Dialogs.parent()
            val dlg = JDialog(parent as? Frame, title, false)
            dlg.layout = BorderLayout()
            val htmlBase = if (highlight != null) {
                val styled = "<b><font size='+1'>$highlight</font></b>"
                val messageStyled = message.replace(highlight, styled)
                "<html>" + messageStyled.replace("\n", "<br>")
            } else {
                "<html>" + message.replace("\n", "<br>")
            }
            val lbl = JLabel(CollectionLogic.multiStepLabelHtml(htmlBase, 0))
            dlg.add(lbl, BorderLayout.CENTER)

            fun refresh() {
                lbl.text = CollectionLogic.multiStepLabelHtml(htmlBase, basket.size())
                dlg.pack()
            }

            val btnAdd = JButton("Add")
            btnAdd.addActionListener {
                val items = extract(data.selected)
                if (items.isEmpty()) {
                    ui.warn("Select at least 1 $itemNamePlural to add.", title)
                    return@addActionListener
                }
                basket.add(items.toList())
                clearSelection(data)
                refresh()
            }
            val btnSelect = JButton("Select")
            btnSelect.addActionListener {
                if (basket.isEmpty()) {
                    ui.infoAutoClose("No items collected yet.", title, 1000)
                    return@addActionListener
                }
                applySelection(data, basket.items(), zoom = false)
            }
            val btnDone = JButton("Done")
            btnDone.addActionListener {
                if (!basket.canFinish(minCount)) {
                    ui.warn("Need at least $minCount $itemNamePlural.", title)
                    return@addActionListener
                }
                dlg.isVisible = false
                dlg.dispose()
                onDone(basket.items())
            }
            val btnCancel = JButton("Cancel")
            btnCancel.addActionListener {
                dlg.isVisible = false
                dlg.dispose()
            }
            val panel = JPanel(FlowLayout())
            panel.add(btnAdd)
            panel.add(btnSelect)
            panel.add(btnDone)
            panel.add(btnCancel)
            dlg.add(panel, BorderLayout.SOUTH)
            dlg.pack()
            dlg.setSize(maxOf(400, dlg.width), maxOf(140, dlg.height))
            positionUpperLeft(dlg, parent)
            dlg.isVisible = true
        }
    }

    fun showHelp(
        title: String,
        bodyText: String,
        docLinks: List<Pair<String, String>>? = null,
    ) {
        if (Dialogs.isHeadless()) return
        val parent = Dialogs.parent()
        val dlg = JDialog(parent as? Frame, title, false)
        dlg.layout = BorderLayout()
        val area = JTextArea(CollectionLogic.helpBody(bodyText, docLinks), 20, 60)
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        val scroll = JScrollPane(area)
        scroll.preferredSize = Dimension(500, 400)
        dlg.add(scroll, BorderLayout.CENTER)
        val btn = JButton("Close")
        btn.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        val panel = JPanel(FlowLayout())
        panel.add(btn)
        dlg.add(panel, BorderLayout.SOUTH)
        dlg.pack()
        dlg.setSize(maxOf(520, dlg.width), maxOf(450, dlg.height))
        positionUpperLeft(dlg, parent)
        dlg.isVisible = true
    }

    fun positionUpperLeft(dialog: java.awt.Window, parent: java.awt.Component?) {
        if (parent == null) {
            dialog.setLocationRelativeTo(null)
            return
        }
        try {
            val loc = parent.locationOnScreen
            val pos = CollectionLogic.positionUpperLeft(
                loc.x, loc.y, parent.width, parent.height, dialog.width, dialog.height,
            )
            dialog.setLocation(pos.x, pos.y)
        } catch (_: Exception) {
            dialog.setLocationRelativeTo(parent)
        }
    }

    private fun showCollection(
        data: DataSet,
        onDone: (List<Relation>) -> Unit,
        title: String,
        message: String,
        highlight: String,
        kind: String,
        minCount: Int,
        initialCollected: List<Relation>,
        helpTitle: String?,
        helpText: String?,
        helpLinks: List<Pair<String, String>>?,
        modeALabel: String,
        modeATip: String,
        emptyListLabel: String,
        showTypeColumn: Boolean,
        showSelectMembers: Boolean,
        doneButtonText: String,
        extract: (Iterable<OsmPrimitive?>, Boolean) -> List<Relation>,
        ui: UserPrompts,
    ) {
        if (Dialogs.isHeadless()) return
        SwingUtilities.invokeLater {
            createCollectionDialog(
                data, onDone, title, message, highlight, kind, minCount,
                initialCollected, helpTitle, helpText, helpLinks, modeALabel, modeATip,
                emptyListLabel, showTypeColumn, showSelectMembers, doneButtonText, extract, ui,
            )
        }
    }

    private fun createCollectionDialog(
        data: DataSet,
        onDone: (List<Relation>) -> Unit,
        title: String,
        message: String,
        highlight: String,
        kind: String,
        minCount: Int,
        initialCollected: List<Relation>,
        helpTitle: String?,
        helpText: String?,
        helpLinks: List<Pair<String, String>>?,
        modeALabel: String,
        modeATip: String,
        emptyListLabel: String,
        showTypeColumn: Boolean,
        showSelectMembers: Boolean,
        doneButtonText: String,
        extract: (Iterable<OsmPrimitive?>, Boolean) -> List<Relation>,
        ui: UserPrompts,
    ) {
        val basket = CollectionLogic.Basket(initialCollected)
        val parent = Dialogs.parent()
        val dlg = JDialog(parent as? Frame, title, false)
        dlg.layout = BorderLayout()

        val lblMsg = JLabel(CollectionLogic.styledMessageHtml(message, highlight))
        val countLbl = JLabel(CollectionLogic.countLabel(0, kind))
        val chkModeA = JCheckBox(modeALabel, false)
        chkModeA.toolTipText = modeATip
        val chkSelectMembers = if (showSelectMembers) {
            JCheckBox("Select members", false).also {
                it.toolTipText =
                    "When checked: Select and S buttons select relation members (ways, lanelets) " +
                        "instead of the relations. Useful to see which lanelets are part of " +
                        "e.g. a right_of_way relation."
            }
        } else {
            null
        }
        val panelNorth = JPanel(FlowLayout(FlowLayout.LEFT))
        panelNorth.add(lblMsg)
        panelNorth.add(chkModeA)
        if (chkSelectMembers != null) panelNorth.add(chkSelectMembers)
        panelNorth.add(countLbl)
        dlg.add(panelNorth, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        val scroll = JScrollPane(listPanel)
        scroll.preferredSize = Dimension(400, 200)
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        dlg.add(scroll, BorderLayout.CENTER)

        fun selectMembersOn(): Boolean = chkSelectMembers?.isSelected == true

        fun buildList() {
            listPanel.removeAll()
            val items = basket.items()
            if (items.isNotEmpty()) {
                val header = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
                header.add(sizedLabel("ID", 70))
                if (showTypeColumn) header.add(sizedLabel("type", 80))
                header.add(sizedLabel("subtype", 100))
                header.add(JLabel(""))
                listPanel.add(header)
            }
            for (rel in items) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
                row.add(sizedLabel(CollectionLogic.primIdStr(rel), 70))
                if (showTypeColumn) row.add(sizedLabel(CollectionLogic.tagOrDash(rel, "type"), 80))
                row.add(sizedLabel(CollectionLogic.tagOrDash(rel, "subtype"), 100))
                val btnS = JButton("S")
                btnS.toolTipText = if (showSelectMembers) {
                    "Select and zoom to this relation (or its members if 'Select members' is checked)"
                } else {
                    "Select and zoom to this lanelet on the map"
                }
                btnS.preferredSize = Dimension(52, 18)
                btnS.addActionListener {
                    val targets = CollectionLogic.primitivesToSelect(
                        items, selectMembersOn(), single = rel,
                    )
                    applySelection(data, targets, zoom = true)
                }
                row.add(btnS)
                val btnX = JButton("X")
                btnX.toolTipText = "Remove this ${if (showTypeColumn) "relation" else "lanelet"} from collection"
                btnX.preferredSize = Dimension(52, 18)
                btnX.addActionListener {
                    if (basket.remove(rel)) {
                        clearSelection(data)
                        buildList()
                        countLbl.text = CollectionLogic.countLabel(basket.size(), kind)
                        dlg.pack()
                    }
                }
                row.add(btnX)
                listPanel.add(row)
            }
            if (items.isEmpty()) {
                listPanel.add(JLabel(emptyListLabel))
            }
            listPanel.revalidate()
            listPanel.repaint()
        }

        fun updateLabel() {
            buildList()
            countLbl.text = CollectionLogic.countLabel(basket.size(), kind)
            dlg.pack()
        }

        val btnAdd = JButton("Add")
        btnAdd.addActionListener {
            val items = extract(data.selected, chkModeA.isSelected)
            if (items.isEmpty()) {
                ui.warn(CollectionLogic.emptyExtractMessage(!showTypeColumn), title)
                return@addActionListener
            }
            basket.add(items)
            clearSelection(data)
            updateLabel()
        }
        val btnSelect = JButton("Select")
        btnSelect.addActionListener {
            if (basket.isEmpty()) {
                ui.infoAutoClose(CollectionLogic.nothingCollectedMessage(kind), title, 1000)
                return@addActionListener
            }
            val members = if (selectMembersOn()) {
                CollectionLogic.relationMembersFromList(basket.items())
            } else {
                emptyList()
            }
            if (members.isNotEmpty()) {
                applySelection(data, members, zoom = true)
            } else {
                applySelection(data, basket.items(), zoom = false)
            }
        }
        val btnUndo = JButton("Undo")
        btnUndo.toolTipText = "Remove the last batch added."
        btnUndo.addActionListener {
            if (basket.undo() == null) {
                ui.infoAutoClose("Nothing to undo.", title, 1000)
                return@addActionListener
            }
            clearSelection(data)
            updateLabel()
        }
        val btnClear = JButton("Clear")
        btnClear.addActionListener {
            basket.clear()
            clearSelection(data)
            updateLabel()
        }
        val btnDone = JButton(doneButtonText)
        btnDone.addActionListener {
            if (!basket.canFinish(minCount)) {
                ui.warn(CollectionLogic.needMinMessage(minCount, kind), title)
                return@addActionListener
            }
            dlg.isVisible = false
            dlg.dispose()
            onDone(basket.items())
        }
        val btnCancel = JButton("Cancel")
        btnCancel.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        val panel = JPanel(FlowLayout())
        panel.add(btnAdd)
        panel.add(btnSelect)
        panel.add(btnUndo)
        panel.add(btnClear)
        panel.add(btnDone)
        panel.add(btnCancel)
        if (helpText != null) {
            val btnHelp = JButton("Help")
            btnHelp.addActionListener {
                showHelp(helpTitle ?: "Lanelet2 Help", helpText, helpLinks)
            }
            panel.add(btnHelp)
        }
        dlg.add(panel, BorderLayout.SOUTH)
        updateLabel()
        dlg.pack()
        dlg.setSize(maxOf(450, dlg.width), maxOf(280, dlg.height))
        positionUpperLeft(dlg, parent)
        dlg.isVisible = true
    }

    private fun sizedLabel(text: String, width: Int): JLabel {
        val lbl = JLabel(text)
        lbl.preferredSize = Dimension(width, 14)
        return lbl
    }

    private fun applySelection(data: DataSet, prims: Collection<OsmPrimitive>, zoom: Boolean) {
        if (prims.isEmpty()) return
        try {
            data.setSelected(ArrayList(prims))
        } catch (_: Exception) {
        }
        if (zoom) zoomToPrimitives(prims)
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }
}
