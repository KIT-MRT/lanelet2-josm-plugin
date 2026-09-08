package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class StyleKind { BUILTIN, FILE, DYNAMIC }

data class StyleCatalogEntry(
    val id: String,
    val kind: StyleKind,
    val title: String,
    val url: String? = null,
    val file: String? = null,
    val ptoken: String? = null,
)

data class LaneletCreateTags(
    val subtype: String,
    val location: String?,
    val oneWay: String?,
)

object LaneletSettings {
    const val PREFIX = "lanelet2."

    const val KEY_ROUTING_AUTO_DEBOUNCE_MS = "routing.auto_debounce_ms"
    const val KEY_ROUTING_HOOK_FULL_MAP = "routing.hook_full_map"
    const val KEY_ROUTING_DEFAULT_PARTICIPANT = "routing.default_participant"
    const val KEY_LANELET_DEFAULT_SUBTYPE = "lanelet.default_subtype"
    const val KEY_LANELET_DEFAULT_LOCATION = "lanelet.default_location"
    const val KEY_LANELET_DEFAULT_ONE_WAY = "lanelet.default_one_way"
    const val KEY_PROTECT_MERGE_ANCHORS = "merge_anchor.protect"
    const val KEY_DELETE_TAGGED_NODES = "delete.tagged_nodes"
    const val KEY_MERGE_GRID_CELL_M = "merge.grid_cell_m"
    const val KEY_HIGHLIGHT_GROUP_TAG = "highlight_file_boundaries.group_tag"
    const val KEY_MERGE_LAST_INPUT_DIR = "merge.last_input_dir"
    const val KEY_MERGE_LAST_OUTPUT_DIR = "merge.last_output_dir"
    const val KEY_SPLIT_LAST_OUTPUT_DIR = "split.last_output_dir"
    const val KEY_BACKENDS_PYTHON = "backends.python"
    const val KEY_BACKENDS_DIR = "backends.dir"
    const val KEY_BACKENDS_ENV_SCRIPT = "backends.env_script"
    const val KEY_GIT_COMMIT_REMINDER = "git.commit_reminder"
    const val KEY_MAPSTYLE_AUTO_APPLY_ON_LAUNCH = "mapstyle.auto_apply_on_launch"
    const val KEY_MAPSTYLE_LAST_PRESET = "mapstyle.last_preset"
    const val KEY_PRESETS_AUTO_INSTALL_ON_LAUNCH = "presets.auto_install_on_launch"
    const val KEY_PRESETS_TOOLBAR_NAMES = "presets.toolbar.names"
    const val KEY_MIGRATED = "migrated"
    const val KEY_AUTOTAG_ENABLED = "autotag.enabled"
    const val KEY_AUTOTAG_TAGS = "autotag.tags"
    const val KEY_ZOOMFILTER_ENABLED = "zoomfilter.enabled"
    const val KEY_ZOOMFILTER_THRESHOLD = "zoomfilter.threshold"
    const val KEY_ZOOMFILTER_FILTERS = "zoomfilter.filters"
    const val KEY_COLLECTION_DIALOG = "collection_dialog.enabled"
    const val KEY_EXTRA_TOOLBAR_VISIBLE = "toolbar.extra_visible"

    const val ROUTING_AUTO_DEBOUNCE_MS_DEFAULT = 4000
    const val ROUTING_AUTO_DEBOUNCE_MS_MIN = 0
    const val ROUTING_AUTO_DEBOUNCE_MS_MAX = 60000

    const val MERGE_GRID_CELL_M_DEFAULT = 60.0
    const val MERGE_GRID_CELL_M_MIN = 5.0
    const val MERGE_GRID_CELL_M_MAX = 500.0

    const val ONE_WAY_YES = "yes"
    const val ONE_WAY_NO = "no"

    const val LOC_URBAN = "urban"
    const val LOC_NONURBAN = "nonurban"

    const val MAPSTYLE_PRESET_LL2_EDITING = "ll2_editing"
    const val MAPSTYLE_PRESET_JOSM_DEFAULT = "josm_default"
    const val MAPSTYLE_PRESET_CUSTOM = "custom"

    const val PRESETS_FILE_NAME = "ll2_editor_presets.xml"
    const val PRESETS_SOURCE_TITLE = "LL2 Editor Presets"

