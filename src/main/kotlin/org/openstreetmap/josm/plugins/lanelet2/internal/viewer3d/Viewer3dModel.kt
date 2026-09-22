package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/** Headless snapshots of OSM geometry for the 3D bridge. No JOSM types here. */
data class NodeSnapshot(
    val uniqueId: Long,
    val lat: Double?,
    val lon: Double?,
    val eleTag: String?,
)

/**
 * A lanelet relation: bound ways with their nodes already in driving order
 * (lanelet2 alignment applied), plus the tags the viewer shows.
 */
data class LaneletSnapshot(
    val uniqueId: Long,
    val deleted: Boolean,
    val leftWayId: Long?,
    val rightWayId: Long?,
    val leftReversed: Boolean,
    val rightReversed: Boolean,
    val left: List<NodeSnapshot>,
    val right: List<NodeSnapshot>,
    val subtype: String?,
    val oneWay: String?,
)

data class WaySnapshot(
    val uniqueId: Long,
    val deleted: Boolean,
    val nodes: List<NodeSnapshot>,
    val type: String?,
    val subtype: String?,
    val participantBicycle: String?,
)

/**
 * One lanelet relation for the viewer: its bound ways by feature id, whether
 * each is reversed to run in driving direction (lanelet2 `geometry::align`),
 * and the direction arrow at 35 % of the centerline:
 * [arrow] = x, y, z, dx, dy, dz (unit), width (m), or null when degenerate.
 */
class LaneletRefs(
    val left: String,
    val right: String,
    val leftReversed: Boolean,
    val rightReversed: Boolean,
    val arrow: DoubleArray?,
    val twoWay: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is LaneletRefs && left == other.left && right == other.right &&
            leftReversed == other.leftReversed && rightReversed == other.rightReversed &&
            twoWay == other.twoWay && (arrow?.contentEquals(other.arrow) ?: (other.arrow == null))

    override fun hashCode(): Int = 31 * left.hashCode() + right.hashCode()
}

/**
 * One renderable object for the viewer. Wire format (protocol v2):
 * `{"id","kind","tags","pts":[x,y,z,...],"nodes":[uniqueId,...],"center"}`,
 * plus for `kind: "lanelet"` the fields of [LaneletRefs].
 */
class ViewerFeature(
    val id: String,
    val kind: String,
    val tags: Map<String, String>,
    /** x, y, z per vertex: local ENU metres, rounded to millimetres. */
    val pts: DoubleArray,
    /** JOSM unique id of the node behind each vertex; empty for overlays. */
    val nodeIds: LongArray = LongArray(0),
    val center: List<Double>? = null,
    val lanelet: LaneletRefs? = null,
) {
    val vertexCount: Int get() = pts.size / 3

    fun copy(tags: Map<String, String> = this.tags, center: List<Double>? = this.center) =
        ViewerFeature(id, kind, tags, pts, nodeIds, center, lanelet)

    override fun equals(other: Any?): Boolean =
        other is ViewerFeature && id == other.id && kind == other.kind && tags == other.tags &&
            pts.contentEquals(other.pts) && nodeIds.contentEquals(other.nodeIds) && center == other.center &&
            lanelet == other.lanelet

    override fun hashCode(): Int = 31 * id.hashCode() + pts.contentHashCode()
}

/**
 * Exact change signature for diffing (mirrors Jython `_feature_sig`). Shares
 * the feature's arrays rather than copying them, so the engine's `sent` map
 * costs no extra memory per streamed way.
 */
class FeatureSignature(
    val nodeIds: LongArray,
    val pts: DoubleArray,
    val type: String?,
    val subtype: String?,
    val participantBicycle: String?,
    val lanelet: LaneletRefs? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is FeatureSignature && nodeIds.contentEquals(other.nodeIds) && pts.contentEquals(other.pts) &&
            type == other.type && subtype == other.subtype && participantBicycle == other.participantBicycle &&
            lanelet == other.lanelet

    override fun hashCode(): Int = 31 * nodeIds.contentHashCode() + pts.contentHashCode()
}

sealed class PatchOp {
    abstract val op: String

    data class Upsert(val feature: ViewerFeature) : PatchOp() {
        override val op: String = "upsert"
    }

    data class Remove(val id: String) : PatchOp() {
        override val op: String = "remove"
    }
}

sealed class OutboundMessage {
    data class Snapshot(
        val anchor: Anchor?,
        val features: List<ViewerFeature>,
    ) : OutboundMessage()

    data class Patch(val ops: List<PatchOp>) : OutboundMessage()

    /**
     * JOSM's current selection, as the viewer can show it: node unique ids and
     * way feature ids ("way/<id>"; a selected relation contributes its member
     * ways). [truncated] when it was capped at [Viewer3dConstants.MAX_SELECTION_SYNC].
     */
    data class Selection(
        val nodeIds: List<Long>,
        val wayIds: List<String>,
        val truncated: Boolean = false,
    ) : OutboundMessage()

    /**
     * JOSM's answer to one browser command, matched by the command's [id].
     * [warning]: applied, but worth a look (e.g. a height jump it introduced).
     */
    data class CommandResult(
        val id: String,
        val ok: Boolean,
        val message: String,
        val warning: String? = null,
    ) : OutboundMessage()
}

/** Parsed browser → JOSM command (before JOSM Command construction). */
sealed class InboundOp {
    /**
     * Move a node. Only the components present change: without x/y the node
     * keeps its lat/lon exactly, without z its `ele` tag is left alone (so an
     * XY drag never adds `ele` to a node that had none).
     */
    data class MoveNode(
        val id: String,
        val x: Double?,
        val y: Double?,
        val z: Double?,
    ) : InboundOp()

    data class SetTag(val id: String, val key: String, val value: String) : InboundOp()

    data class SetView(val x: Double, val y: Double, val force: Boolean) : InboundOp()

    /** Replace JOSM's selection ("node/1", "way/2", ...). */
    data class Select(val ids: List<String>) : InboundOp()

    /** Select [ids] and run JOSM's own Delete action on them (with its warnings). */
    data class DeleteSelection(val ids: List<String>) : InboundOp()

    /**
     * Interpolate `ele` along [way] between [anchors] (two or more of its
     * nodes), or between its two ends when fewer are given.
     */
    data class InterpolateHeight(val way: String, val anchors: List<String>) : InboundOp()

    data object Undo : InboundOp()

    data object Redo : InboundOp()
}

/** [id] is set by the viewer when it wants a [OutboundMessage.CommandResult]. */
data class InboundCommand(val ops: List<InboundOp>, val id: String? = null)

object Viewer3dConstants {
    const val VIEWPORT_ID = "viewport"
    const val MAX_WAYS_PER_CYCLE = 999
    const val QUEUE_MAX = 2000
    const val DEBOUNCE_MS = 200
    const val VIEWPORT_DEBOUNCE_MS = 1000
    const val SEQUENCE_TITLE = "3D viewer edit"
    const val MAX_SELECTION_SYNC = 20000
    const val SELECTION_DEBOUNCE_MS = 120
}
