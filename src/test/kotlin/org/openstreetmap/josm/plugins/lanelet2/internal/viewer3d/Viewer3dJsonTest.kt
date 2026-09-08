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
                        points = listOf(listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0)),
                        nodes = listOf("node/1", "node/2"),
                    ),
                ),
            ),
        )
        assertTrue(!json.contains(" "))
        assertTrue(json.contains("\"type\":\"snapshot\""))
        assertTrue(json.contains("\"anchor\":{\"lat\":49,\"lon\":8.4}"))
        assertTrue(json.contains("\"nodes\":[\"node/1\",\"node/2\"]"))
    }

    @Test
    fun encodesPatchWithUpsertAndRemove() {
        val json = Viewer3dJson.encode(
            OutboundMessage.Patch(
                listOf(
                    PatchOp.Remove("way/9"),
                    PatchOp.Upsert(
                        ViewerFeature("way/1", "line", emptyMap(), listOf(listOf(0.0, 0.0, 0.0))),
                    ),
                ),
            ),
        )
        assertTrue(json.contains("\"op\":\"remove\",\"id\":\"way/9\""))
        assertTrue(json.contains("\"op\":\"upsert\""))
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
