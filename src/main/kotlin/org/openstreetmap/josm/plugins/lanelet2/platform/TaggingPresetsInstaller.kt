package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.data.preferences.sources.PresetPrefHelper
import org.openstreetmap.josm.data.preferences.sources.SourceEntry
import org.openstreetmap.josm.data.preferences.sources.SourceType
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets
import org.openstreetmap.josm.tools.Logging

object TaggingPresetsInstaller {
    const val PRESET_URL = "resource://lanelet2/ll2_editor_presets.xml"

    fun installOnLaunch() {
        if (!LaneletSettings.getPresetsAutoInstallOnLaunch()) return
        installPresets()
    }

    fun isInstalled(): Boolean = persistedSources().any { isOurSource(it) }

    fun installPresets(): Boolean {
        var changed = false
        if (!isInstalled()) {
            val entries = ArrayList(persistedSources())
            entries.add(
                SourceEntry(
                    SourceType.TAGGING_PRESET,
                    PRESET_URL,
                    null,
                    LaneletSettings.PRESETS_SOURCE_TITLE,
                    true,
                ),
            )
            try {
                PresetPrefHelper.INSTANCE.put(entries)
                changed = true
            } catch (e: Exception) {
                Logging.error("lanelet2: failed to register tagging presets")
                Logging.error(e)
                return false
            }
        }
        if (changed) {
            reloadTaggingPresets()
            // TODO: pin selected LL2 tagging-preset items onto the JOSM main toolbar
            // (Jython sync_toolbar_from_settings / apply_minimal_josm_toolbar).
        }
        return changed
    }

    private fun isOurSource(entry: SourceEntry): Boolean {
        if (entry.title == LaneletSettings.PRESETS_SOURCE_TITLE) return true
        val url = entry.url ?: return false
        if (url == PRESET_URL) return true
        return url.endsWith(LaneletSettings.PRESETS_FILE_NAME)
    }

    private fun persistedSources(): List<SourceEntry> = try {
        PresetPrefHelper.INSTANCE.get()
    } catch (_: Exception) {
        emptyList()
    }

    private fun reloadTaggingPresets() {
        try {
            if (MainApplication.getMenu() == null) return
            TaggingPresets.destroy()
            TaggingPresets.initialize()
        } catch (e: Exception) {
            try {
                TaggingPresets.readFromPreferences()
            } catch (e2: Exception) {
                Logging.error("lanelet2: failed to reload tagging presets")
                Logging.error(e2)
            }
        }
    }
}
