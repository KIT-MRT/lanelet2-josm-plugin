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

data class ViewerFeature(
    val id: String,
    val kind: String,
    val tags: Map<String, String>,
    val points: List<List<Double>>,
    val nodes: List<String> = emptyList(),
    val center: List<Double>? = null,
)

/** Exact change signature for diffing (mirrors Jython `_feature_sig`). */
data class FeatureSignature(
    val nodes: List<String>,
    val points: List<Triple<Double, Double, Double>>,
    val type: String?,
    val subtype: String?,
    val participantBicycle: String?,
)

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