    val ROUTING_PARTICIPANTS = listOf("vehicle", "bicycle", "pedestrian", "train")

    val LANELET_ONE_WAY_VALUES = listOf(ONE_WAY_YES, ONE_WAY_NO)
    val LANELET_LOCATIONS = listOf(LOC_URBAN, LOC_NONURBAN)
    val MAPSTYLE_PRESETS = listOf(
        MAPSTYLE_PRESET_LL2_EDITING,
        MAPSTYLE_PRESET_JOSM_DEFAULT,
        MAPSTYLE_PRESET_CUSTOM,
    )

    val LANELET_SUBTYPES = listOf(
        "road",
        "highway",
        "play_street",
        "emergency_lane",
        "bus_lane",
        "bicycle_lane",
        "exit",
        "walkway",
        "shared_walkway",
        "crosswalk",
        "stairs",
    )

    val SUBTYPES_WITH_LOCATION = setOf("road", "highway", "bus_lane", "exit")

    val SUBTYPE_TOOLBAR_PRESETS = setOf("road", "bicycle_lane", "crosswalk")

    val STYLE_CATALOG: List<StyleCatalogEntry> = listOf(
        StyleCatalogEntry(
            id = "josm_standard",
            kind = StyleKind.BUILTIN,
            url = "resource://styles/standard/elemstyles.mapcss",
            title = "JOSM default (MapCSS)",
            ptoken = "standard",
        ),
        StyleCatalogEntry(
            id = "ll2_lanelets",
            kind = StyleKind.FILE,
            file = "lanelets.mapcss",
            title = "LL2_Style",
        ),
        StyleCatalogEntry(
            id = "ll2_lines",
            kind = StyleKind.FILE,
            file = "lines.mapcss",
            title = "LL2_Lines_Style",
        ),
        StyleCatalogEntry(
            id = "ll2_routing",
            kind = StyleKind.FILE,
            file = "routing.mapcss",
            title = "LL2_Routing_Style",
        ),
        StyleCatalogEntry(
            id = "ll2_debug_routing",
            kind = StyleKind.FILE,
            file = "debug_routing_graph.mapcss",
            title = "LL2_Debug_Routing_Graph",
        ),
        StyleCatalogEntry(
            id = "ll2_file_boundaries",
            kind = StyleKind.DYNAMIC,
            title = "Lanelet2 File Boundaries",
            ptoken = "ll2_file_boundaries",
        ),
        StyleCatalogEntry(
            id = "ll2_notes",
            kind = StyleKind.DYNAMIC,
            title = "Lanelet2 Notes",
            ptoken = "ll2_notes",
        ),
    )

    val STYLE_PRESETS: Map<String, Set<String>> = mapOf(
        MAPSTYLE_PRESET_LL2_EDITING to setOf("ll2_lines", "ll2_file_boundaries", "ll2_notes"),
        MAPSTYLE_PRESET_JOSM_DEFAULT to setOf("josm_standard"),
    )

    val DEFAULT_TOOLBAR_PRESET_NAMES = listOf(
        "Virtual line",
        "Dashed line",
        "Solid line",
        "Pedestrian marking",
        "Road border",
        "Stop line",
        "Bike dashed",
        "Bike solid",
        "Curbstone high",
        "Curbstone low: private property or restricted area",
        "Curbstone low: sidewalk entry for bikes and pedestrians",
        "Curbstone low: road side-entry",
        "drivable_space_border",
    )

    val LL2_MINIMAL_JOSM_TOOLBAR = listOf(
        "open", "save", "|", "undo", "redo", "|", "dialogs/search", "preference",
    )

    val JOSM_DEFAULT_TOOLBAR = listOf(
        "open", "save", "download", "upload", "|", "undo", "redo", "|",
        "dialogs/search", "preference", "|", "splitway", "combineway", "wayflip", "|",
        "imagery-offset", "|",
        "tagginggroup_Highways/Streets", "tagginggroup_Highways/Ways",
        "tagginggroup_Highways/Waypoints", "tagginggroup_Highways/Barriers", "|",
        "tagginggroup_Transport/Car", "tagginggroup_Transport/Public Transport", "|",
        "tagginggroup_Facilities/Tourism", "tagginggroup_Facilities/Food+Drinks", "|",
        "tagginggroup_Man Made/Historic Places", "|",
        "tagginggroup_Man Made/Man Made",
    )

