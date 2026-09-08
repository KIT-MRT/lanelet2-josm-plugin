package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.WindowConstants

/**
 * Lanelet2 Settings window. Port of `core/settings_ui/lanelet2_settings_window.py`
 * with [RoutingPanel] injected the way `ll2_routing_settings.register_settings_extension`
 * does in Jython.
 */
object SettingsWindow {
    const val TITLE = "Lanelet2 Settings"

    private val otherSubtypes = LaneletSettings.LANELET_SUBTYPES.filter {
        it !in listOf("road", "bicycle_lane", "crosswalk")
    }

    fun show(parent: java.awt.Component? = null) {
        if (Dialogs.isHeadless()) return
        val owner = parent ?: try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        val dlg = JDialog(owner as? java.awt.Frame, TITLE, true)
        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dlg.layout = BorderLayout()

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        content.add(JLabel(" "))
        content.add(JLabel("Default lanelet subtype (Create Lanelet):"))
        content.add(
            JLabel(
                "<html><small>With 3+ ways selected: crosswalk = batch (L,R,... even count only); " +
                    "other subtypes = parallel strip + chain (N+1 borders).</small></html>",
            ),
        )

        val grpSubtype = ButtonGroup()
        val radRoad = JRadioButton("road", true)
        val radBicycle = JRadioButton("bicycle_lane")
        val radCrosswalk = JRadioButton("crosswalk")
        val radOther = JRadioButton("Other")
        grpSubtype.add(radRoad)
        grpSubtype.add(radBicycle)
        grpSubtype.add(radCrosswalk)
        grpSubtype.add(radOther)

        val rowCommon = JPanel(FlowLayout(FlowLayout.LEFT))
        rowCommon.add(radRoad)
        rowCommon.add(radBicycle)
        rowCommon.add(radCrosswalk)
        content.add(rowCommon)

        val comboOther = JComboBox(otherSubtypes.toTypedArray())
        val rowOther = JPanel(FlowLayout(FlowLayout.LEFT))
        rowOther.add(radOther)
        rowOther.add(comboOther)
        content.add(rowOther)

        val savedSub = LaneletSettings.getLaneletDefaultSubtype()
        when (savedSub) {
            "road" -> radRoad.isSelected = true
            "bicycle_lane" -> radBicycle.isSelected = true
            "crosswalk" -> radCrosswalk.isSelected = true
            else -> {
                radOther.isSelected = true
                if (savedSub in otherSubtypes) {
                    comboOther.selectedItem = savedSub
                } else {
                    comboOther.selectedIndex = 0
                }
            }
        }

        fun refreshOtherCombo() {
            comboOther.isEnabled = radOther.isSelected
        }
        val subtypeListener = ActionListener { refreshOtherCombo() }
        for (rb in listOf(radRoad, radBicycle, radCrosswalk, radOther)) {
            rb.addActionListener(subtypeListener)
        }
        refreshOtherCombo()

        content.add(JLabel(" "))
        content.add(
            JLabel(
                "Default location (urban / nonurban): used for road, highway, bus_lane, exit; " +
                    "ignored for other subtypes when creating lanelets.",
            ),
        )

        val grpLoc = ButtonGroup()
        val radUrban = JRadioButton("urban", true)
        val radNonurban = JRadioButton("nonurban")
        grpLoc.add(radUrban)
        grpLoc.add(radNonurban)
        val locRow = JPanel(FlowLayout(FlowLayout.LEFT))
        locRow.add(radUrban)
        locRow.add(radNonurban)
        content.add(locRow)

        if (LaneletSettings.getLaneletDefaultLocation() == LaneletSettings.LOC_NONURBAN) {
            radNonurban.isSelected = true
        } else {
            radUrban.isSelected = true
        }

        content.add(JLabel(" "))
        content.add(
            JLabel(
                "Default one_way for new lanelets: one-way (tag omitted) or bidirectional (one_way=no).",
            ),
        )
        val grpOw = ButtonGroup()
        val radOwYes = JRadioButton("One-way (yes; tag omitted)", true)
        val radOwNo = JRadioButton("Bidirectional (one_way=no)")
        grpOw.add(radOwYes)
        grpOw.add(radOwNo)
        val owRow = JPanel(FlowLayout(FlowLayout.LEFT))
        owRow.add(radOwYes)
        owRow.add(radOwNo)
        content.add(owRow)

        if (LaneletSettings.getLaneletDefaultOneWay() == LaneletSettings.ONE_WAY_NO) {
            radOwNo.isSelected = true
        } else {
            radOwYes.isSelected = true
        }

        content.add(JLabel(" "))
        content.add(JLabel("Editing:"))
        val chkDeleteTagged = JCheckBox(
            "Delete tagged nodes when deleting ways (overrides JOSM default)",
            LaneletSettings.getDeleteTaggedNodes(),
        )
        chkDeleteTagged.toolTipText =
            "When on, deleting a way also removes its nodes that would otherwise be " +
                "orphaned - including tagged ones. Nodes still used elsewhere are kept."
        val deleteRow = JPanel(FlowLayout(FlowLayout.LEFT))
        deleteRow.add(chkDeleteTagged)
        content.add(deleteRow)

        val chkProtectAnchors = JCheckBox(
            "Protect merge_anchor nodes (block move / delete / tag removal)",
            LaneletSettings.getProtectMergeAnchors(),
        )
        chkProtectAnchors.toolTipText =
            "When on, editing a node tagged merge_anchor is reverted with a warning, " +
                "so partial maps stay mergeable into a city-level map."
        val anchorRow = JPanel(FlowLayout(FlowLayout.LEFT))
        anchorRow.add(chkProtectAnchors)
        content.add(anchorRow)

        content.add(JLabel(" "))
        content.add(JLabel("Merge / file boundaries:"))
        val gridCellModel = SpinnerNumberModel(
            LaneletSettings.getMergeGridCellM().toInt(),
            LaneletSettings.MERGE_GRID_CELL_M_MIN.toInt(),
            LaneletSettings.MERGE_GRID_CELL_M_MAX.toInt(),
            5,
        )
        val gridCellSpinner = JSpinner(gridCellModel)
        gridCellSpinner.toolTipText =
            "Occupancy grid cell size in meters for merge overlap discovery and " +
                "File Boundaries (FileBds) hulls. Smaller = finer detail, slower."
        val gridCellRow = JPanel(FlowLayout(FlowLayout.LEFT))
        gridCellRow.add(JLabel("Grid cell size (meters):"))
        gridCellRow.add(gridCellSpinner)
        content.add(gridCellRow)

        content.add(JLabel(" "))
        content.add(JLabel("Map paint styles:"))
        val mapStylesRow = JPanel(FlowLayout(FlowLayout.LEFT))
        val mapStylesBtn = JButton("Map Styles...")
        mapStylesBtn.toolTipText =
            "Configure bundled LL2 MapCSS styles, apply presets, and reorder styles."
        mapStylesBtn.addActionListener {
            try {
                MapStylesDialog.show(dlg)
            } catch (ex: Exception) {
                Dialogs.error("Could not open Map Styles dialog:\n$ex", "Lanelet2 Map Styles")
            }
        }
        mapStylesRow.add(mapStylesBtn)
        content.add(mapStylesRow)

        content.add(JLabel(" "))
        content.add(JLabel("Tagging presets:"))
        val presetsRow = JPanel(FlowLayout(FlowLayout.LEFT))
        val presetsBtn = JButton("Presets...")
        presetsBtn.toolTipText =
            "Install bundled LL2 tagging presets and manage toolbar preset buttons."
        presetsBtn.addActionListener {
            try {
                PresetsDialog.show(dlg)
            } catch (ex: Exception) {
                Dialogs.error("Could not open Presets dialog:\n$ex", "Lanelet2 Presets")
            }
        }
        presetsRow.add(presetsBtn)
        content.add(presetsRow)

        val routingOnOk = RoutingPanel.addPanel(content)

        dlg.add(content, BorderLayout.CENTER)

        val btnPanel = JPanel(FlowLayout())
        val okBtn = JButton("OK")
        val cancelBtn = JButton("Cancel")
        okBtn.addActionListener {
            if (radRoad.isSelected) {
                LaneletSettings.setLaneletDefaultSubtype("road")
            } else if (radBicycle.isSelected) {
                LaneletSettings.setLaneletDefaultSubtype("bicycle_lane")
            } else if (radCrosswalk.isSelected) {
                LaneletSettings.setLaneletDefaultSubtype("crosswalk")
            } else {
                LaneletSettings.setLaneletDefaultSubtype(comboOther.selectedItem?.toString())
            }
            if (radUrban.isSelected) {
                LaneletSettings.setLaneletDefaultLocation(LaneletSettings.LOC_URBAN)
            } else {
                LaneletSettings.setLaneletDefaultLocation(LaneletSettings.LOC_NONURBAN)
            }
            if (radOwNo.isSelected) {
                LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_NO)
            } else {
                LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_YES)
            }
            LaneletSettings.setDeleteTaggedNodes(chkDeleteTagged.isSelected)
            LaneletSettings.setProtectMergeAnchors(chkProtectAnchors.isSelected)
            try {
                LaneletSettings.setMergeGridCellM((gridCellSpinner.value as Number).toDouble())
            } catch (_: Exception) {
                LaneletSettings.setMergeGridCellM(LaneletSettings.MERGE_GRID_CELL_M_DEFAULT)
            }
            routingOnOk()
            dlg.isVisible = false
            dlg.dispose()
        }
        cancelBtn.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        btnPanel.add(okBtn)
        btnPanel.add(cancelBtn)
        dlg.add(btnPanel, BorderLayout.SOUTH)

        dlg.setSize(520, 720)
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }
}
