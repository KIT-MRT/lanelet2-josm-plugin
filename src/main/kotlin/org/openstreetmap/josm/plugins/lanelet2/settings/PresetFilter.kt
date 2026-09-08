package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.gui.tagging.presets.TaggingPreset

/** Pure helpers from `core/settings_ui/presets_dialog.py` for tests and the dialog. */
internal fun presetLabel(preset: TaggingPreset): String {
    try {
        return preset.localeName
    } catch (_: Exception) {
    }
    try {
        return preset.rawName
    } catch (_: Exception) {
    }
    return preset.name ?: preset.toString()
}

internal fun buildPresetFilterBlob(label: String, name: String?, rawName: String?): String {
    val parts = mutableListOf(label.lowercase())
    if (!name.isNullOrBlank()) parts.add(name.lowercase())
    if (!rawName.isNullOrBlank()) parts.add(rawName.lowercase())
    return parts.joinToString(" ")
}

internal fun blobMatchesFilter(blob: String, query: String): Boolean {
    if (query.isEmpty()) return true
    return query in blob
}

internal fun presetMatchesFilter(preset: TaggingPreset, query: String): Boolean {
    val raw = try {
        preset.rawName
    } catch (_: Exception) {
        null
    }
    val blob = buildPresetFilterBlob(presetLabel(preset), preset.name, raw)
    return blobMatchesFilter(blob, query.trim().lowercase())
}
