package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendSetupWizard
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Routing settings rows injected into [SettingsWindow] / [Lanelet2PreferenceSetting].
 * Port of `ll2_dependent/scripts/ll2_routing_settings.build_routing_panel`, plus the
 * commit-reminder checkbox from the internal launcher (settings-gated; the
 * Jython timer itself is not ported).
 *
 * Debounce spinner shows seconds via **integer division** of milliseconds
 * (`ms / 1000`), matching Jython 2.7 (`int(ms) / 1000` truncates).
 */
class RoutingControls internal constructor(
    val participantCombo: JComboBox<String>,
    val debounceSpinner: JSpinner,
    val hookFull: JCheckBox,
    val reminder: JCheckBox,
) {
    fun save() {
        LaneletSettings.setRoutingDefaultParticipant(participantCombo.selectedItem?.toString())
        val debounceMs = try {
            (debounceSpinner.value as Number).toInt() * 1000
        } catch (_: Exception) {
            LaneletSettings.ROUTING_AUTO_DEBOUNCE_MS_DEFAULT
        }
        LaneletSettings.setRoutingAutoDebounceMs(debounceMs)
        LaneletSettings.setRoutingHookFullMap(hookFull.isSelected)
        LaneletSettings.setGitCommitReminder(reminder.isSelected)
    }

    companion object {
        const val ID = "routing"
        val KEYS = setOf(
            LaneletSettings.KEY_ROUTING_DEFAULT_PARTICIPANT,
            LaneletSettings.KEY_ROUTING_AUTO_DEBOUNCE_MS,
            LaneletSettings.KEY_ROUTING_HOOK_FULL_MAP,
            LaneletSettings.KEY_GIT_COMMIT_REMINDER,
        )
    }
}

object RoutingPanel {
    fun addPanel(content: JPanel): () -> Unit = addControls(content)::save

    fun addControls(content: JPanel): RoutingControls {
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

        return RoutingControls(combo, debounceSpinner, chkHookFull, chkReminder)
    }
}
