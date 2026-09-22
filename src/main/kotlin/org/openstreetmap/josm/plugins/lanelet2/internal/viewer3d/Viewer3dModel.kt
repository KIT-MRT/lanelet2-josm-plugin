package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/** Headless snapshots of OSM geometry for the 3D bridge. No JOSM types here. */
data class NodeSnapshot(
    val uniqueId: Long,
    val lat: Double?,
    val lon: Double?,
    val eleTag: String?,
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
 * One renderable object for the viewer. Wire format (protocol v2):
 * `{"id","kind","tags","pts":[x,y,z,...],"nodes":[uniqueId,...],"center"}`.
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
) {
    val vertexCount: Int get() = pts.size / 3

    fun copy(tags: Map<String, String> = this.tags, center: List<Double>? = this.center) =
        ViewerFeature(id, kind, tags, pts, nodeIds, center)

    override fun equals(other: Any?): Boolean =
        other is ViewerFeature && id == other.id && kind == other.kind && tags == other.tags &&
            pts.contentEquals(other.pts) && nodeIds.contentEquals(other.nodeIds) && center == other.center

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
) {
    override fun equals(other: Any?): Boolean =
        other is FeatureSignature && nodeIds.contentEquals(other.nodeIds) && pts.contentEquals(other.pts) &&
            type == other.type && subtype == other.subtype && participantBicycle == other.participantBicycle

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
}

/** Parsed browser → JOSM command (before JOSM Command construction). */
sealed class InboundOp {
    data class MoveNode(
        val id: String,
        val x: Double,
        val y: Double,
        val z: Double?,
    ) : InboundOp()

    data class SetTag(val id: String, val key: String, val value: String) : InboundOp()

    data class SetView(val x: Double, val y: Double, val force: Boolean) : InboundOp()
}

data class InboundCommand(val ops: List<InboundOp>)

object Viewer3dConstants {
    const val VIEWPORT_ID = "viewport"
    const val MAX_WAYS_PER_CYCLE = 999
    const val QUEUE_MAX = 2000
    const val DEBOUNCE_MS = 200
    const val VIEWPORT_DEBOUNCE_MS = 1000
    const val SEQUENCE_TITLE = "3D viewer edit"
}
