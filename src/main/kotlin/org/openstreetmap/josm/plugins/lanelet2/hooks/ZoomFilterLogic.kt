package org.openstreetmap.josm.plugins.lanelet2.hooks

import kotlin.math.cos
import kotlin.math.ln

/**
 * Pure zoom-filter decisions from `zoom_filter_hook.py` / `zoom_filter_window.py`.
 *
 * Quirk: comments and the window docstring say default zoom 19; the code
 * default is **17.0**. Filters turn **on** when `zoom <= threshold` (zoomed
 * out), not when zoomed in.
 */
object ZoomFilterLogic {
    const val DEFAULT_THRESHOLD = 17.0
    const val R0 = 156543.03392

    val DEFAULT_FILTERS: List<ZoomFilterSpec> = listOf(
        ZoomFilterSpec(enabled = true, hiding = true, inverted = false, text = "type:node"),
    )

    data class ZoomFilterSpec(
        val enabled: Boolean = true,
        val hiding: Boolean = true,
        val inverted: Boolean = false,
        val text: String = "",
    )

    /**
     * Slippy zoom from ground resolution (m/px) and centre latitude.
     * Jython: `res = 156543.03 * cos(lat) / 2**z` inverted via `log(ratio, 2)`.
     */
    fun zoomFromGroundRes(groundResMPerPx: Double, latDeg: Double): Double? {
        if (groundResMPerPx <= 0.0) return null
        val ratio = R0 * cos(Math.toRadians(latDeg)) / groundResMPerPx
        if (ratio <= 0.0) return null
        return ln(ratio) / ln(2.0)
    }

    fun zoomFromDist100(dist100: Double, latDeg: Double): Double? {
        if (dist100 <= 0.0) return null
        return zoomFromGroundRes(dist100 / 100.0, latDeg)
    }

    fun shouldFiltersBeActive(zoom: Double, threshold: Double): Boolean = zoom <= threshold

    fun parseThreshold(raw: String?, default: Double = DEFAULT_THRESHOLD): Double {
        if (raw.isNullOrBlank()) return default
        return raw.toDoubleOrNull() ?: default
    }

    /** Filters that are pushed into the JOSM panel (`text` non-blank and enabled). */
    fun managedSpecs(config: List<ZoomFilterSpec>): List<ZoomFilterSpec> =
        config.filter { it.enabled && it.text.trim().isNotEmpty() }
            .map { it.copy(text = it.text.trim()) }

    fun parseFiltersJson(raw: String?): List<ZoomFilterSpec> {
        if (raw.isNullOrBlank()) return DEFAULT_FILTERS.map { it.copy() }
        return parseFiltersJsonOrNull(raw) ?: DEFAULT_FILTERS.map { it.copy() }
    }

    fun parseFiltersJsonOrNull(raw: String): List<ZoomFilterSpec>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        val objects = splitTopLevelObjects(inner) ?: return null
        val out = ArrayList<ZoomFilterSpec>(objects.size)
        for (obj in objects) {
            out.add(parseFilterObject(obj) ?: return null)
        }
        return out
    }

    /**
     * Stable key order. Jython 2.7 `json.dumps` emitted hash order; we parse
     * by key so existing settings files still load.
     */
    fun encodeFiltersJson(filters: List<ZoomFilterSpec>): String =
        filters.joinToString(prefix = "[", postfix = "]", separator = ", ") { f ->
            "{" +
                "\"enabled\": ${f.enabled}, " +
                "\"hiding\": ${f.hiding}, " +
                "\"inverted\": ${f.inverted}, " +
                "\"text\": ${jsonString(f.text)}" +
                "}"
        }

    fun truthy(value: Any?): Boolean {
        if (value == null) return false
        if (value is Boolean) return value
        return when (value.toString().trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no", "" -> false
            else -> true
        }
    }

    private fun parseFilterObject(obj: String): ZoomFilterSpec? {
        val body = obj.trim().removePrefix("{").removeSuffix("}").trim()
        val fields = splitTopLevelFields(body) ?: return null
        val map = HashMap<String, String>()
        for (field in fields) {
            val colon = indexOfTopLevel(field, ':') ?: continue
            val key = unquoteJsonString(field.substring(0, colon).trim()) ?: continue
            map[key] = field.substring(colon + 1).trim()
        }
        val textRaw = map["text"]
        val text = when {
            textRaw == null -> ""
            textRaw == "null" -> ""
            else -> unquoteJsonString(textRaw) ?: textRaw
        }
        return ZoomFilterSpec(
            enabled = parseJsonBool(map["enabled"], default = true),
            hiding = parseJsonBool(map["hiding"], default = true),
            inverted = parseJsonBool(map["inverted"], default = false),
            text = text,
        )
    }

    private fun parseJsonBool(raw: String?, default: Boolean): Boolean {
        if (raw == null) return default
        return when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    private fun splitTopLevelObjects(inner: String): List<String>? {
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        var escape = false
        val buf = StringBuilder()
        for (ch in inner) {
            if (inStr) {
                buf.append(ch)
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    inStr = true
                    buf.append(ch)
                }
                '{' -> {
                    depth++
                    buf.append(ch)
                }
                '}' -> {
                    depth--
                    buf.append(ch)
                    if (depth < 0) return null
                }
                ',' -> {
                    if (depth == 0) {
                        val piece = buf.toString().trim()
                        if (piece.isNotEmpty()) out.add(piece)
                        buf.setLength(0)
                    } else {
                        buf.append(ch)
                    }
                }
                else -> buf.append(ch)
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        if (depth != 0) return null
        return out
    }

    private fun splitTopLevelFields(body: String): List<String>? {
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        var escape = false
        val buf = StringBuilder()
        for (ch in body) {
            if (inStr) {
                buf.append(ch)
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    inStr = true
                    buf.append(ch)
                }
                '{', '[' -> {
                    depth++
                    buf.append(ch)
                }
                '}', ']' -> {
                    depth--
                    buf.append(ch)
                }
                ',' -> {
                    if (depth == 0) {
                        out.add(buf.toString())
                        buf.setLength(0)
                    } else {
                        buf.append(ch)
                    }
                }
                else -> buf.append(ch)
            }
        }
        out.add(buf.toString())
        return out
    }

    private fun indexOfTopLevel(s: String, target: Char): Int? {
        var inStr = false
        var escape = false
        for (i in s.indices) {
            val ch = s[i]
            if (inStr) {
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inStr = false
                }
                continue
            }
            if (ch == '"') {
                inStr = true
            } else if (ch == target) {
                return i
            }
        }
        return null
    }

    private fun unquoteJsonString(raw: String): String? {
        val s = raw.trim()
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return null
        val inner = s.substring(1, s.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            if (i + 1 >= inner.length) return null
            when (val n = inner[i + 1]) {
                '"', '\\', '/' -> {
                    out.append(n)
                    i += 2
                }
                'b' -> {
                    out.append('\b')
                    i += 2
                }
                'f' -> {
                    out.append('\u000c')
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 5 >= inner.length) return null
                    val hex = inner.substring(i + 2, i + 6)
                    val cp = hex.toIntOrNull(16) ?: return null
                    out.append(cp.toChar())
                    i += 6
                }
                else -> return null
            }
        }
        return out.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