    private val laneletDefaultUiListeners = CopyOnWriteArrayList<Runnable>()

    fun prefKey(key: String): String =
        if (key.startsWith(PREFIX)) key else PREFIX + key

    fun get(key: String, default: String): String {
        val value = pref().get(prefKey(key), null)
        return value ?: default
    }

    fun put(key: String, value: String?) {
        pref().put(prefKey(key), value ?: "")
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        val raw = pref().get(prefKey(key), null) ?: return default
        return parseBoolean(raw, default)
    }

    fun putBoolean(key: String, value: Boolean) {
        put(key, if (value) "1" else "0")
    }

    fun getInt(key: String, default: Int): Int {
        val raw = pref().get(prefKey(key), null) ?: return default
        return raw.toIntOrNull() ?: default
    }

    fun putInt(key: String, value: Int) {
        put(key, value.toString())
    }

    fun getList(key: String, default: List<String>): List<String> {
        val pk = prefKey(key)
        val fromList = pref().getList(pk, null)
        if (fromList != null && fromList.isNotEmpty()) {
            return fromList.toList()
        }
        val raw = pref().get(pk, null)
        if (raw.isNullOrBlank()) return default
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { default }
    }

    fun putList(key: String, value: List<String>) {
        pref().putList(prefKey(key), value)
    }

    fun addChangeListener(listener: PreferenceChangedListener) {
        pref().addPreferenceChangeListener(listener)
    }

    fun addChangeListener(key: String, listener: PreferenceChangedListener) {
        pref().addKeyPreferenceChangeListener(prefKey(key), listener)
    }

    fun removeChangeListener(listener: PreferenceChangedListener) {
        pref().removePreferenceChangeListener(listener)
    }

    fun removeChangeListener(key: String, listener: PreferenceChangedListener) {
        pref().removeKeyPreferenceChangeListener(prefKey(key), listener)
    }

    fun registerLaneletDefaultUiListener(listener: Runnable) {
        if (!laneletDefaultUiListeners.contains(listener)) {
            laneletDefaultUiListeners.add(listener)
        }
    }

    fun unregisterLaneletDefaultUiListener(listener: Runnable) {
        laneletDefaultUiListeners.remove(listener)
    }

    fun migrateLegacyFileOnce() {
        migrateLegacyFileOnce(File(System.getProperty("user.home"), ".lanelet2_settings"))
    }

    fun migrateLegacyFileOnce(legacyFile: File) {
        if (pref().getBoolean(prefKey(KEY_MIGRATED), false)) return
        if (!legacyFile.isFile) return
        val parsed = parseLegacySettingsFile(legacyFile)
        for ((key, value) in parsed) {
            put(key, value)
        }
        pref().putBoolean(prefKey(KEY_MIGRATED), true)
        Logging.info(
            "lanelet2: migrated {0} setting(s) from {1}",
            parsed.size,
            legacyFile.absolutePath,
        )
    }

    fun getRoutingAutoDebounceMs(default: Int = ROUTING_AUTO_DEBOUNCE_MS_DEFAULT): Int {
        val raw = pref().get(prefKey(KEY_ROUTING_AUTO_DEBOUNCE_MS), null) ?: return default
        val ms = raw.toIntOrNull() ?: return default
        return ms.coerceIn(ROUTING_AUTO_DEBOUNCE_MS_MIN, ROUTING_AUTO_DEBOUNCE_MS_MAX)
    }

    fun setRoutingAutoDebounceMs(ms: Int) {
        val clamped = ms.coerceIn(ROUTING_AUTO_DEBOUNCE_MS_MIN, ROUTING_AUTO_DEBOUNCE_MS_MAX)
        put(KEY_ROUTING_AUTO_DEBOUNCE_MS, clamped.toString())
    }

    fun getRoutingHookFullMap(default: Boolean = false): Boolean {
        val raw = pref().get(prefKey(KEY_ROUTING_HOOK_FULL_MAP), null) ?: return default
        return raw == "1"
    }

    fun setRoutingHookFullMap(enabled: Boolean) {
        putBoolean(KEY_ROUTING_HOOK_FULL_MAP, enabled)
    }

