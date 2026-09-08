package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.data.preferences.sources.PresetPrefHelper
import org.openstreetmap.josm.data.preferences.sources.SourceEntry
import org.openstreetmap.josm.data.preferences.sources.SourceType
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.preferences.ToolbarPreferences
import org.openstreetmap.josm.gui.tagging.presets.TaggingPreset
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetMenu
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetReader
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetSeparator
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.tools.Logging

object TaggingPresetsInstaller {
    const val PRESET_URL = "resource://lanelet2/ll2_editor_presets.xml"
    private const val PRESET_NAME_PREFIX = "Lanelet2/"

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
        if (!isInstalled()) {
            installPresets()
        } else {
            if (ensureIconSource()) reloadTaggingPresets()
            afterPresetSetup()
        }
    }

    fun isInstalled(): Boolean = persistedSources().any { isOurSource(it) }

    /**
     * Register [ICON_SOURCE] with JOSM's preset icon search path. Idempotent.
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
            afterPresetSetup()
            return true
        }
        return false
    }

    fun uninstallPresets(): Boolean {
        if (!isInstalled()) return false
        val toolbarStrings = mutableListOf<String>()
        for (preset in listEditorPresets()) {
            try {
                val ts = preset.toolbarString
                if (!ts.isNullOrBlank()) toolbarStrings.add(ts)
            } catch (_: Exception) {
            }
        }
        val entries = persistedSources().filterNot { isOurSource(it) }
        return try {
            PresetPrefHelper.INSTANCE.put(entries)
            removeIconSource()
            removeToolbarStrings(toolbarStrings)
            reloadTaggingPresets()
            true
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to uninstall tagging presets")
            Logging.error(e)
            false
        }
    }

    fun listEditorPresets(): List<TaggingPreset> {
        val out = mutableListOf<TaggingPreset>()
        try {
            for (tp in TaggingPresets.getTaggingPresets()) {
                if (tp is TaggingPresetSeparator || tp is TaggingPresetMenu) continue
                val raw = try {
                    tp.rawName
                } catch (_: Exception) {
                    tp.name
                }
                if (raw != null && raw.startsWith(PRESET_NAME_PREFIX)) {
                    out.add(tp)
                }
            }
        } catch (_: Exception) {
        }
        if (out.isNotEmpty()) return out
        if (isInstalled()) return out
        return listPresetsFromFile()
    }

    fun isOnToolbar(preset: TaggingPreset): Boolean {
        return try {
            val ts = preset.toolbarString ?: return false
            ts in ToolbarPreferences.getToolString()
        } catch (_: Exception) {
            false
        }
    }

    fun setOnToolbar(preset: TaggingPreset, enabled: Boolean): Boolean {
        return try {
            val ts = preset.toolbarString ?: return false
            val tb = MainApplication.getToolbar() ?: return false
            val present = ts in ToolbarPreferences.getToolString()
            val want = enabled
            when {
                want && !present -> {
                    tb.addCustomButton(ts, -1, false)
                    true
                }
                !want && present -> {
                    tb.addCustomButton(ts, -1, true)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun applyMinimalJosmToolbar(): Boolean = setToolbarList(LaneletSettings.LL2_MINIMAL_JOSM_TOOLBAR)

    fun restoreJosmDefaultToolbar(preserveLl2Presets: Boolean = true): Boolean {
        val ok = setToolbarList(LaneletSettings.JOSM_DEFAULT_TOOLBAR)
        if (ok && preserveLl2Presets && isInstalled()) {
            syncToolbarFromSettings()
        }
        return ok
    }

    fun syncToolbarFromSettings() {
        val enabled = LaneletSettings.getToolbarPresetNames().toSet()
        for (tp in listEditorPresets()) {
            val nm = tp.name
            setOnToolbar(tp, nm in enabled)
        }
    }

    fun applyToolbarDefaults() {
        val names = LaneletSettings.getToolbarPresetNames()
        val enabled = names.toSet()
        for (tp in listEditorPresets()) {
            val nm = tp.name
            setOnToolbar(tp, nm in enabled)
        }
        LaneletSettings.setToolbarPresetNames(enabled.toList())
    }

    private fun afterPresetSetup() {
        applyMinimalJosmToolbar()
        syncToolbarFromSettings()
    }

    private fun listPresetsFromFile(): List<TaggingPreset> {
        return try {
            TaggingPresetReader.readAll(PRESET_URL, false)
                .filter { it !is TaggingPresetSeparator && it !is TaggingPresetMenu }
                .map { it as TaggingPreset }
        } catch (e: Exception) {
            Logging.debug("lanelet2: could not read bundled presets: {0}", e.message)
            emptyList()
        }
    }

    private fun setToolbarList(items: List<String>): Boolean {
        return try {
            Config.getPref().putList("toolbar", items)
            MainApplication.getToolbar()?.refreshToolbarControl()
            true
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to set toolbar list")
            Logging.error(e)
            false
        }
    }

    private fun removeToolbarStrings(strings: List<String>) {
        val tb = MainApplication.getToolbar() ?: return
        for (ts in strings) {
            try {
                tb.addCustomButton(ts, -1, true)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeIconSource(): Boolean {
        val kept = iconSources().filter { it != ICON_SOURCE }
        if (kept.size == iconSources().size) return false
        return try {
            TaggingPresets.ICON_SOURCES.put(kept)
            true
        } catch (_: Exception) {
            false
        }
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
