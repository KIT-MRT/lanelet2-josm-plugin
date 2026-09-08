package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendSetupWizard
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.WindowConstants

/**
 * Routing settings panel injected by the Jython `ll2_dependent` tier into the
 * core settings window. The rest of that window is not ported yet; this dialog
 * is the home for participant / debounce / hook-full-map plus the backends
 * wizard button.
 *
 * Debounce spinner shows seconds via **integer division** of milliseconds
 * (`ms / 1000`), matching Jython 2.7 (`int(ms) / 1000` truncates).
 */
object RoutingSettings {
    const val TITLE = "Lanelet2 Settings"

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        val action = object : LaneletAction(TITLE, "icons/settings.svg", TITLE, null) {
            override fun actionPerformed(e: ActionEvent) = show()
        }
        registry.register(
            ActionSlot(
                id = "settings_ui.lanelet2_settings_window",
                action = action,
                toolbarLabel = "Settings",
                iconName = "icons/settings.svg",
                menu = MenuId.UTILS,
            ),
        )
    }

    fun show() {
        if (Dialogs.isHeadless()) return
        val parent = try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        val dlg = JDialog(parent, TITLE, true)
        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val content = JPanel(GridLayout(0, 1, 0, 4))
        val onOk = addPanel(content)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        val ok = JButton("OK")
        val cancel = JButton("Cancel")
        ok.addActionListener {
            onOk()
            dlg.dispose()
        }
        cancel.addActionListener { dlg.dispose() }
        buttons.add(ok)
        buttons.add(cancel)
        val wrap = JPanel()
        wrap.layout = javax.swing.BoxLayout(wrap, javax.swing.BoxLayout.Y_AXIS)
        wrap.add(content)
        wrap.add(buttons)
        dlg.add(wrap)
        dlg.pack()
        dlg.setLocationRelativeTo(parent)
        dlg.isVisible = true
    }

    /**
     * Add routing rows to [content] and return the persist-on-OK callback.
     * Port of `ll2_routing_settings.build_routing_panel`.
     */
    fun addPanel(content: JPanel): () -> Unit {
        content.add(JLabel(" "))
        content.add(JLabel("Routing (lanelet2 backend):"))

        val routingRow = JPanel(FlowLayout(FlowLayout.LEFT))
        routingRow.add(JLabel("Default routing participant:"))
        val combo = JComboBox(LaneletSettings.ROUTING_PARTICIPANTS.toTypedArray())
        combo.selectedItem = LaneletSettings.getRoutingDefaultParticipant()
        routingRow.add(combo)
        content.add(routingRow)

        val debounceSec = LaneletSettings.getRoutingAutoDebounceMs() / 1000
        val debounceModel = SpinnerNumberModel(debounceSec, 0, 60, 1)
        val debounceSpinner = JSpinner(debounceModel)
        debounceSpinner.toolTipText =
            "Delay before save + routing graph refresh after lanelet edits. " +
                "0 = immediate. Manual Ctrl+Shift+H always runs immediately."
        val debounceRow = JPanel(FlowLayout(FlowLayout.LEFT))
        debounceRow.add(JLabel("Auto routing graph debounce (seconds):"))
        debounceRow.add(debounceSpinner)
        content.add(debounceRow)

        val chkHookFull = JCheckBox(
            "Auto routing graph uses full map (save + debounce; off = viewport subset)",
            LaneletSettings.getRoutingHookFullMap(),
        )
        chkHookFull.toolTipText =
            "When off (default), auto-triggered routing uses a buffered viewport " +
                "extract (_small layer, 300 ms coalesce, no save). When on, uses the " +
                "full map with the debounce setting above."
        val hookFullRow = JPanel(FlowLayout(FlowLayout.LEFT))
        hookFullRow.add(chkHookFull)
        content.add(hookFullRow)

        val backendsRow = JPanel(FlowLayout(FlowLayout.LEFT))
        val backendsBtn = JButton("Set up Lanelet2 backends")
        backendsBtn.toolTipText =
            "Create a private virtualenv and pip-install lanelet2, or point at an existing interpreter."
        backendsBtn.addActionListener {
            try {
                BackendSetupWizard.show()
            } catch (ex: Exception) {
                Dialogs.error("Could not open backend settings:\n$ex", "Set up Lanelet2 backends")
            }
        }
        backendsRow.add(backendsBtn)
        content.add(backendsRow)

        val chkReminder = JCheckBox(
            "Remind me to commit / push every 60 minutes (off by default)",
            LaneletSettings.getGitCommitReminder(),
        )
        chkReminder.toolTipText =
            "When on, a timer asks whether to run Git Commit if you have not committed " +
                "in the last hour or have unpushed commits. The Jython always started this; " +
                "the rest of the internal launcher hooks are not ported yet."
        val reminderRow = JPanel(FlowLayout(FlowLayout.LEFT))
        reminderRow.add(chkReminder)
        content.add(reminderRow)

        return {
            LaneletSettings.setRoutingDefaultParticipant(combo.selectedItem?.toString())
            val debounceMs = try {
                (debounceSpinner.value as Number).toInt() * 1000
            } catch (_: Exception) {
                LaneletSettings.ROUTING_AUTO_DEBOUNCE_MS_DEFAULT
            }
            LaneletSettings.setRoutingAutoDebounceMs(debounceMs)
            LaneletSettings.setRoutingHookFullMap(chkHookFull.isSelected)
            LaneletSettings.setGitCommitReminder(chkReminder.isSelected)
        }
    }
}
