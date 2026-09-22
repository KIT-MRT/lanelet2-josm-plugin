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

    /** Node [east] metres east of (49, 8.4). */
    private fun at(east: Double) = LatLon(49.0, 8.4 + east / (111_320.0 * Math.cos(Math.toRadians(49.0))))

    @Test
    fun aNodeInsertedIntoAWayIsInterpolated() {
        val a = existing(49.0, 8.4, "100")
        val b = Node(at(40.0)).also { it.put("ele", "110"); it.setOsmId(2000, 1); ds.addPrimitive(it) }
        val w = Way().also { it.setNodes(listOf(a, b)); it.setOsmId(3000, 1); ds.addPrimitive(it) }
        val n = Node(at(10.0))
        ds.addPrimitive(n)
        w.setNodes(listOf(a, n, b)) // shift+click on the way in JOSM's add mode
        AutoHeightHook.flush()
        assertEquals(102.5, n.get("ele")!!.toDouble(), 0.01)
    }

    @Test
    fun aWayDrawnClickByClickIsReprofiledWhenItIsFinished() {
        val e1 = existing(49.0, 8.4, "100")
        val e2 = Node(at(40.0)).also { it.put("ele", "110"); it.setOsmId(2000, 1); ds.addPrimitive(it) }
        val w = Way()
        val n1 = Node(at(10.0))
        ds.addPrimitive(n1)
        w.setNodes(listOf(e1, n1))
        ds.addPrimitive(w)
        AutoHeightHook.flush() // n1 is the free end: nearest height
        assertEquals("100", n1.get("ele"))
        val n2 = Node(at(20.0))
        ds.addPrimitive(n2)
        w.setNodes(listOf(e1, n1, n2))
        AutoHeightHook.flush()
        assertEquals("100", n2.get("ele"))
        w.setNodes(listOf(e1, n1, n2, e2)) // finished on an existing node
        AutoHeightHook.flush()
        assertEquals(102.5, n1.get("ele")!!.toDouble(), 0.01)
        assertEquals(105.0, n2.get("ele")!!.toDouble(), 0.01)
        assertTrue(UndoRedoHandler.getInstance().lastCommand.descriptionText.endsWith(AutoHeightHook.REFRESH_TITLE))
    }

    @Test
    fun aHeightSetByHandIsNotReprofiled() {
        val e1 = existing(49.0, 8.4, "100")
        val e2 = Node(at(40.0)).also { it.put("ele", "110"); it.setOsmId(2000, 1); ds.addPrimitive(it) }
        val w = Way()
        val n1 = Node(at(10.0))
        ds.addPrimitive(n1)
        w.setNodes(listOf(e1, n1))
        ds.addPrimitive(w)
        AutoHeightHook.flush()
        n1.put("ele", "99") // the user's own value
        w.setNodes(listOf(e1, n1, e2))
        AutoHeightHook.flush()
        assertEquals("99", n1.get("ele"))
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
