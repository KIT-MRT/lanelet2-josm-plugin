package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.data.preferences.sources.MapPaintPrefHelper
import org.openstreetmap.josm.data.preferences.sources.SourceEntry
import org.openstreetmap.josm.data.preferences.sources.SourceType
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles
import org.openstreetmap.josm.gui.mappaint.StyleSource
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.tools.Logging

object MapStyles {
    /**
     * JOSM's search path for map paint style icons. Our styles reference icons
     * relatively (`style_images/133-10.png`), and unlike presets, a style's own
     * `resource://` URL is not part of the search path, so the traffic sign,
     * arrow and traffic light icons stay unresolved unless we add the root here.
     */
    private const val ICON_SOURCES_KEY = "mappaint.icon.sources"

    fun installOnLaunch() {
        val iconSourceAdded = ensureIconSource()
        ensureStylesRegistered()
        if (LaneletSettings.getMapstyleAutoApplyOnLaunch()) {
            applyPreset(LaneletSettings.MAPSTYLE_PRESET_LL2_EDITING)
        } else if (iconSourceAdded) {
            // Styles cache their icon lookups, so a profile that already loaded
            // them keeps rendering the misses until the sources are re-read.
            reloadStyles()
        }
    }

    private fun reloadStyles() {
        try {
            MapPaintStyles.readFromPreferences()
            repaintMap()
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to reload map styles after icon source change")
            Logging.error(e)
        }
    }

    /**
     * Register the plugin's icon root with JOSM's style icon search path.
     * Idempotent.
     *
     * @return true if the search path was modified.
     */
    fun ensureIconSource(): Boolean {
        return try {
            val current = Config.getPref().getList(ICON_SOURCES_KEY, emptyList())
            if (TaggingPresetsInstaller.ICON_SOURCE in current) return false
            Config.getPref().putList(ICON_SOURCES_KEY, current + TaggingPresetsInstaller.ICON_SOURCE)
            true
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to register map style icon source")
            Logging.error(e)
            false
        }
    }

    fun ensureStylesRegistered(): Boolean {
        var changed = false
        ensureIconSource()
        for (entry in LaneletSettings.STYLE_CATALOG) {
            if (entry.kind != StyleKind.FILE && entry.kind != StyleKind.BUILTIN) continue
            if (isRegistered(entry)) continue
            val se = makeSourceEntry(entry, active = false) ?: continue
            try {
                MapPaintStyles.addStyle(se)
                changed = true
            } catch (e: Exception) {
                Logging.error("lanelet2: failed to register map style {0}", entry.title)
                Logging.error(e)
            }
        }
        return changed
    }

    fun applyPreset(presetId: String): Boolean {
        val activeIds = LaneletSettings.STYLE_PRESETS[presetId] ?: return false
        ensureStylesRegistered()
        val sources = styleSources()
        val ordered = ArrayList<SourceEntry>(sources.size)
        val used = HashSet<StyleSource>()

        for (entry in LaneletSettings.STYLE_CATALOG) {
            val src = findStyleSource(sources, entry) ?: continue
            src.active = entry.id in activeIds
            ordered.add(src)
            used.add(src)
        }
        for (src in sources) {
            if (src !in used) {
                src.active = false
                ordered.add(src)
            }
        }
        return try {
            MapPaintPrefHelper.INSTANCE.put(ordered)
            MapPaintStyles.readFromPreferences()
            LaneletSettings.setMapstyleLastPreset(presetId)
            repaintMap()
            true
        } catch (e: Exception) {
            Logging.error("lanelet2: failed to apply map style preset {0}", presetId)
            Logging.error(e)
            false
        }
    }

    fun resolveUrl(entry: StyleCatalogEntry): String? = resolveStyleUrl(entry)

    fun getCurrentStyleRows(): List<StyleSource> = styleSources()

    /** Toggle active flag for the style at [index]. Returns true if toggled. */
    fun toggleStyleAtIndex(index: Int): Boolean {
        val idx = index
        val sources = styleSources()
        if (idx < 0 || idx >= sources.size) return false
        MapPaintStyles.toggleStyleActive(idx)
        LaneletSettings.setMapstyleLastPreset(LaneletSettings.MAPSTYLE_PRESET_CUSTOM)
        return true
    }

    private fun isRegistered(entry: StyleCatalogEntry): Boolean {
        val title = entry.title
        val url = resolveStyleUrl(entry)
        if (persistedSources().any { matches(it, title, url) }) return true
        if (styleSources().any { matches(it, title, url) }) return true
        return false
    }

    private fun matches(src: SourceEntry, title: String, url: String?): Boolean {
        if (styleTitle(src) == title) return true
        if (url != null && src.url == url) return true
        return false
    }

    private fun makeSourceEntry(entry: StyleCatalogEntry, active: Boolean): SourceEntry? {
        return when (entry.kind) {
            StyleKind.FILE -> {
                val url = resolveStyleUrl(entry) ?: return null
                SourceEntry(SourceType.MAP_PAINT_STYLE, url, null, entry.title, active)
            }
            StyleKind.BUILTIN -> {
                val url = entry.url ?: return null
                SourceEntry(SourceType.MAP_PAINT_STYLE, url, entry.ptoken, entry.title, active)
            }
            StyleKind.DYNAMIC -> null
        }
    }

    private fun findStyleSource(sources: List<StyleSource>, entry: StyleCatalogEntry): StyleSource? {
        val key = catalogMatchKey(entry)
        for (src in sources) {
            if (sourceMatchKey(src) == key) return src
        }
        if (entry.kind == StyleKind.FILE || entry.kind == StyleKind.DYNAMIC) {
            val title = entry.title
            if (title.isNotEmpty()) {
                for (src in sources) {
                    if (styleTitle(src) == title) return src
                }
            }
        }
        return null
    }

    private fun catalogMatchKey(entry: StyleCatalogEntry): Pair<String, String> = when (entry.kind) {
        StyleKind.FILE -> "url" to (resolveStyleUrl(entry) ?: "")
        StyleKind.BUILTIN -> "url" to (entry.url ?: "")
        StyleKind.DYNAMIC -> {
            val ptoken = entry.ptoken
            if (!ptoken.isNullOrBlank()) "ptoken" to ptoken else "title" to entry.title
        }
    }

    private fun sourceMatchKey(src: SourceEntry): Pair<String, String> {
        val url = src.url ?: ""
        if (url.startsWith("resource://")) return "url" to url
        if (url.isNotEmpty()) return "file" to url
        val ptoken = src.name
        if (!ptoken.isNullOrBlank()) return "ptoken" to ptoken
        return "title" to styleTitle(src)
    }

    private fun styleTitle(src: SourceEntry): String {
        val t = src.title
        if (!t.isNullOrBlank()) return t.trim()
        return src.displayString?.trim().orEmpty()
    }

    private fun styleSources(): List<StyleSource> = try {
        MapPaintStyles.getStyles().styleSources
    } catch (_: Exception) {
        emptyList()
    }

    private fun persistedSources(): List<SourceEntry> = try {
        MapPaintPrefHelper.INSTANCE.get()
    } catch (_: Exception) {
        emptyList()
    }

    private fun repaintMap() {
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }
}
