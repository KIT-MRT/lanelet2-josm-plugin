package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class Viewer3dCommandsTest {
    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun moveNodeAndSetTagProduceOneSequenceCommand() {
        val ds = DataSet()
        val node = Node(LatLon(49.0, 8.4)).also { it.put("ele", "0"); ds.addPrimitive(it) }
        val anchor = Anchor(49.0, 8.4)
        val undo = UndoRedoHandler.getInstance()
        undo.clean()
        val commands = Viewer3dCommands.buildDataCommands(
            listOf(
                InboundOp.MoveNode("node/${node.uniqueId}", 10.0, 20.0, 1.5),
                InboundOp.SetTag("node/${node.uniqueId}", "note", "x"),
            ),
            ds,
            anchor,
        )
        assertEquals(2, commands.size)
        undo.add(SequenceCommand(Viewer3dConstants.SEQUENCE_TITLE, commands))
        assertTrue(undo.hasUndoCommands())
        assertTrue(undo.lastCommand is SequenceCommand)
    }

    @Test
    fun setViewIsExcludedFromDataCommands() {
        val ds = OsmFixtures.dataSet(OsmFixtures.node(8.4, 49.0))
        val commands = Viewer3dCommands.buildDataCommands(
            listOf(InboundOp.SetView(0.0, 0.0, false)),
            ds,
            Anchor(49.0, 8.4),
        )
        assertTrue(commands.isEmpty())
    }

    @Test
    fun parseIdHandlesNodeToken() {
        val (ptype, pid) = Viewer3dCommands.parseId("node/42")!!
        assertEquals(org.openstreetmap.josm.data.osm.OsmPrimitiveType.NODE, ptype)
        assertEquals(42L, pid)
    }
}
