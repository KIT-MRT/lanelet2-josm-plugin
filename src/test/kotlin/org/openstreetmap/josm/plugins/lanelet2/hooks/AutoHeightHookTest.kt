package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class AutoHeightHookTest {
    private lateinit var ds: DataSet

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        UndoRedoHandler.getInstance().clean()
        ds = DataSet()
        AutoHeightHook.attachTo(ds)
    }

    @AfterEach
    fun tearDown() {
        AutoHeightHook.attachTo(null)
    }

    private fun existing(lat: Double, lon: Double, ele: String) =
        Node(LatLon(lat, lon)).also {
            it.put("ele", ele)
            it.setOsmId(1000 + ds.nodes.size.toLong(), 1) // not new: loaded from a file
            ds.addPrimitive(it)
        }

    @Test
    fun isOnByDefault() {
        assertTrue(LaneletSettings.isAutoHeightEnabled())
        assertEquals(2.0, LaneletSettings.getHeightJumpWarnM(), 0.0)
    }

    @Test
    fun aNewNodeTakesTheNearestHeightAsItsOwnUndoStep() {
        existing(49.0, 8.4, "112.5")
        val n = Node(LatLon(49.00001, 8.40001))
        ds.addPrimitive(n)
        assertEquals(1, AutoHeightHook.pendingSize())
        assertNull(AutoHeightHook.flush())
        assertEquals("112.5", n.get("ele"))
        // JOSM describes a SequenceCommand as "Sequence: <name>".
        assertTrue(UndoRedoHandler.getInstance().lastCommand.descriptionText.endsWith(AutoHeightHook.SEQUENCE_TITLE))
    }

    @Test
    fun loadedNodesAndNodesWithHeightsAreLeftAlone() {
        existing(49.0, 8.4, "112.5")
        val withEle = Node(LatLon(49.00001, 8.40001)).also { it.put("ele", "3") }
        ds.addPrimitive(withEle)
        assertEquals(0, AutoHeightHook.pendingSize())
    }

    @Test
    fun disabledDoesNothing() {
        LaneletSettings.setAutoHeightEnabled(false)
        existing(49.0, 8.4, "112.5")
        ds.addPrimitive(Node(LatLon(49.00001, 8.40001)))
        assertEquals(0, AutoHeightHook.pendingSize())
    }

    @Test
    fun aJumpItIntroducesIsReported() {
        val a = existing(49.0, 8.4, "100")
        existing(49.0009, 8.4, "110") // 100 m north, 10 m higher
        val n = Node(LatLon(49.0008, 8.4)) // nearest to the high node
        ds.addPrimitive(n)
        ds.addPrimitive(Way().also { it.setNodes(listOf(a, n)) })
        val warning = AutoHeightHook.flush()
        assertEquals("110", n.get("ele"))
        assertNotNull(warning)
        assertTrue(warning!!.contains("10 m"), warning)
    }
}
