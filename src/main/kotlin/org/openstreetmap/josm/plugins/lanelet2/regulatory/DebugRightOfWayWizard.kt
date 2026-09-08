package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.zoomToPrimitives
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.RegulatoryElements
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.util.ArrayList
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Step through `subtype=right_of_way` regulatory elements, selecting/zooming
 * their `right_of_way`, `yield`, and `ref_line` members.
 *
 * No dataset mutation (no undo). Port of `debug_right_of_way_wizard.py`.
 *
 * [run] collects the relations through the collection dialog, as the Jython
 * does; only a headless caller falls back to the current selection.
 * [pageFor] / [nextIndex] / [prevIndex] are headless-testable. The wizard
 * itself is read-only (no undo entries).
 */
object DebugRightOfWayWizard {
    const val TITLE = "Right of Way Debug Wizard"
    const val TITLE_LAYER = "Right of Way Debug Wizard"
    const val COLLECT_TITLE = "Right of Way Debug Wizard: Select Relations"

    const val HELP_TEXT = """Right of Way Debug Wizard

Select right_of_way regulatory elements, then step through each one.
For each relation you can:
- Select all right of way lanelets
- Select all yielding lanelets
- Select all ref_line(s)

Use Prev/Next to navigate between relations."""

    data class Page(
        val relation: Relation,
        val rightOfWay: List<OsmPrimitive>,
        val yield: List<OsmPrimitive>,
        val refLines: List<OsmPrimitive>,
    ) {
        val allMembers: List<OsmPrimitive> get() = rightOfWay + yield + refLines
    }

    fun primIdStr(prim: OsmPrimitive?): String {
        if (prim == null) return "?"
        return try {
            prim.uniqueId.toString()
        } catch (_: Exception) {
            "?"
        }
    }

