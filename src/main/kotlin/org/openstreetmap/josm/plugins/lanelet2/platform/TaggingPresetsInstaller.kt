package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.data.preferences.sources.PresetPrefHelper
import org.openstreetmap.josm.data.preferences.sources.SourceEntry
import org.openstreetmap.josm.data.preferences.sources.SourceType
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets
import org.openstreetmap.josm.tools.Logging

object TaggingPresetsInstaller {
    const val PRESET_URL = "resource://lanelet2/ll2_editor_presets.xml"

    /**
     * Icon search root handed to JOSM for the presets' relative `style_images/...`
     * references. `ImageProvider.getImageUrl` understands the `resource://` scheme
     * and strips it before asking `ResourceProvider`, so a preset icon named
     * `style_images/stop_line.png` resolves to `lanelet2/style_images/stop_line.png`
     * inside the plugin jar.
     */
    const val ICON_SOURCE = "resource://lanelet2/"

    fun installOnLaunch() {
        if (!LaneletSettings.getPresetsAutoInstallOnLaunch()) return
        installPresets()
    }

    fun isInstalled(): Boolean = persistedSources().any { isOurSource(it) }

    /**
     * Register [ICON_SOURCE] with JOSM's preset icon search path. Idempotent.
     *
     * Without this, every relative icon reference in the bundled presets fails to
     * resolve, because JOSM only looks in `TaggingPresets.ICON_SOURCES` plus its
     * own stock locations. The Jython equivalent registered a filesystem
     * directory; the bundled copy lives in the jar instead.
     *
     * @return true if the search path was modified.
     */
    fun ensureIconSource(): Boolean {
        val current = iconSources()
        if (ICON_SOURCE in current) return false
        return try {
            TaggingPresets.ICON_SOURCES.put(current + ICON_SOURCE)
            true
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to register preset icon source")
            Logging.error(e)
            false
        }
    }

    fun installPresets(): Boolean {
        // Must precede loading, so icons resolve on the first pass.
        var changed = ensureIconSource()
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

    private fun iconSources(): List<String> = try {
        TaggingPresets.ICON_SOURCES.get().orEmpty().filter { it.isNotBlank() }
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
