package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Autotag widgets + persist path shared by [AutotagNewElements] and the
 * native preferences tab. Writes through [AutotagHook] so enable toggles
 * install / uninstall the hook.
 */
class AutotagSettingsPanel internal constructor(
    val component: JPanel,
    val enabledBox: JCheckBox,
    val textArea: JTextArea,
) {
    fun save() {
        val pairs = AutotagLogic.textToTags(textArea.text ?: "")
        AutotagHook.setTags(pairs)
        AutotagHook.setEnabled(enabledBox.isSelected)
    }

    companion object {
        const val ID = "autotag"
        val KEYS = setOf(
            LaneletSettings.KEY_AUTOTAG_ENABLED,
            LaneletSettings.KEY_AUTOTAG_TAGS,
        )

        fun create(dialogParent: () -> Component?): AutotagSettingsPanel {
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
                val sel = AutotagNewElements.showFileOriginPicker(
                    dialogParent(),
                    AutotagHook.existingFileOrigins(),
                )
                if (sel != null) {
                    AutotagNewElements.applyFileOriginToText(textArea, sel)
                }
            }
            south.add(choose)
            panel.add(south, BorderLayout.SOUTH)
            return AutotagSettingsPanel(panel, enabledBox, textArea)
        }
    }
}
