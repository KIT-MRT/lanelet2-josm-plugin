package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane

/**
 * Native JOSM preferences tab for Lanelet2. Same builders and [LaneletSettings]
 * accessors as [SettingsWindow], plus the autotag / zoom-filter hook sections.
 */
class Lanelet2PreferenceSetting : DefaultTabPreferenceSetting(
    ICON_NAME,
    TITLE,
    DESCRIPTION,
) {
    internal var form: SettingsForm? = null
        private set

    override fun addGui(gui: PreferenceTabbedPane) {
        val panel = newBoxContent()
        form = SettingsForm.preferencesTab(panel) { gui }
        createPreferenceTabWithScrollPane(gui, panel)
    }

    /**
     * Persist through the same [SettingsForm] writers [addGui] installed.
     * No-op if the tab was never opened (JOSM never called [addGui]).
     * @return `false` — none of these settings require a restart
     */
    override fun ok(): Boolean {
        form?.save()
        return false
    }

    internal fun bindForTest(): SettingsForm {
        val panel = newBoxContent()
        val built = SettingsForm.preferencesTab(panel) { null }
        form = built
        return built
    }

    companion object {
        const val ICON_NAME = "lanelet2/settings"
        const val TITLE = "Lanelet2"
        const val DESCRIPTION = "Lanelet2 editing defaults, routing, styles, and background hooks."
    }
}