    fun primSubtype(prim: OsmPrimitive?): String {
        return try {
            prim?.get("subtype") ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }

    fun primType(prim: OsmPrimitive?): String {
        return try {
            prim?.get("type") ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }

    fun pageFor(rel: Relation): Page {
        val members = RegulatoryElements.extractMembersByRole(
            rel,
            roles = listOf("right_of_way", "yield", "ref_line"),
        )
        return Page(
            relation = rel,
            rightOfWay = members["right_of_way"] ?: emptyList(),
            yield = members["yield"] ?: emptyList(),
            refLines = members["ref_line"] ?: emptyList(),
        )
    }

    /** Null means the Next button should act as Done. */
    fun nextIndex(current: Int, size: Int): Int? {
        if (current < size - 1) return current + 1
        return null
    }

    fun prevIndex(current: Int): Int? = if (current > 0) current - 1 else null

    fun selectAndZoom(data: DataSet, prims: Collection<OsmPrimitive>) {
        if (prims.isEmpty()) return
        try {
            data.setSelected(ArrayList(prims))
        } catch (_: Exception) {
        }
        zoomToPrimitives(prims)
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE_LAYER)
            return
        }
        val data = layer.data
        if (CollectionLogic.collectionDialogRequired()) {
            CollectionDialog.clearSelection(data)
            CollectionDialog.showRelationCollection(
                data = data,
                onDone = { collected -> startWizard(data, collected, ui) },
                relType = "regulatory_element",
                relSubtype = "right_of_way",
                title = COLLECT_TITLE,
                message = "Select ways or right_of_way relations. Add / Select / Done. Then the wizard will open.",
                minCount = 1,
                helpTitle = TITLE,
                helpText = HELP_TEXT,
                helpLinks = listOf("RegulatoryElementTagging" to "RegulatoryElementTagging.md"),
                ui = ui,
            )
            return
        }
        val collected = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, "regulatory_element", "right_of_way",
        )
        startWizard(data, collected, ui)
    }

    fun startWizard(data: DataSet, collected: List<Relation>, ui: UserPrompts = Dialogs) {
        if (collected.isEmpty()) {
            ui.info("No right_of_way relations to debug.", TITLE)
            return
        }
        if (Dialogs.isHeadless()) {
            selectAndZoom(data, pageFor(collected.first()).allMembers)
            ui.info(
                "Right of way debug: ${collected.size} relation(s) (wizard skipped, headless).",
                TITLE,
            )
            return
        }
        showWizardDialog(data, collected)
    }

    fun showWizardDialog(data: DataSet, relations: List<Relation>) {
        if (relations.isEmpty()) return
        if (Dialogs.isHeadless()) return
        SwingUtilities.invokeLater { createWizardDialog(data, relations) }
    }

    private fun createWizardDialog(data: DataSet, relations: List<Relation>) {
        val parent = Dialogs.parent()
        val dlg = JDialog(parent as? java.awt.Frame, TITLE, false)
        dlg.layout = BorderLayout()
        var currentIdx = 0

        val titleLbl = JLabel()
        val contentPanel = JPanel(GridLayout(0, 1, 0, 8))
        val btnPrev = JButton("Prev")
        val btnNext = JButton("Next")

        fun buildRoleSection(roleLabel: String, items: List<OsmPrimitive>, isLanelet: Boolean): JPanel {
            val panel = JPanel(BorderLayout())
            val header = JPanel(FlowLayout(FlowLayout.LEFT))
            header.add(JLabel("<html><b>$roleLabel</b> (${items.size})</html>"))
            val btnSelectAll = JButton("Select All")
            btnSelectAll.toolTipText = "Select all ${roleLabel.lowercase()} and zoom to them"
            btnSelectAll.addActionListener { selectAndZoom(data, items) }
            header.add(btnSelectAll)
            panel.add(header, BorderLayout.NORTH)

            val listPanel = JPanel(GridLayout(0, 3, 4, 2))
            listPanel.add(JLabel("ID"))
            listPanel.add(JLabel(if (isLanelet) "subtype" else "type"))
            listPanel.add(JLabel(""))
            for (prim in items) {
                listPanel.add(JLabel(primIdStr(prim)))
                val sub = if (prim is Relation) primSubtype(prim) else primType(prim)
                listPanel.add(JLabel(sub))
                val btnS = JButton("S")
                btnS.toolTipText = "Select and zoom to this primitive"
                btnS.addActionListener { selectAndZoom(data, listOf(prim)) }
                listPanel.add(btnS)
            }
            val scroll = JScrollPane(listPanel)
            scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            panel.add(scroll, BorderLayout.CENTER)
            return panel
        }

        fun refresh() {
            val page = pageFor(relations[currentIdx])
            titleLbl.text = "<html><b>Relation ${currentIdx + 1} of ${relations.size}</b> (ID: ${primIdStr(page.relation)})</html>"
            contentPanel.removeAll()
            contentPanel.add(buildRoleSection("Right of way lanelets", page.rightOfWay, isLanelet = true))
            contentPanel.add(buildRoleSection("Yielding lanelets", page.yield, isLanelet = true))
            contentPanel.add(buildRoleSection("Ref line(s)", page.refLines, isLanelet = false))
            contentPanel.revalidate()
            contentPanel.repaint()
            btnPrev.isEnabled = currentIdx > 0
            btnNext.text = if (currentIdx >= relations.size - 1) "Done" else "Next"
            selectAndZoom(data, page.allMembers)
        }

        val topPanel = JPanel(BorderLayout())
        topPanel.add(titleLbl, BorderLayout.NORTH)
        val btnSelectAllMembers = JButton("Select All Members")
        btnSelectAllMembers.toolTipText =
            "Select all members of this relation (right-of-way + yield + ref_line) and zoom"
        btnSelectAllMembers.addActionListener {
            selectAndZoom(data, pageFor(relations[currentIdx]).allMembers)
        }
        topPanel.add(btnSelectAllMembers, BorderLayout.SOUTH)
        dlg.add(topPanel, BorderLayout.NORTH)

        val scrollContent = JScrollPane(contentPanel)
        scrollContent.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        dlg.add(scrollContent, BorderLayout.CENTER)

        val btnPanel = JPanel(FlowLayout())
        btnPrev.addActionListener {
            prevIndex(currentIdx)?.let { currentIdx = it; refresh() }
        }
        btnNext.addActionListener {
            val next = nextIndex(currentIdx, relations.size)
            if (next != null) {
                currentIdx = next
                refresh()
            } else {
                dlg.isVisible = false
                dlg.dispose()
            }
        }
        val btnClose = JButton("Close")
        btnClose.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        btnPanel.add(btnPrev)
        btnPanel.add(btnNext)
        btnPanel.add(btnClose)
        dlg.add(btnPanel, BorderLayout.SOUTH)

        refresh()
        dlg.pack()
        dlg.setSize(maxOf(450, dlg.width), maxOf(400, dlg.height))
        CollectionDialog.positionUpperLeft(dlg, parent)
        dlg.isVisible = true
    }
}
