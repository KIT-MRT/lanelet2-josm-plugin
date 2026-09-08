package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import kotlin.math.abs

class DebugRegulatoryConnectionsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    private fun stopLine(): Way {
        val w = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        w.put("type", "stop_line")
        return w
    }

    @Test
    fun wayCentroidAveragesLatLon() {
        val w = OsmFixtures.way(0.0 to 0.0, 2.0 to 2.0)
        val c = DebugRegulatoryConnections.wayCentroid(w)!!
        assertEquals(1.0, c.first, 1e-12)
        assertEquals(1.0, c.second, 1e-12)
    }

    @Test
    fun laneletCentroidMeansLeftAndRight() {
        val left = OsmFixtures.way(0.0 to 2.0, 2.0 to 2.0)
        val right = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val c = DebugRegulatoryConnections.laneletCentroid(ll)!!
        assertEquals(1.0, c.first, 1e-12)
        assertEquals(1.0, c.second, 1e-12)
    }

    @Test
    fun indexIsCaseSensitiveOnLaneletType() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val mixed = OsmFixtures.relation("Lanelet")
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        ll.addMember(RelationMember("regulatory_element", reg))
        mixed.addMember(RelationMember("regulatory_element", reg))
        val ds = OsmFixtures.dataSet(ll, mixed, reg)
        val index = DebugRegulatoryConnections.buildRegElemToLaneletsIndex(ds)
        assertEquals(listOf(ll), index[reg.uniqueId])
    }

    @Test
    fun debugLayerTagsAndUndo() {
        val stop = stopLine()
        val tl = OsmFixtures.way(0.0 to 4.0, 2.0 to 4.0)
        tl.put("type", "traffic_light")
        tl.put("subtype", "red_yellow_green")
        val left = OsmFixtures.way(0.0 to 2.0, 2.0 to 2.0)
        val right = OsmFixtures.way(0.0 to 0.0, 2.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val reg = OsmFixtures.relation(
            "regulatory_element",
            "traffic_light",
            "ref_line" to stop,
            "refers" to tl,
        )
        ll.addMember(RelationMember("regulatory_element", reg))
        val source = OsmFixtures.dataSet(stop, tl, ll, reg)

        OsmFixtures.withUndo { undo ->
            val build = DebugRegulatoryConnections.apply(source, listOf(reg), undo = undo)
            assertTrue(build.commands.isNotEmpty())
            assertEquals(1, undo.undoCommands.size)
            assertTrue(undo.lastCommand.descriptionText.contains(DebugRegulatoryConnections.SEQUENCE_NAME))

            val central = build.dataSet.nodes.first { it.get("type") == "regulatory_element_debug" }
            assertEquals(reg.uniqueId.toString(), central.get("source_id"))
            assertEquals(reg.uniqueId.toString(), central.get("id"))
            assertEquals("traffic_light", central.get("subtype"))
            assertEquals(stop.uniqueId.toString(), central.get("ref_line"))
            assertEquals(tl.uniqueId.toString(), central.get("refers"))
            assertEquals(ll.uniqueId.toString(), central.get("lanelets"))

            val toRefLine = build.dataSet.ways.first { it.hasKey("relation_to_ref_line") }
            assertEquals(stop.uniqueId.toString(), toRefLine.get("relation_to_ref_line"))
            val toRefers = build.dataSet.ways.first { it.hasKey("relation_to_refers") }
            assertEquals(tl.uniqueId.toString(), toRefers.get("relation_to_refers"))
            val toLanelet = build.dataSet.ways.first { it.hasKey("relation_to_lanelet") }
            assertEquals(ll.uniqueId.toString(), toLanelet.get("relation_to_lanelet"))
            assertEquals(ll.uniqueId.toString(), toLanelet.get("relation_is_member_of"))

            val tgtTl = build.dataSet.nodes.first { it.get("source_id") == tl.uniqueId.toString() && it.get("type") == "traffic_light" }
            assertEquals("red_yellow_green", tgtTl.get("subtype"))

            val nBefore = build.dataSet.nodes.size + build.dataSet.ways.size
            undo.undo()
            assertTrue(build.dataSet.nodes.isEmpty())
            assertTrue(build.dataSet.ways.isEmpty())
            assertTrue(nBefore > 0)
        }
    }

    @Test
    fun sharedLaneletGetsCrossConnection() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val stopA = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0).also { it.put("type", "stop_line") }
        val stopB = OsmFixtures.way(2.0 to 0.0, 3.0 to 0.0).also { it.put("type", "stop_line") }
        val a = OsmFixtures.relation("regulatory_element", "traffic_light", "ref_line" to stopA)
        val b = OsmFixtures.relation("regulatory_element", "traffic_light", "ref_line" to stopB)
        ll.addMember(RelationMember("regulatory_element", a))
        ll.addMember(RelationMember("regulatory_element", b))
        val source = OsmFixtures.dataSet(ll, a, b, stopA, stopB)
        OsmFixtures.withUndo { undo ->
            val build = DebugRegulatoryConnections.apply(source, listOf(a, b), undo = undo)
            val cross = build.dataSet.ways.first { it.hasKey("connects_regulatory_elements") }
            val ids = cross.get("connects_regulatory_elements")!!.split(",").toSet()
            assertEquals(setOf(a.uniqueId.toString(), b.uniqueId.toString()), ids)
            assertEquals(ll.uniqueId.toString(), cross.get("shared_lanelet"))
        }
    }

    @Test
    fun resolveSubtypeMatchesJythonDialog() {
        assertNull(DebugRegulatoryConnections.resolveSubtype("(any)", ""))
        assertNull(DebugRegulatoryConnections.resolveSubtype(null, "x"))
        assertEquals("traffic_light", DebugRegulatoryConnections.resolveSubtype("traffic_light", ""))
        assertEquals("foo", DebugRegulatoryConnections.resolveSubtype("Custom", "foo"))
        assertNull(DebugRegulatoryConnections.resolveSubtype("Custom", "  "))
    }

    @Test
    fun emptyMembersSkipRelation() {
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        val ds = OsmFixtures.dataSet(reg)
        OsmFixtures.withUndo { undo ->
            val build = DebugRegulatoryConnections.apply(ds, listOf(reg), undo = undo)
            assertTrue(build.commands.isEmpty())
            assertTrue(undo.undoCommands.isEmpty())
        }
    }

    @Test
    fun centralNodeIsMeanOfMemberCentroids() {
        val stop = OsmFixtures.way(0.0 to 0.0, 0.0 to 0.0)
        stop.put("type", "stop_line")
        val tl = OsmFixtures.way(4.0 to 0.0, 4.0 to 0.0)
        tl.put("type", "traffic_light")
        val reg = OsmFixtures.relation(
            "regulatory_element",
            "traffic_light",
            "ref_line" to stop,
            "refers" to tl,
        )
        val source = OsmFixtures.dataSet(stop, tl, reg)
        OsmFixtures.withUndo { undo ->
            val build = DebugRegulatoryConnections.apply(source, listOf(reg), undo = undo)
            val central = build.dataSet.nodes.first { it.get("type") == "regulatory_element_debug" }
            assertTrue(abs(central.coor.lat() - 0.0) < 1e-12)
            assertTrue(abs(central.coor.lon() - 2.0) < 1e-12)
        }
    }
}
