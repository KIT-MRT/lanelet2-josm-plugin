package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.WindowConstants

/**
 * Lanelet2 Settings window. Port of `core/settings_ui/lanelet2_settings_window.py`
 * with [RoutingPanel] injected the way `ll2_routing_settings.register_settings_extension`
 * does in Jython.
 *
 * Widgets and persist live in [SettingsForm]; this is only the standalone dialog shell.
 */
object SettingsWindow {
    const val TITLE = "Lanelet2 Settings"
    const val MIN_WIDTH = 560
    const val MIN_HEIGHT = 480
    const val PREFERRED_WIDTH = 580

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
        dlg.isResizable = true

        var dialogParent: java.awt.Component? = null
        val content = newBoxContent()
        content.border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        val form = SettingsForm.standalone(content) { dialogParent }

        dlg.add(scrollPane(content), BorderLayout.CENTER)
        dialogParent = dlg

        val btnPanel = JPanel(FlowLayout())
        val okBtn = JButton("OK")
        val cancelBtn = JButton("Cancel")
        okBtn.addActionListener {
            form.save()
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

        val size = dialogSize(Toolkit.getDefaultToolkit().screenSize)
        dlg.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        dlg.setSize(size)
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    /**
     * Cap the window to most of the screen. The form sits in a scroll pane so
     * sections below the fold stay reachable on short displays.
     */
    fun dialogSize(
        screen: Dimension,
        preferredHeight: Int = 900,
        screenFraction: Double = 0.85,
    ): Dimension {
        val maxH = maxOf(MIN_HEIGHT, (screen.height * screenFraction).toInt())
        val maxW = maxOf(MIN_WIDTH, (screen.width * 0.9).toInt())
        return Dimension(
            PREFERRED_WIDTH.coerceAtMost(maxW).coerceAtLeast(MIN_WIDTH),
            preferredHeight.coerceAtMost(maxH).coerceAtLeast(MIN_HEIGHT),
        )
    }

    fun scrollPane(content: JPanel): JScrollPane {
        val scroll = JScrollPane(content)
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.verticalScrollBar.unitIncrement = 16
        return scroll
    }
}