    fun getRoutingDefaultParticipant(default: String = "vehicle"): String {
        val raw = pref().get(prefKey(KEY_ROUTING_DEFAULT_PARTICIPANT), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return if (v in ROUTING_PARTICIPANTS) v else default
    }

    fun setRoutingDefaultParticipant(participant: String?) {
        var p = participant?.trim().orEmpty()
        if (p !in ROUTING_PARTICIPANTS) p = "vehicle"
        put(KEY_ROUTING_DEFAULT_PARTICIPANT, p)
    }

    fun getBackendsPython(): String = get(KEY_BACKENDS_PYTHON, "")

    fun setBackendsPython(path: String?) {
        put(KEY_BACKENDS_PYTHON, path?.trim().orEmpty())
    }

    fun getBackendsDir(): String = get(KEY_BACKENDS_DIR, "")

    fun setBackendsDir(path: String?) {
        put(KEY_BACKENDS_DIR, path?.trim().orEmpty())
    }

    /**
     * 60-minute commit/push reminder. Off by default — the Jython always-on
     * timer is an approved settings-gated change. Hooks/lifecycle remain a
     * separate task.
     */
    fun getGitCommitReminder(default: Boolean = false): Boolean =
        getBoolean(KEY_GIT_COMMIT_REMINDER, default)

    fun setGitCommitReminder(enabled: Boolean) {
        putBoolean(KEY_GIT_COMMIT_REMINDER, enabled)
    }

    /**
     * Jython `l2s.get("autotag.enabled", "0") == "1"` — only the literal `"1"`
     * is true, not `"true"` / `"yes"`.
     */
    fun isAutotagEnabled(default: Boolean = false): Boolean {
        val raw = pref().get(prefKey(KEY_AUTOTAG_ENABLED), null) ?: return default
        return raw == "1"
    }

    fun setAutotagEnabled(enabled: Boolean) {
        putBoolean(KEY_AUTOTAG_ENABLED, enabled)
        notifyLaneletDefaultUiChanged()
    }

    fun getAutotagTagsRaw(default: String = ""): String = get(KEY_AUTOTAG_TAGS, default)

    fun setAutotagTagsRaw(raw: String) {
        put(KEY_AUTOTAG_TAGS, raw)
    }

    /**
     * Opt into the legacy incremental collection dialog for Select Lanelets /
     * Select Relations and the regulatory-element wizards. Off by default —
     * those actions use the current JOSM selection. `"1"`-only, like autotag.
     */
    fun isCollectionDialogEnabled(default: Boolean = false): Boolean {
        val raw = pref().get(prefKey(KEY_COLLECTION_DIALOG), null) ?: return default
        return raw == "1"
    }

    fun setCollectionDialogEnabled(enabled: Boolean) {
        putBoolean(KEY_COLLECTION_DIALOG, enabled)
    }

    /**
     * Whether the extra Lanelet2 toolbar rows are shown. Default on. The
     * toggle lives on JOSM's own (top) toolbar, not on those extra rows.
     */
    fun isExtraToolbarVisible(default: Boolean = true): Boolean =
        getBoolean(KEY_EXTRA_TOOLBAR_VISIBLE, default)

    fun setExtraToolbarVisible(visible: Boolean) {
        putBoolean(KEY_EXTRA_TOOLBAR_VISIBLE, visible)
        notifyLaneletDefaultUiChanged()
    }

    /** Same `"1"`-only contract as [isAutotagEnabled]. */
    fun isZoomFilterEnabled(default: Boolean = false): Boolean {
        val raw = pref().get(prefKey(KEY_ZOOMFILTER_ENABLED), null) ?: return default
        return raw == "1"
    }

    fun setZoomFilterEnabled(enabled: Boolean) {
        putBoolean(KEY_ZOOMFILTER_ENABLED, enabled)
        notifyLaneletDefaultUiChanged()
    }

    fun getZoomFilterThreshold(default: Double = 17.0): Double {
        val raw = pref().get(prefKey(KEY_ZOOMFILTER_THRESHOLD), null) ?: return default
        return raw.toDoubleOrNull() ?: default
    }

    fun setZoomFilterThreshold(threshold: Double) {
        put(KEY_ZOOMFILTER_THRESHOLD, threshold.toString())
    }

    fun getZoomFilterFiltersRaw(default: String = ""): String = get(KEY_ZOOMFILTER_FILTERS, default)

    fun setZoomFilterFiltersRaw(raw: String) {
        put(KEY_ZOOMFILTER_FILTERS, raw)
    }

    /**
     * Persist a resolved interpreter the way `setup_backends.sh` writes
     * `~/.lanelet2_settings`: python path, backends dir, env_script=none.
     */
    fun persistBackendInterpreter(python: String, backendsDir: String) {
        put(KEY_BACKENDS_PYTHON, python)
        put(KEY_BACKENDS_DIR, backendsDir)
        put(KEY_BACKENDS_ENV_SCRIPT, "none")
    }

    fun subtypeUsesLocation(subtype: String?): Boolean = subtype in SUBTYPES_WITH_LOCATION

    fun getLaneletDefaultSubtype(
        default: String = "road",
        validOptions: Collection<String> = LANELET_SUBTYPES,
    ): String {
        val raw = pref().get(prefKey(KEY_LANELET_DEFAULT_SUBTYPE), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return if (v in validOptions) v else default
    }

    fun setLaneletDefaultSubtype(subtype: String?): Boolean {
        var s = subtype?.trim().orEmpty()
        if (s !in LANELET_SUBTYPES) s = "road"
        if (s == getLaneletDefaultSubtype()) return false
        put(KEY_LANELET_DEFAULT_SUBTYPE, s)
        notifyLaneletDefaultUiChanged()
        return true
    }

    fun getLaneletDefaultLocation(default: String = LOC_URBAN): String {
        val raw = pref().get(prefKey(KEY_LANELET_DEFAULT_LOCATION), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return if (v in LANELET_LOCATIONS) v else default
    }

    fun setLaneletDefaultLocation(location: String?) {
        var loc = location?.trim().orEmpty()
        if (loc !in LANELET_LOCATIONS) loc = LOC_URBAN
        put(KEY_LANELET_DEFAULT_LOCATION, loc)
    }

    fun getLaneletDefaultOneWay(default: String = ONE_WAY_YES): String {
        val raw = pref().get(prefKey(KEY_LANELET_DEFAULT_ONE_WAY), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return if (v in LANELET_ONE_WAY_VALUES) v else default
    }

    fun setLaneletDefaultOneWay(oneWay: String?): Boolean {
        var ow = oneWay?.trim()?.lowercase().orEmpty()
        if (ow !in LANELET_ONE_WAY_VALUES) ow = ONE_WAY_YES
        if (ow == getLaneletDefaultOneWay()) return false
        put(KEY_LANELET_DEFAULT_ONE_WAY, ow)
        notifyLaneletDefaultUiChanged()
        return true
    }

    fun getProtectMergeAnchors(default: Boolean = true): Boolean {
        val raw = pref().get(prefKey(KEY_PROTECT_MERGE_ANCHORS), null) ?: return default
        return raw != "0"
    }

    fun setProtectMergeAnchors(enabled: Boolean) {
        putBoolean(KEY_PROTECT_MERGE_ANCHORS, enabled)
    }

    fun getDeleteTaggedNodes(default: Boolean = true): Boolean {
        val raw = pref().get(prefKey(KEY_DELETE_TAGGED_NODES), null) ?: return default
        return raw != "0"
    }

    fun setDeleteTaggedNodes(enabled: Boolean) {
        putBoolean(KEY_DELETE_TAGGED_NODES, enabled)
    }

    fun getMergeGridCellM(default: Double = MERGE_GRID_CELL_M_DEFAULT): Double {
        val raw = pref().get(prefKey(KEY_MERGE_GRID_CELL_M), null) ?: return default
        val m = raw.toDoubleOrNull() ?: return default
        return m.coerceIn(MERGE_GRID_CELL_M_MIN, MERGE_GRID_CELL_M_MAX)
    }

    /**
     * Tag used to group hull points in Highlight File Boundaries.
     * Empty / missing → `file_origin` (Jython `v or DEFAULT_GROUP_TAG`).
     * Mismatch detection still always reads the literal `file_origin` tag.
     */
    fun getHighlightGroupTag(default: String = "file_origin"): String {
        val raw = pref().get(prefKey(KEY_HIGHLIGHT_GROUP_TAG), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return v.ifEmpty { default }
    }

    fun setHighlightGroupTag(tag: String?) {
        val v = tag?.trim().orEmpty().ifEmpty { "file_origin" }
        put(KEY_HIGHLIGHT_GROUP_TAG, v)
    }

    fun setMergeGridCellM(meters: Double) {
        val m = meters.coerceIn(MERGE_GRID_CELL_M_MIN, MERGE_GRID_CELL_M_MAX)
        val stored = if (m == m.toInt().toDouble()) m.toInt().toString() else m.toString()
        put(KEY_MERGE_GRID_CELL_M, stored)
    }

    fun getMapstyleAutoApplyOnLaunch(default: Boolean = true): Boolean {
        val raw = pref().get(prefKey(KEY_MAPSTYLE_AUTO_APPLY_ON_LAUNCH), null) ?: return default
        return raw != "0"
    }

    fun setMapstyleAutoApplyOnLaunch(enabled: Boolean) {
        putBoolean(KEY_MAPSTYLE_AUTO_APPLY_ON_LAUNCH, enabled)
    }

    fun getMapstyleLastPreset(default: String = MAPSTYLE_PRESET_LL2_EDITING): String {
        val raw = pref().get(prefKey(KEY_MAPSTYLE_LAST_PRESET), null)
        val v = if (raw.isNullOrEmpty()) default else raw
        return if (v in MAPSTYLE_PRESETS) v else default
    }

    fun setMapstyleLastPreset(presetId: String?) {
        var pid = presetId?.trim().orEmpty()
        if (pid !in MAPSTYLE_PRESETS) pid = MAPSTYLE_PRESET_CUSTOM
        put(KEY_MAPSTYLE_LAST_PRESET, pid)
    }

    fun getStyleCatalogEntry(styleId: String): StyleCatalogEntry? =
        STYLE_CATALOG.firstOrNull { it.id == styleId }

    fun getPresetsAutoInstallOnLaunch(default: Boolean = true): Boolean {
        val raw = pref().get(prefKey(KEY_PRESETS_AUTO_INSTALL_ON_LAUNCH), null) ?: return default
        return raw != "0"
    }

    fun setPresetsAutoInstallOnLaunch(enabled: Boolean) {
        putBoolean(KEY_PRESETS_AUTO_INSTALL_ON_LAUNCH, enabled)
    }

    fun getToolbarPresetNames(): List<String> {
        val names = getList(KEY_PRESETS_TOOLBAR_NAMES, emptyList())
        return names.ifEmpty { DEFAULT_TOOLBAR_PRESET_NAMES.toList() }
    }

    fun setToolbarPresetNames(names: List<String>?) {
        if (names == null) {
            put(KEY_PRESETS_TOOLBAR_NAMES, "")
            return
        }
        val cleaned = LinkedHashSet<String>()
        for (n in names) {
            val s = n.trim()
            if (s.isNotEmpty()) cleaned.add(s)
        }
        put(KEY_PRESETS_TOOLBAR_NAMES, cleaned.joinToString(","))
    }

    fun getLaneletCreateTags(): LaneletCreateTags {
        val subtype = getLaneletDefaultSubtype()
        val loc = if (subtypeUsesLocation(subtype)) getLaneletDefaultLocation() else null
        val oneWayTag = if (getLaneletDefaultOneWay() == ONE_WAY_NO) ONE_WAY_NO else null
        return LaneletCreateTags(subtype, loc, oneWayTag)
    }

    internal fun notifyLaneletDefaultUiChanged() {
        for (fn in laneletDefaultUiListeners) {
            try {
                fn.run()
            } catch (_: Exception) {
            }
        }
    }

    private fun pref() = Config.getPref()

    private fun parseBoolean(raw: String, default: Boolean): Boolean = when (raw.trim().lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> default
    }
}

internal fun parseLegacySettingsFile(file: File): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (!file.isFile) return out
    file.forEachLine { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEachLine
        val k = line.substring(0, eq).trim()
        val v = line.substring(eq + 1).trim()
        if (k.isNotEmpty()) out[k] = v
    }
    return out
}

fun resolveStyleUrl(entry: StyleCatalogEntry): String? = when (entry.kind) {
    StyleKind.FILE -> entry.file?.let { "resource://lanelet2/$it" }
    StyleKind.BUILTIN -> entry.url
    StyleKind.DYNAMIC -> null
}
