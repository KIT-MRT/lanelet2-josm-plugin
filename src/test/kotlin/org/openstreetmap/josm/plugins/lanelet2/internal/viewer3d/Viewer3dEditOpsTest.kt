package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/** Browser → JOSM editing ops: parsing, partial moves, replies, editor actions. */
class Viewer3dEditOpsTest {
    private val anchor = Anchor(49.0, 8.4)
    private lateinit var ds: DataSet
    private var visible = true
    private lateinit var withEle: Node
    private lateinit var noEle: Node

    private class FakeActions : EditorActions {
        val calls = mutableListOf<String>()
        var refuseDelete: String? = null
        override fun select(ds: DataSet, prims: Collection<OsmPrimitive>) {
            calls.add("select ${prims.size}")
        }
        override fun delete(ds: DataSet, prims: Collection<OsmPrimitive>): String? {
            calls.add("delete ${prims.size}")
            return refuseDelete
        }
        override fun undo(): Boolean = calls.add("undo")
        override fun redo(): Boolean = calls.add("redo")
    }

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        UndoRedoHandler.getInstance().clean()
        ds = DataSet()
        withEle = Node(LatLon(49.0, 8.4)).also { it.put("ele", "110.5"); ds.addPrimitive(it) }
        noEle = Node(LatLon(49.0001, 8.4001)).also { ds.addPrimitive(it) }
        visible = true
    }

    private fun command(json: String) = Viewer3dJson.parseCommand(json)!!

    // What EditTarget.of(layer) does, minus the OsmDataLayer (which needs JOSM's
    // cache manager and cannot be built headless).
    private fun target() = EditTarget(ds, visible) { cmds ->
        UndoRedoHandler.getInstance().add(SequenceCommand(Viewer3dConstants.SEQUENCE_TITLE, cmds))
    }

    private fun apply(cmd: InboundCommand, actions: EditorActions = FakeActions(), noLayer: Boolean = false) =
        Viewer3dCommands.applyInbound(
            cmd, if (noLayer) null else target(), anchor, driveView = false, setView = { _, _ -> }, actions = actions,
        )

    @Test
    fun parsesIdsPartialMovesAndEditorOps() {
        val cmd = command(
            """{"type":"command","id":"c7","ops":[
              {"op":"move_node","id":"node/1","z":2.5},
              {"op":"move_node","id":"node/2","x":1,"y":2},
              {"op":"move_node","id":"node/3","x":1},
              {"op":"select","ids":["node/1","way/2"]},
              {"op":"delete_selection","ids":["node/1"]},
              {"op":"undo"},{"op":"redo"},{"op":"set_view","x":0,"y":0,"force":"True"}]}""",
        )
        assertEquals("c7", cmd.id)
        assertEquals(InboundOp.MoveNode("node/1", null, null, 2.5), cmd.ops[0])
        assertEquals(InboundOp.MoveNode("node/2", 1.0, 2.0, null), cmd.ops[1])
        // x without y is malformed and dropped, so the select follows.
        assertEquals(InboundOp.Select(listOf("node/1", "way/2")), cmd.ops[2])
        assertEquals(InboundOp.DeleteSelection(listOf("node/1")), cmd.ops[3])
        assertEquals(InboundOp.Undo, cmd.ops[4])
        assertEquals(InboundOp.Redo, cmd.ops[5])
        assertTrue((cmd.ops[6] as InboundOp.SetView).force)
    }

    @Test
    fun heightOnlyMoveKeepsLatLonExactly() {
        val before = withEle.coor
        val r = apply(command("""{"type":"command","id":"a","ops":[{"op":"move_node","id":"node/${withEle.uniqueId}","z":112.25}]}"""))!!
        assertTrue(r.ok, r.message)
        assertEquals(before, withEle.coor)
        assertEquals("112.25", withEle.get("ele"))
    }

    @Test
    fun xyOnlyMoveDoesNotAddEle() {
        val r = apply(command("""{"type":"command","id":"a","ops":[{"op":"move_node","id":"node/${noEle.uniqueId}","x":10,"y":20}]}"""))!!
        assertTrue(r.ok, r.message)
        assertNull(noEle.get("ele"))
        val (lat, lon) = Viewer3dEnu.enuToLatLon(10.0, 20.0, anchor.lat, anchor.lon)
        assertEquals(lat, noEle.coor.lat(), 1e-12)
        assertEquals(lon, noEle.coor.lon(), 1e-12)
    }

    @Test
    fun oneGestureIsOneUndoStep() {
        apply(command("""{"type":"command","id":"a","ops":[
            {"op":"move_node","id":"node/${withEle.uniqueId}","z":1},
            {"op":"move_node","id":"node/${noEle.uniqueId}","z":2}]}"""))
        assertEquals(1, UndoRedoHandler.getInstance().undoCommands.size)
    }

    @Test
    fun aMissingNodeRejectsTheWholeGesture() {
        val r = apply(command("""{"type":"command","id":"a","ops":[
            {"op":"move_node","id":"node/${withEle.uniqueId}","z":1},
            {"op":"move_node","id":"node/-999999","z":2}]}"""))!!
        assertFalse(r.ok)
        assertTrue(r.message.contains("no longer exist"), r.message)
        assertEquals("110.5", withEle.get("ele"))
        assertFalse(UndoRedoHandler.getInstance().hasUndoCommands())
    }

    @Test
    fun hiddenLayerIsRejectedWithAReason() {
        visible = false
        val r = apply(command("""{"type":"command","id":"a","ops":[{"op":"move_node","id":"node/${withEle.uniqueId}","z":1}]}"""))!!
        assertFalse(r.ok)
        assertTrue(r.message.contains("hidden"), r.message)
        assertEquals("110.5", withEle.get("ele"))
    }

    @Test
    fun noLayerIsRejected() {
        val r = apply(command("""{"type":"command","id":"a","ops":[{"op":"move_node","id":"node/1","z":1}]}"""), noLayer = true)!!
        assertFalse(r.ok)
    }

    @Test
    fun commandsWithoutIdGetNoReply() {
        assertNull(apply(command("""{"type":"command","ops":[{"op":"move_node","id":"node/${withEle.uniqueId}","z":1}]}""")))
        assertEquals("1", withEle.get("ele"))
    }

    @Test
    fun editorOpsGoThroughTheActions() {
        val fake = FakeActions()
        assertTrue(apply(command("""{"type":"command","id":"s","ops":[{"op":"select","ids":["node/${withEle.uniqueId}","way/424242"]}]}"""), fake)!!.ok)
        assertTrue(apply(command("""{"type":"command","id":"d","ops":[{"op":"delete_selection","ids":["node/${withEle.uniqueId}"]}]}"""), fake)!!.ok)
        assertTrue(apply(command("""{"type":"command","id":"u","ops":[{"op":"undo"}]}"""), fake)!!.ok)
        assertTrue(apply(command("""{"type":"command","id":"r","ops":[{"op":"redo"}]}"""), fake)!!.ok)
        assertEquals(listOf("select 1", "delete 1", "undo", "redo"), fake.calls)
    }

    @Test
    fun refusedDeleteIsReported() {
        val fake = FakeActions().also { it.refuseDelete = "locked" }
        val r = apply(command("""{"type":"command","id":"d","ops":[{"op":"delete_selection","ids":["node/${withEle.uniqueId}"]}]}"""), fake)!!
        assertFalse(r.ok)
        assertEquals("locked", r.message)
    }

    @Test
    fun selectionMessageExpandsRelationsAndCaps() {
        val w = Way().also {
            it.setNodes(listOf(withEle, noEle))
            ds.addPrimitive(it)
        }
        val rel = Relation().also {
            it.addMember(RelationMember("left", w))
            ds.addPrimitive(it)
        }
        val msg = selectionMessage(listOf(rel, noEle))
        assertEquals(listOf("way/${w.uniqueId}"), msg.wayIds)
        assertEquals(listOf(noEle.uniqueId), msg.nodeIds)
        assertFalse(msg.truncated)
        val json = Viewer3dJson.encode(msg)
        assertTrue(json.startsWith("{\"type\":\"selection\",\"nodes\":[${noEle.uniqueId}],\"ways\":[\"way/"), json)
    }

    @Test
    fun resultEncodes() {
        val json = Viewer3dJson.encode(OutboundMessage.CommandResult("c1", false, "No \"layer\""))
        assertEquals("""{"type":"command_result","id":"c1","ok":false,"message":"No \"layer\""}""", json)
    }
}
