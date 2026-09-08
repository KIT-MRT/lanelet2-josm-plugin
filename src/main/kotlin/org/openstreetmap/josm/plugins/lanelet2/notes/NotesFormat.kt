package org.openstreetmap.josm.plugins.lanelet2.notes

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Byte-compatible OSM XML writer for `.notes` files (`notes_core.serialize`).
 *
 * Format quirks pinned by `testdata/notes/jython_serialize_fixture.notes`:
 * single-quoted attributes, `%.9f` coords, tags in sorted key order, Unix
 * newlines, a trailing newline, `generator='lanelet2_notes'`. Negative unique
 * ids are remapped to `max(positive ids, id_map values, 0) + 1` and the map
 * is mutated so subsequent saves keep the same ids.
 */
data class NoteNodeRec(
    val uniqueId: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String> = emptyMap(),
    val deleted: Boolean = false,
    val incomplete: Boolean = false,
)

data class NoteWayRec(
    val uniqueId: Long,
    val nodeUniqueIds: List<Long>,
    val tags: Map<String, String> = emptyMap(),
    val deleted: Boolean = false,
    val incomplete: Boolean = false,
)

fun escapeXmlAttr(raw: Any?): String {
    // Jython `_esc(n.get(k))` does `str(s)` first, so a missing value becomes "None".
    val s = raw?.toString() ?: "None"
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;")
        .replace("\r", "&#13;")
        .replace("\n", "&#10;")
        .replace("\t", "&#9;")
}

fun formatCoord(v: Double): String = String.format(Locale.US, "%.9f", v)

fun serializeNotes(
    nodes: Collection<NoteNodeRec>,
    ways: Collection<NoteWayRec>,
    idMap: MutableMap<Long, Long>,
): String {
    val liveNodes = nodes.filter { !it.deleted && !it.incomplete }
    val liveWays = ways.filter { !it.deleted && !it.incomplete }

    var maxId = 0L
    for (p in liveNodes) if (p.uniqueId > maxId) maxId = p.uniqueId
    for (p in liveWays) if (p.uniqueId > maxId) maxId = p.uniqueId
    for (v in idMap.values) if (v > maxId) maxId = v
    var counter = maxId

    fun pid(uniqueId: Long): Long {
        val existing = idMap[uniqueId]
        if (existing != null) return existing
        if (uniqueId > 0) {
            idMap[uniqueId] = uniqueId
            return uniqueId
        }
        counter += 1
        idMap[uniqueId] = counter
        return counter
    }

    val nodePid = LinkedHashMap<Long, Long>()
    for (n in liveNodes) {
        nodePid[n.uniqueId] = pid(n.uniqueId)
    }

    val lines = ArrayList<String>()
    lines.add("<?xml version='1.0' encoding='UTF-8'?>")
    lines.add("<osm version='0.6' generator='lanelet2_notes'>")

    for (n in liveNodes.sortedBy { nodePid[it.uniqueId] }) {
        val head = "  <node id='${nodePid[n.uniqueId]}' visible='true' version='1' " +
            "lat='${formatCoord(n.lat)}' lon='${formatCoord(n.lon)}'"
        val keys = n.tags.keys.map { it }.sorted()
        if (keys.isEmpty()) {
            lines.add("$head />")
            continue
        }
        lines.add("$head>")
        for (k in keys) {
            lines.add("    <tag k='${escapeXmlAttr(k)}' v='${escapeXmlAttr(n.tags[k])}' />")
        }
        lines.add("  </node>")
    }

    for (w in liveWays.sortedBy { pid(it.uniqueId) }) {
        val wid = pid(w.uniqueId)
        lines.add("  <way id='$wid' visible='true' version='1'>")
        for (nid in w.nodeUniqueIds) {
            lines.add("    <nd ref='${nodePid[nid]}' />")
        }
        for (k in w.tags.keys.sorted()) {
            lines.add("    <tag k='${escapeXmlAttr(k)}' v='${escapeXmlAttr(w.tags[k])}' />")
        }
        lines.add("  </way>")
    }

    lines.add("</osm>")
    return lines.joinToString("\n") + "\n"
}

fun atomicWriteUtf8(path: File, text: String) {
    val parent = path.parentFile
    if (parent != null && !parent.isDirectory) {
        try {
            parent.mkdirs()
        } catch (_: Exception) {
        }
    }
    val tmp = File(path.path + ".tmp")
    tmp.writeText(text, StandardCharsets.UTF_8)
    try {
        if (path.exists()) path.delete()
    } catch (_: Exception) {
    }
    if (!tmp.renameTo(path)) {
        tmp.copyTo(path, overwrite = true)
        tmp.delete()
    }
}
