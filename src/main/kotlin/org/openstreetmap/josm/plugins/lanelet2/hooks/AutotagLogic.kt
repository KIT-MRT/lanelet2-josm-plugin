package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Way
import java.io.File

/**
 * Pure autotag / delete-set / merge-anchor decisions from `autotag_hook.py`
 * and `autotag_new_elements.py`. No listeners, no EDT, no undo stack.
 */
object AutotagLogic {
    const val FILE_ORIGIN_TAG = "file_origin"
    const val MERGE_ANCHOR_TAG = "merge_anchor"
    const val DEBOUNCE_MS = 400
    const val SEQUENCE_TITLE = "Autotag new elements"

    const val HUD_X = 6
    const val HUD_Y = 8

    fun serializeTags(tags: List<Pair<String, String>>): String =
        tags.joinToString("|") { (k, v) -> "$k=$v" }

    /**
     * Jython `_deserialize_tags`: split on `|`, first `=`, strip the key only.
     * Values keep leading/trailing spaces. Chunks without `=` are skipped.
     */
    fun deserializeTags(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrEmpty()) return emptyList()
        val pairs = ArrayList<Pair<String, String>>()
        for (chunk in raw.split("|")) {
            val eq = chunk.indexOf('=')
            if (eq < 0) continue
            val k = chunk.substring(0, eq).trim()
            if (k.isNotEmpty()) {
                pairs.add(k to chunk.substring(eq + 1))
            }
        }
        return pairs
    }

    /**
     * Dialog textarea parser: skip blanks and `#` comments; strip key **and**
     * value. Distinct from [deserializeTags] (settings file).
     */
    fun textToTags(text: String): List<Pair<String, String>> {
        val pairs = ArrayList<Pair<String, String>>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val k = line.substring(0, eq).trim()
            if (k.isNotEmpty()) {
                pairs.add(k to line.substring(eq + 1).trim())
            }
        }
        return pairs
    }

    fun tagsToText(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (k, v) -> "$k=$v" }

    fun fileOriginOf(tags: List<Pair<String, String>>): String? =
        tags.firstOrNull { it.first == FILE_ORIGIN_TAG }?.second

    fun replaceFileOrigin(tags: List<Pair<String, String>>, path: String?): List<Pair<String, String>> {
        val pairs = tags.filter { it.first != FILE_ORIGIN_TAG }.toMutableList()
        if (!path.isNullOrEmpty()) {
            pairs.add(FILE_ORIGIN_TAG to path)
        }
        return pairs
    }

    /** `os.path.basename`; empty basename falls back to the raw path. */
    fun fileOriginBasename(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val name = File(path).name
        return name.ifEmpty { path }
    }

    fun shouldCollectNew(prim: OsmPrimitive?, pending: List<OsmPrimitive>): Boolean {
        if (prim == null) return false
        return try {
            prim.isNew && prim !in pending
        } catch (_: Exception) {
            false
        }
    }

    fun collectNewPrimitives(
        primitives: Iterable<OsmPrimitive?>,
        pending: MutableList<OsmPrimitive>,
    ) {
        for (prim in primitives) {
            if (shouldCollectNew(prim, pending)) {
                pending.add(prim!!)
            }
        }
    }

    fun targetsForTag(
        pending: List<OsmPrimitive>,
        ds: Any?,
        key: String,
        value: String,
    ): List<OsmPrimitive> {
        val targets = ArrayList<OsmPrimitive>()
        for (prim in pending) {
            try {
                if (prim.dataSet === ds && prim.isNew && prim.get(key) != value) {
                    targets.add(prim)
                }
            } catch (_: Exception) {
            }
        }
        return targets
    }

    fun uniqueFileOrigins(values: Iterable<String?>): List<String> {
        val origins = ArrayList<String>()
        val seen = HashSet<String>()
        for (val_ in values) {
            if (!val_.isNullOrEmpty() && val_ !in seen) {
                seen.add(val_)
                origins.add(val_)
            }
        }
        return origins
    }

    data class DialogSize(val width: Int, val height: Int)

    /**
     * Jython `_picker_dialog_size`. `int(max_len * char_w * 0.62)` truncates
     * toward zero (operands are non-negative).
     */
    fun pickerDialogSize(maxLen: Int, originCount: Int, charW: Int): DialogSize {
        val width = minOf(maxOf(520, (maxLen * charW * 0.62).toInt() + 140), 1200)
        val rowH = 22
        val tableH = minOf(maxOf(160, originCount * rowH + 28), 420)
        val height = minOf(maxOf(320, tableH + 130), 640)
        return DialogSize(width, height)
    }

    data class PrimKey(val type: OsmPrimitiveType, val uniqueId: Long)

    fun primKey(prim: OsmPrimitive): PrimKey = PrimKey(prim.type, prim.uniqueId)

    /**
     * Jython `_collect_delete_set`. `null` when the selection contains no way
     * (stock JOSM delete handles that case).
     */
    fun collectDeleteSet(
        selected: List<OsmPrimitive>,
        protectAnchors: Boolean,
    ): List<OsmPrimitive>? {
        val ways = selected.filterIsInstance<Way>()
        if (ways.isEmpty()) return null
        val toDelete = selected.toMutableList()
        val keys = toDelete.map { primKey(it) }.toMutableSet()
        for (way in ways) {
            val nodes = try {
                way.nodes
            } catch (_: Exception) {
                continue
            } ?: continue
            for (node in nodes) {
                val key = primKey(node)
                if (key in keys) continue
                if (protectAnchors && node.hasKey(MERGE_ANCHOR_TAG)) continue
                val referrers = try {
                    node.referrers
                } catch (_: Exception) {
                    continue
                } ?: continue
                if (referrers.all { primKey(it) in keys }) {
                    toDelete.add(node)
                    keys.add(key)
                }
            }
        }
        return toDelete
    }

    data class AnchorViolation(val kind: String, val id: Long)

    /**
     * Snapshot-driven merge-anchor check. First violation wins; snapshot
     * iteration order is insertion order of our [LinkedHashMap] cache (Jython
     * 2.7 dict order was hash-based — same result when only one anchor
     * changed).
     */
    fun checkAnchorCommand(
        positions: Map<Long, Pair<Double, Double>>,
        lookup: (Long) -> Node?,
    ): AnchorViolation? {
        for ((nid, old) in positions) {
            val node = try {
                lookup(nid)
            } catch (_: Exception) {
                null
            }
            if (node == null || node.isDeleted) return AnchorViolation("deleted", nid)
            if (!node.hasKey(MERGE_ANCHOR_TAG)) return AnchorViolation("untagged", nid)
            try {
                if (old.first != node.lat() || old.second != node.lon()) {
                    return AnchorViolation("moved", nid)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun isAnchorNode(prim: OsmPrimitive?): Boolean {
        return try {
            prim is Node && prim.hasKey(MERGE_ANCHOR_TAG)
        } catch (_: Exception) {
            false
        }
    }

    fun warnDetail(kind: String): String = when (kind) {
        "deleted" -> "deleted"
        "moved" -> "moved"
        "untagged" -> "had its 'merge_anchor' tag removed"
        else -> "changed"
    }
}
