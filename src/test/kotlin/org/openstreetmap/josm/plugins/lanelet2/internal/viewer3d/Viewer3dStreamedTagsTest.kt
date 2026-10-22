package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/**
 * What the viewer reads off a streamed way must be in the stream, starting
 * from a real JOSM [Way]. The browser tests drive a fake bridge that sends
 * whatever tags it likes, which once hid that `height` never left JOSM: the
 * Autoware traffic light icon then always lay flat on its line.
 */
class Viewer3dStreamedTagsTest {
    private val anchor = Anchor(49.0, 8.4)
    private lateinit var ds: DataSet

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        ds = DataSet()
    }

    /** A 2-point Autoware traffic light: its bottom edge, [height] tall. */
    private fun light(height: String): Way {
        val nodes = listOf(8.4, 8.400004).map { lon ->
            Node(LatLon(49.0, lon)).also {
                it.put("ele", "168.0")
                ds.addPrimitive(it)
            }
        }
        return Way().also {
            it.setNodes(nodes)
            it.put("type", "traffic_light")
            it.put("subtype", "red_yellow_green")
            it.put("height", height)
            it.put("original_id", "8407")
            ds.addPrimitive(it)
        }
    }

    @Test
    fun anAutowareLightKeepsItsHeight() {
        val feat = Viewer3dFeatures.featureForWay(light("0.86").toSnapshot(), anchor)!!
        assertEquals("0.86", feat.tags["height"])
        assertEquals("traffic_light", feat.tags["type"])
        assertNull(feat.tags["original_id"], "only what the viewer reads is streamed")
        assertEquals(2, feat.nodeIds.size)
        val json = Viewer3dJson.encode(OutboundMessage.Snapshot(anchor, listOf(feat)))
        assertTrue(json.contains("\"height\":\"0.86\""), json)
    }

    @Test
    fun aChangedHeightReachesTheViewer() {
        // One way, only the tag edited: nodes and points stay the same, so
        // nothing but the height can tell the two apart.
        val way = light("0.86")
        val low = Viewer3dFeatures.featureForWay(way.toSnapshot(), anchor)!!
        way.put("height", "1.2")
        val high = Viewer3dFeatures.featureForWay(way.toSnapshot(), anchor)!!
        assertNotEquals(Viewer3dFeatures.featureSignature(low), Viewer3dFeatures.featureSignature(high))
    }
}
