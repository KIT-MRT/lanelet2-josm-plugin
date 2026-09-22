package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.plugins.lanelet2.hooks.AutotagSettingsPanel
import org.openstreetmap.josm.plugins.lanelet2.hooks.ZoomFilterSettingsPanel
import org.openstreetmap.josm.plugins.lanelet2.infra.HeightTools
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/**
 * One settings section: a unique [id], the preference [keys] it writes, and [save].
 * [SettingsForm.ALL_WRITERS] is the complete set — each key has exactly one writer.
 */
data class SettingsSection(
    val id: String,
    val keys: Set<String>,
    val save: () -> Unit,
)

/**
 * Shared settings form used by [SettingsWindow] and [Lanelet2PreferenceSetting].
 * Both UIs are views over these builders; they do not reimplement the keys.
 */
class SettingsForm internal constructor(
    val sections: List<SettingsSection>,
    val editingDefaults: EditingDefaultsControls,
    val editingOptions: EditingOptionsControls,
    val heights: HeightControls,
    val mergeGrid: MergeGridControls,
    val routing: RoutingControls,
    val autotag: AutotagSettingsPanel?,
    val zoomFilter: ZoomFilterSettingsPanel?,
) {
    fun save() = sections.forEach { it.save() }

    fun keys(): Set<String> = sections.flatMapTo(linkedSetOf()) { it.keys }

    fun section(id: String): SettingsSection = sections.first { it.id == id }

    companion object {
        fun standalone(content: JPanel, dialogParent: () -> Component?): SettingsForm =
            build(content, dialogParent, includeHooks = false)

        fun preferencesTab(content: JPanel, dialogParent: () -> Component?): SettingsForm =
            build(content, dialogParent, includeHooks = true)

        /**
         * Every section factory that can write a preference key. Used to assert
         * that no two builders claim the same key.
         */
        val ALL_WRITERS: List<(JPanel) -> SettingsSection> = listOf(
            { p ->
                val c = EditingDefaultsControls.addTo(p)
                SettingsSection(EditingDefaultsControls.ID, EditingDefaultsControls.KEYS, c::save)
            },
            { p ->
                val c = EditingOptionsControls.addTo(p)
                SettingsSection(EditingOptionsControls.ID, EditingOptionsControls.KEYS, c::save)
            },
            { p ->
                val c = HeightControls.addTo(p)
                SettingsSection(HeightControls.ID, HeightControls.KEYS, c::save)
            },
            { p ->
                val c = MergeGridControls.addTo(p)
                SettingsSection(MergeGridControls.ID, MergeGridControls.KEYS, c::save)
            },
            { panel ->
                MapStylesPresetsControls.addTo(panel) { null }
                SettingsSection(MapStylesPresetsControls.ID, MapStylesPresetsControls.KEYS) {}
            },
            { p ->
                val c = RoutingPanel.addControls(p)
                SettingsSection(RoutingControls.ID, RoutingControls.KEYS, c::save)
            },
            { _ ->
                val created = AutotagSettingsPanel.create { null }
                SettingsSection(AutotagSettingsPanel.ID, AutotagSettingsPanel.KEYS, created::save)
            },
            { _ ->
                val created = ZoomFilterSettingsPanel.create()
                SettingsSection(ZoomFilterSettingsPanel.ID, ZoomFilterSettingsPanel.KEYS, created::save)
            },
        )

        private fun build(
            content: JPanel,
            dialogParent: () -> Component?,
            includeHooks: Boolean,
        ): SettingsForm {
            val editingDefaults = EditingDefaultsControls.addTo(content)
            val editingOptions = EditingOptionsControls.addTo(content)
            val heights = HeightControls.addTo(content)
            val mergeGrid = MergeGridControls.addTo(content)
            MapStylesPresetsControls.addTo(content, dialogParent)
            val routing = RoutingPanel.addControls(content)
            val sections = mutableListOf(
                SettingsSection(EditingDefaultsControls.ID, EditingDefaultsControls.KEYS, editingDefaults::save),
                SettingsSection(EditingOptionsControls.ID, EditingOptionsControls.KEYS, editingOptions::save),
                SettingsSection(HeightControls.ID, HeightControls.KEYS, heights::save),
                SettingsSection(MergeGridControls.ID, MergeGridControls.KEYS, mergeGrid::save),
                SettingsSection(MapStylesPresetsControls.ID, MapStylesPresetsControls.KEYS) {},
                SettingsSection(RoutingControls.ID, RoutingControls.KEYS, routing::save),
            )
            var autotag: AutotagSettingsPanel? = null
            var zoomFilter: ZoomFilterSettingsPanel? = null
            if (includeHooks) {
                content.add(JLabel(" "))
                content.add(JLabel("Autotag:"))
                val createdAutotag = AutotagSettingsPanel.create(dialogParent)
                createdAutotag.component.alignmentX = Component.LEFT_ALIGNMENT
                content.add(createdAutotag.component)
                autotag = createdAutotag
                sections += SettingsSection(AutotagSettingsPanel.ID, AutotagSettingsPanel.KEYS, autotag::save)

                content.add(JLabel(" "))
                content.add(JLabel("Zoom filter:"))
                val createdZoom = ZoomFilterSettingsPanel.create()
                createdZoom.component.alignmentX = Component.LEFT_ALIGNMENT
                content.add(createdZoom.component)
                zoomFilter = createdZoom
                sections += SettingsSection(ZoomFilterSettingsPanel.ID, ZoomFilterSettingsPanel.KEYS, zoomFilter::save)
            }
            return SettingsForm(
                sections = sections,
                editingDefaults = editingDefaults,
                editingOptions = editingOptions,
                heights = heights,
                mergeGrid = mergeGrid,
                routing = routing,
                autotag = autotag,
                zoomFilter = zoomFilter,
            )
        }
    }
}

