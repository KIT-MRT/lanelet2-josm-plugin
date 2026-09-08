package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
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

        var dialogParent: java.awt.Component? = null
        val content = newBoxContent()
        val form = SettingsForm.standalone(content) { dialogParent }

        dlg.add(content, BorderLayout.CENTER)
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

        dlg.setSize(520, 720)
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }
}
