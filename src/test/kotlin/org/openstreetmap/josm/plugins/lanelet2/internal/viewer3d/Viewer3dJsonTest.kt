package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Viewer3dJsonTest {
    @Test
    fun encodesSnapshotCompactly() {
        val json = Viewer3dJson.encode(
            OutboundMessage.Snapshot(
                Anchor(49.0, 8.4),
                listOf(
                    ViewerFeature(
                        id = "way/1",
                        kind = "line",
                        tags = mapOf("type" to "line_thin"),
                        pts = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 12.345),
                        nodeIds = longArrayOf(1, -2),
                    ),
                ),
            ),
        )
        assertTrue(!json.contains(" "))
        assertTrue(json.contains("\"type\":\"snapshot\""))
        assertTrue(json.contains("\"anchor\":{\"lat\":49,\"lon\":8.4}"))
        assertTrue(json.contains("\"pts\":[0,0,0,1,0,12.345]"), json)
        assertTrue(json.contains("\"nodes\":[1,-2]"), json)
    }

    @Test
    fun encodesPatchWithUpsertAndRemove() {
        val json = Viewer3dJson.encode(
            OutboundMessage.Patch(
                listOf(
                    PatchOp.Remove("way/9"),
                    PatchOp.Upsert(
                        ViewerFeature("way/1", "line", emptyMap(), doubleArrayOf(0.0, 0.0, 0.0)),
                    ),
                ),
            ),
        )
        assertTrue(json.contains("\"op\":\"remove\",\"id\":\"way/9\""))
        assertTrue(json.contains("\"op\":\"upsert\""))
    }

    @Test
    fun anchorKeepsItsPrecision() {
        val json = Viewer3dJson.encode(OutboundMessage.Snapshot(Anchor(49.0032012345, 8.42983), emptyList()))
        assertTrue(json.contains("\"anchor\":{\"lat\":49.003201235,\"lon\":8.42983}"), json)
    }

    /** The fast formatter must print millimetre values exactly like `%.3f` trimmed did. */
    @Test
    fun fastNumberFormatMatchesTheOldFormat() {
        val rnd = java.util.Random(7)
        val values = mutableListOf(0.0, -0.0, 1.0, -1.0, 0.1, 0.01, 0.001, -0.001, 12.5, 1234.567, -7030.2, 2147483.647)
        repeat(20000) { values.add(Viewer3dEnu.roundCoord((rnd.nextDouble() - 0.5) * 20000.0)) }
        for (v in values) {
            assertEquals(Viewer3dJson.formatNumSlow(v), Viewer3dJson.formatNum(v), "value $v")
        }
    }

    @Test
    fun parseCommandSkipsUnknownOps() {
        val cmd = Viewer3dJson.parseCommand(
            """{"type":"command","ops":[{"op":"noop"},{"op":"move_node","id":"node/1","x":1,"y":2}]}""",
        )!!
        assertEquals(1, cmd.ops.size)
        assertTrue(cmd.ops[0] is InboundOp.MoveNode)
    }

    @Test
    fun parseSetViewForceFlag() {
        val cmd = Viewer3dJson.parseCommand(
            """{"type":"command","ops":[{"op":"set_view","x":0,"y":0,"force":true}]}""",
        )!!
        val op = cmd.ops.single() as InboundOp.SetView
        assertTrue(op.force)
    }

    @Test
    fun ignoresNonCommandMessages() {
        assertNull(Viewer3dJson.parseCommand("""{"type":"snapshot","features":[]}"""))
    }
}