class EditingDefaultsControls internal constructor(
    val radRoad: JRadioButton,
    val radBicycle: JRadioButton,
    val radCrosswalk: JRadioButton,
    val radOther: JRadioButton,
    val comboOther: JComboBox<String>,
    val radUrban: JRadioButton,
    val radNonurban: JRadioButton,
    val radOwYes: JRadioButton,
    val radOwNo: JRadioButton,
) {
    fun save() {
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
    }

    fun selectedSubtype(): String = when {
        radRoad.isSelected -> "road"
        radBicycle.isSelected -> "bicycle_lane"
        radCrosswalk.isSelected -> "crosswalk"
        else -> comboOther.selectedItem?.toString() ?: "road"
    }

    fun selectSubtype(subtype: String) {
        when (subtype) {
            "road" -> radRoad.isSelected = true
            "bicycle_lane" -> radBicycle.isSelected = true
            "crosswalk" -> radCrosswalk.isSelected = true
            else -> {
                radOther.isSelected = true
                if (subtype in otherSubtypes) {
                    comboOther.selectedItem = subtype
                }
            }
        }
        comboOther.isEnabled = radOther.isSelected
    }

    fun selectedLocation(): String =
        if (radNonurban.isSelected) LaneletSettings.LOC_NONURBAN else LaneletSettings.LOC_URBAN

    fun selectLocation(location: String) {
        if (location == LaneletSettings.LOC_NONURBAN) {
            radNonurban.isSelected = true
        } else {
            radUrban.isSelected = true
        }
    }

    fun selectedOneWay(): String =
        if (radOwNo.isSelected) LaneletSettings.ONE_WAY_NO else LaneletSettings.ONE_WAY_YES

    fun selectOneWay(oneWay: String) {
        if (oneWay == LaneletSettings.ONE_WAY_NO) {
            radOwNo.isSelected = true
        } else {
            radOwYes.isSelected = true
        }
    }

    companion object {
        const val ID = "editing-defaults"
        val KEYS = setOf(
            LaneletSettings.KEY_LANELET_DEFAULT_SUBTYPE,
            LaneletSettings.KEY_LANELET_DEFAULT_LOCATION,
            LaneletSettings.KEY_LANELET_DEFAULT_ONE_WAY,
        )

        internal val otherSubtypes = LaneletSettings.LANELET_SUBTYPES.filter {
            it !in listOf("road", "bicycle_lane", "crosswalk")
        }

        fun addTo(content: JPanel): EditingDefaultsControls {
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

            return EditingDefaultsControls(
                radRoad, radBicycle, radCrosswalk, radOther, comboOther,
                radUrban, radNonurban, radOwYes, radOwNo,
            )
        }
    }
}

