package org.openstreetmap.josm.plugins.lanelet2.notes

data class ExportNote(
    val id: String,
    val severity: String,
    val type: String,
    val done: Boolean,
    val text: String,
    val lat: Double?,
    val lon: Double?,
    val refs: String,
)

data class ImportBlock(
    val id: String? = null,
    val type: String = "issue",
    val severity: String = "minor",
    val done: String = "no",
    val text: String = "",
    val coord: Pair<Double, Double>? = null,
    val refs: String = "",
)

fun exportNotesText(notes: List<ExportNote>): String {
    val lines = ArrayList<String>()
    for (a in notes) {
        val done = if (a.done) "yes" else "no"
        lines.add("# note ${a.id} [${a.severity}] type=${a.type} done=$done")
        val txt = a.text.replace("\r", " ").replace("\n", " / ")
        lines.add("text: $txt")
        if (a.lat != null && a.lon != null) {
            lines.add("coord: ${formatCoord(a.lat)}, ${formatCoord(a.lon)}")
        }
        if (a.refs.isNotEmpty()) {
            lines.add("refs: ${a.refs}")
        }
        lines.add("")
    }
    return lines.joinToString("\n")
}

fun parseImport(text: String?): List<ImportBlock> {
    val blocks = ArrayList<ImportBlock>()
    var cur: MutableMap<String, Any?>? = null

    fun flush() {
        val c = cur ?: return
        @Suppress("UNCHECKED_CAST")
        blocks.add(
            ImportBlock(
                id = c["id"] as String?,
                type = (c["type"] as? String) ?: "issue",
                severity = (c["severity"] as? String) ?: "minor",
                done = (c["done"] as? String) ?: "no",
                text = (c["text"] as? String) ?: "",
                coord = c["coord"] as? Pair<Double, Double>,
                refs = (c["refs"] as? String) ?: "",
            ),
        )
    }

    for (raw in (text ?: "").split('\n', '\r')) {
        val line = raw.trim()
        if (line.startsWith("# note")) {
            flush()
            cur = mutableMapOf("type" to "issue", "severity" to "minor", "done" to "no")
            val rest = line.removePrefix("# note").trim()
            val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && !parts[0].startsWith("[")) {
                cur["id"] = parts[0]
            }
            val lb = rest.indexOf('[')
            val rb = rest.indexOf(']')
            if (lb in 0 until rb) {
                val sev = rest.substring(lb + 1, rb).trim().lowercase()
                if (sev in NotesTags.SEVERITIES) cur["severity"] = sev
            }
            for (tok in parts) {
                if (tok.startsWith("type=")) {
                    cur["type"] = tok.substring(5)
                } else if (tok.startsWith("done=")) {
                    val v = tok.substring(5).lowercase()
                    cur["done"] = if (v in setOf("yes", "true", "1")) "yes" else "no"
                }
            }
            continue
        }
        val c = cur ?: continue
        val low = line.lowercase()
        when {
            low.startsWith("text:") -> c["text"] = line.substring(5).trim()
            low.startsWith("coord:") -> {
                val valStr = line.substring(6).trim().replace(';', ',')
                val bits = valStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (bits.size >= 2) {
                    val lat = bits[0].toDoubleOrNull()
                    val lon = bits[1].toDoubleOrNull()
                    if (lat != null && lon != null) c["coord"] = lat to lon
                }
            }
            low.startsWith("refs:") -> c["refs"] = line.substring(5).trim()
        }
    }
    flush()
    return blocks
}
