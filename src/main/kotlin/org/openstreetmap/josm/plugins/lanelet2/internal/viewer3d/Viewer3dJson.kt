package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import java.util.Locale

/** Compact JSON encoding for bridge messages (`separators=(",", ":")`). */
object Viewer3dJson {
    fun encode(message: OutboundMessage): String = when (message) {
        is OutboundMessage.Snapshot -> encodeSnapshot(message)
        is OutboundMessage.Patch -> encodePatch(message)
    }

    private fun encodeSnapshot(msg: OutboundMessage.Snapshot): String {
        val sb = StringBuilder(256 + msg.features.sumOf { 64 + it.pts.size * 9 })
        sb.append("{\"type\":\"snapshot\"")
        if (msg.anchor == null) {
            sb.append(",\"anchor\":null")
        } else {
            // Full precision: the viewer converts its ENU metres back to lat/lon
            // with this, and 3 decimals of a degree are up to ~50 m off.
            sb.append(",\"anchor\":{\"lat\":")
            appendDegrees(sb, msg.anchor.lat)
            sb.append(",\"lon\":")
            appendDegrees(sb, msg.anchor.lon)
            sb.append('}')
        }
        sb.append(",\"features\":")
        encodeFeatures(sb, msg.features)
        sb.append('}')
        return sb.toString()
    }

    private fun encodePatch(msg: OutboundMessage.Patch): String {
        val sb = StringBuilder(128)
        sb.append("{\"type\":\"patch\",\"ops\":[")
        msg.ops.forEachIndexed { i, op ->
            if (i > 0) sb.append(',')
            when (op) {
                is PatchOp.Remove -> {
                    sb.append("{\"op\":\"remove\",\"id\":")
                    sb.append(jsonString(op.id))
                    sb.append('}')
                }
                is PatchOp.Upsert -> {
                    sb.append("{\"op\":\"upsert\",\"feature\":")
                    encodeFeature(sb, op.feature)
                    sb.append('}')
                }
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun encodeFeatures(sb: StringBuilder, features: List<ViewerFeature>) {
        sb.append('[')
        features.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            encodeFeature(sb, f)
        }
        sb.append(']')
    }

    private fun encodeFeature(sb: StringBuilder, feat: ViewerFeature) {
        sb.append("{\"id\":")
        sb.append(jsonString(feat.id))
        sb.append(",\"kind\":")
        sb.append(jsonString(feat.kind))
        sb.append(",\"tags\":")
        encodeTags(sb, feat.tags)
        sb.append(",\"pts\":[")
        for (i in feat.pts.indices) {
            if (i > 0) sb.append(',')
            appendNum(sb, feat.pts[i])
        }
        sb.append(']')
        if (feat.nodeIds.isNotEmpty()) {
            sb.append(",\"nodes\":[")
            for (i in feat.nodeIds.indices) {
                if (i > 0) sb.append(',')
                sb.append(feat.nodeIds[i])
            }
            sb.append(']')
        }
        if (feat.center != null) {
            sb.append(",\"center\":[")
            feat.center.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                appendNum(sb, v)
            }
            sb.append(']')
        }
        sb.append('}')
    }

    private fun encodeTags(sb: StringBuilder, tags: Map<String, String>) {
        sb.append('{')
        tags.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(k))
            sb.append(':')
            sb.append(jsonString(v))
        }
        sb.append('}')
    }

    /**
     * Coordinate in metres with at most 3 decimals, trailing zeros dropped
     * (`2`, `0.1`, `-12.345`). Same text as `%.3f` trimmed for the millimetre-
     * rounded values the bridge sends, without `String.format`, which cost
     * over a second per city-size snapshot.
     */
    internal fun appendNum(sb: StringBuilder, v: Double) {
        if (v.isNaN() || v.isInfinite() || kotlin.math.abs(v) >= 1e12) {
            sb.append(formatNumSlow(v))
            return
        }
        var milli = Math.round(v * 1000.0)
        if (milli == 0L) {
            sb.append('0')
            return
        }
        if (milli < 0) {
            sb.append('-')
            milli = -milli
        }
        sb.append(milli / 1000)
        var frac = (milli % 1000).toInt()
        if (frac == 0) return
        sb.append('.')
        var width = 3
        while (frac % 10 == 0) {
            frac /= 10
            width--
        }
        val digits = frac.toString()
        repeat(width - digits.length) { sb.append('0') }
        sb.append(digits)
    }

    internal fun formatNum(v: Double): String = StringBuilder(12).also { appendNum(it, v) }.toString()

    internal fun formatNumSlow(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            .ifEmpty { "0" }
    }

    /** Degrees with up to 9 decimals (~0.1 mm), trailing zeros dropped. */
    private fun appendDegrees(sb: StringBuilder, v: Double) {
        if (v == v.toLong().toDouble()) {
            sb.append(v.toLong())
            return
        }
        sb.append(String.format(Locale.US, "%.9f", v).trimEnd('0').trimEnd('.'))
    }

    fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Minimal parser for inbound `{"type":"command","ops":[...]}`. Unknown ops skipped. */
    fun parseCommand(json: String): InboundCommand? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"type\"")) return null
        val type = extractStringField(trimmed, "type") ?: return null
        if (type != "command") return null
        val opsRaw = extractArrayField(trimmed, "ops") ?: return null
        val ops = parseOpsArray(opsRaw)
        return InboundCommand(ops)
    }

    private fun parseOpsArray(raw: String): List<InboundOp> {
        val out = ArrayList<InboundOp>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && raw[i] != '{') i++
            if (i >= raw.length) break
            val end = findMatchingBrace(raw, i)
            if (end < 0) break
            parseOp(raw.substring(i, end + 1))?.let { out.add(it) }
            i = end + 1
        }
        return out
    }

    private fun parseOp(obj: String): InboundOp? {
        val op = extractStringField(obj, "op") ?: return null
        return when (op) {
            "move_node" -> {
                val id = extractStringField(obj, "id") ?: return null
                val x = extractNumberField(obj, "x") ?: return null
                val y = extractNumberField(obj, "y") ?: return null
                val z = extractNumberField(obj, "z")
                InboundOp.MoveNode(id, x, y, z)
            }
            "set_tag" -> {
                val id = extractStringField(obj, "id") ?: return null
                val key = extractStringField(obj, "key") ?: return null
                val value = extractStringField(obj, "value") ?: ""
                InboundOp.SetTag(id, key, value)
            }
            "set_view" -> {
                val x = extractNumberField(obj, "x") ?: return null
                val y = extractNumberField(obj, "y") ?: return null
                val forceRaw = extractRawField(obj, "force")
                val force = forceRaw == "true" || forceRaw == "1" || forceRaw == "True"
                InboundOp.SetView(x, y, force)
            }
            else -> null
        }
    }

    private fun extractStringField(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val m = pattern.find(json) ?: return null
        return unescapeJson(m.groupValues[1])
    }

    private fun extractNumberField(json: String, key: String): Double? {
        val pattern = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractRawField(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*([^,}\]]+)""")
        return pattern.find(json)?.groupValues?.get(1)?.trim()
    }

    private fun extractArrayField(json: String, key: String): String? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val start = json.indexOf('[', idx)
        if (start < 0) return null
        val end = findMatchingBracket(json, start)
        if (end < 0) return null
        return json.substring(start + 1, end)
    }

    private fun findMatchingBracket(s: String, start: Int): Int {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}