/** Node heights: auto-height for new nodes, height-jump warning threshold. */
class HeightControls internal constructor(
    val chkAutoHeight: JCheckBox,
    val jumpField: JTextField,
) {
    fun save() {
        LaneletSettings.setAutoHeightEnabled(chkAutoHeight.isSelected)
        val m = jumpField.text.trim().replace(',', '.').toDoubleOrNull()
        if (m != null && m > 0) LaneletSettings.setHeightJumpWarnM(m)
    }

    companion object {
        const val ID = "heights"
        val KEYS = setOf(LaneletSettings.KEY_AUTOHEIGHT_ENABLED, LaneletSettings.KEY_HEIGHT_JUMP_WARN_M)

        fun addTo(content: JPanel): HeightControls {
            content.add(JLabel(" "))
            content.add(JLabel("Heights (ele):"))
            val chkAutoHeight = JCheckBox(
                "New nodes take the height of the nearest node",
                LaneletSettings.isAutoHeightEnabled(),
            )
            chkAutoHeight.toolTipText =
                "A node created without ele gets the ele of the closest existing node that has one, " +
                    "as its own undo step, so new ways do not drop to 0 m in maps with absolute heights."
            val autoRow = JPanel(FlowLayout(FlowLayout.LEFT))
            autoRow.add(chkAutoHeight)
            content.add(autoRow)

            val jumpField = JTextField(HeightTools.formatEle(LaneletSettings.getHeightJumpWarnM()), 5)
            jumpField.toolTipText =
                "Warn when new-node heights, height interpolation or a 3D viewer move leave neighbouring " +
                    "nodes of a way this far apart in height."
            val jumpRow = JPanel(FlowLayout(FlowLayout.LEFT))
            jumpRow.add(JLabel("Warn about height jumps between neighbouring nodes above (m):"))
            jumpRow.add(jumpField)
            content.add(jumpRow)
            return HeightControls(chkAutoHeight, jumpField)
        }
    }
}

class EditingOptionsControls internal constructor(
    val chkDeleteTagged: JCheckBox,
    val chkProtectAnchors: JCheckBox,
    val chkCollectionDialog: JCheckBox,
) {
    fun save() {
        LaneletSettings.setDeleteTaggedNodes(chkDeleteTagged.isSelected)
        LaneletSettings.setProtectMergeAnchors(chkProtectAnchors.isSelected)
        LaneletSettings.setCollectionDialogEnabled(chkCollectionDialog.isSelected)
    }

    companion object {
        const val ID = "editing-options"
        val KEYS = setOf(
            LaneletSettings.KEY_DELETE_TAGGED_NODES,
            LaneletSettings.KEY_PROTECT_MERGE_ANCHORS,
            LaneletSettings.KEY_COLLECTION_DIALOG,
        )

        fun addTo(content: JPanel): EditingOptionsControls {
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

            val chkCollectionDialog = JCheckBox(
                "Use collection dialog (Select Lanelets / Relations / regulatory wizards)",
                LaneletSettings.isCollectionDialogEnabled(),
            )
            chkCollectionDialog.toolTipText =
                "When on, Select Lanelets, Select Relations, and the regulatory-element " +
                    "wizards open the incremental Add / Select / Done collector instead of " +
                    "using the current JOSM selection."
            val collectionRow = JPanel(FlowLayout(FlowLayout.LEFT))
            collectionRow.add(chkCollectionDialog)
            content.add(collectionRow)

            return EditingOptionsControls(chkDeleteTagged, chkProtectAnchors, chkCollectionDialog)
        }
    }
}

class MergeGridControls internal constructor(
    val gridCellSpinner: JSpinner,
) {
    fun save() {
        try {
            LaneletSettings.setMergeGridCellM((gridCellSpinner.value as Number).toDouble())
        } catch (_: Exception) {
            LaneletSettings.setMergeGridCellM(LaneletSettings.MERGE_GRID_CELL_M_DEFAULT)
        }
    }

    companion object {
        const val ID = "merge-grid"
        val KEYS = setOf(LaneletSettings.KEY_MERGE_GRID_CELL_M)

        fun addTo(content: JPanel): MergeGridControls {
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
            return MergeGridControls(gridCellSpinner)
        }
    }
}

object MapStylesPresetsControls {
    const val ID = "mapstyles-presets"
    val KEYS: Set<String> = emptySet()

    fun addTo(content: JPanel, dialogParent: () -> Component?) {
        content.add(JLabel(" "))
        content.add(JLabel("Map paint styles:"))
        val mapStylesRow = JPanel(FlowLayout(FlowLayout.LEFT))
        val mapStylesBtn = JButton("Map Styles...")
        mapStylesBtn.toolTipText =
            "Configure bundled LL2 MapCSS styles, apply presets, and reorder styles."
        mapStylesBtn.addActionListener {
            try {
                MapStylesDialog.show(dialogParent())
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
                PresetsDialog.show(dialogParent())
            } catch (ex: Exception) {
                Dialogs.error("Could not open Presets dialog:\n$ex", "Lanelet2 Presets")
            }
        }
        presetsRow.add(presetsBtn)
        content.add(presetsRow)
    }
}

internal fun newBoxContent(): JPanel {
    val content = JPanel()
    content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
    return content
}
