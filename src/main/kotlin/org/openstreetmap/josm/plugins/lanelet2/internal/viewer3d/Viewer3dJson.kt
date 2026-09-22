package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import jakarta.json.Json
import jakarta.json.JsonNumber
import jakarta.json.JsonObject
import jakarta.json.JsonString
import jakarta.json.JsonValue
import org.openstreetmap.josm.tools.Logging
import java.io.StringReader
import java.util.Locale

/** Compact JSON encoding for bridge messages (`separators=(",", ":")`). */
object Viewer3dJson {
    fun encode(message: OutboundMessage): String = when (message) {
        is OutboundMessage.Snapshot -> encodeSnapshot(message)
        is OutboundMessage.Patch -> encodePatch(message)
        is OutboundMessage.Selection -> encodeSelection(message)
        is OutboundMessage.CommandResult -> encodeResult(message)
    }

    private fun encodeSelection(msg: OutboundMessage.Selection): String {
        val sb = StringBuilder(64 + msg.nodeIds.size * 12 + msg.wayIds.size * 16)
        sb.append("{\"type\":\"selection\",\"nodes\":[")
        msg.nodeIds.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            sb.append(id)
        }
        sb.append("],\"ways\":[")
        msg.wayIds.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(id))
        }
        sb.append(']')
        if (msg.truncated) sb.append(",\"truncated\":true")
        sb.append('}')
        return sb.toString()
    }

    private fun encodeResult(msg: OutboundMessage.CommandResult): String {
        val warning = msg.warning?.let { ",\"warning\":${jsonString(it)}" } ?: ""
        return "{\"type\":\"command_result\",\"id\":${jsonString(msg.id)},\"ok\":${msg.ok}," +
            "\"message\":${jsonString(msg.message)}$warning}"
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

    /**
     * Parse an inbound `{"type":"command","id":...,"ops":[...]}` with JOSM's
     * bundled jakarta.json. Unknown or malformed ops are skipped; anything that
     * is not a command object yields null.
     */
    fun parseCommand(json: String): InboundCommand? {
        val obj = try {
            Json.createReader(StringReader(json)).use { it.readObject() }
        } catch (e: Exception) {
            Logging.debug(e)
            return null
        }
        if (string(obj, "type") != "command") return null
        val opsArr = obj["ops"] as? jakarta.json.JsonArray ?: return null
        val ops = opsArr.mapNotNull { (it as? JsonObject)?.let(::parseOp) }
        return InboundCommand(ops, string(obj, "id"))
    }

    private fun parseOp(o: JsonObject): InboundOp? = when (string(o, "op")) {
        "move_node" -> {
            val id = string(o, "id")
            val x = number(o, "x")
            val y = number(o, "y")
            val z = number(o, "z")
            // x and y move together; a move must change something.
            if (id == null || (x == null) != (y == null) || (x == null && z == null)) null
            else InboundOp.MoveNode(id, x, y, z)
        }
        "set_tag" -> {
            val id = string(o, "id")
            val key = string(o, "key")
            if (id == null || key == null) null else InboundOp.SetTag(id, key, string(o, "value") ?: "")
        }
        "set_view" -> {
            val x = number(o, "x")
            val y = number(o, "y")
            if (x == null || y == null) null else InboundOp.SetView(x, y, truthy(o["force"]))
        }
        "select" -> InboundOp.Select(strings(o, "ids"))
        "delete_selection" -> InboundOp.DeleteSelection(strings(o, "ids"))
        "interpolate_height" -> string(o, "way")?.let { InboundOp.InterpolateHeight(it, strings(o, "anchors")) }
        "undo" -> InboundOp.Undo
        "redo" -> InboundOp.Redo
        else -> null
    }

    private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonString)?.string

    private fun number(o: JsonObject, key: String): Double? = (o[key] as? JsonNumber)?.doubleValue()

    private fun strings(o: JsonObject, key: String): List<String> =
        (o[key] as? jakarta.json.JsonArray)?.mapNotNull { (it as? JsonString)?.string }.orEmpty()

    /** `true`, a non-zero number, or "true"/"1" in any case (the Jython accepted `True`). */
    private fun truthy(v: JsonValue?): Boolean = when {
        v == null -> false
        v == JsonValue.TRUE -> true
        v is JsonNumber -> v.doubleValue() != 0.0
        v is JsonString -> v.string.equals("true", ignoreCase = true) || v.string == "1"
        else -> false
    }
}
