package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class CreateRegulatoryRelationsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    private fun stopLine(): Way {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        w.put("type", "stop_line")
        return w
    }

    private fun typedWay(type: String, vararg lonLat: Pair<Double, Double>): Way {
        val w = OsmFixtures.way(*lonLat)
        w.put("type", type)
        return w
    }

    private fun lanelet() = OsmFixtures.laneletRelation(
        OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
        OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
    )

    @Test
    fun trafficLightExtractsThreeTypeStrings() {
        val tl = typedWay("traffic_light", 0.0 to 2.0, 1.0 to 2.0)
        val bike = typedWay("Traffic_Light_Bicycle", 0.0 to 3.0, 1.0 to 3.0)
        val ped = typedWay("traffic_light_pedestrian", 0.0 to 4.0, 1.0 to 4.0)
        val other = typedWay("traffic_sign", 0.0 to 5.0, 1.0 to 5.0)
        val bikes = typedWay("traffic_light_bikes", 0.0 to 6.0, 1.0 to 6.0)
        val found = CreateTrafficLightRelation.extractTrafficLights(listOf(tl, bike, ped, other, bikes))
        assertEquals(listOf(tl, bike, ped), found)
    }

    @Test
    fun trafficLightTagsRolesAndUndo() {
        val stop = stopLine()
        val tl = typedWay("traffic_light", 0.0 to 2.0, 1.0 to 2.0)
        val ll = lanelet()
        val ds = OsmFixtures.dataSet(stop, tl, ll)
        OsmFixtures.withUndo { undo ->
            val rel = CreateTrafficLightRelation.apply(ds, stop, listOf(tl), listOf(ll), undo = undo)
            assertTrue(rel in ds.relations)
            assertEquals("regulatory_element", rel.get("type"))
            assertEquals("traffic_light", rel.get("subtype"))
            assertEquals(2, rel.membersCount)
            assertEquals("ref_line", rel.members[0].role)
            assertSame(stop, rel.members[0].member)
            assertEquals("refers", rel.members[1].role)
            assertSame(tl, rel.members[1].member)
            assertEquals("regulatory_element", ll.members.last().role)
            assertSame(rel, ll.members.last().member)
            assertEquals(1, undo.undoCommands.size)
            assertTrue(undo.lastCommand.descriptionText.contains(CreateTrafficLightRelation.SEQUENCE_NAME))
            undo.undo()
            assertTrue(rel !in ds.relations)
            assertEquals(2, ll.membersCount)
        }
    }

    @Test
    fun trafficSignOptionalRefLineOmitted() {
        val sign = typedWay("traffic_sign", 0.0 to 2.0, 1.0 to 2.0)
        val ll = lanelet()
        val ds = OsmFixtures.dataSet(sign, ll)
        OsmFixtures.withUndo { undo ->
            val rel = CreateTrafficSignRelation.apply(ds, null, listOf(sign), listOf(ll), undo = undo)
            assertEquals("traffic_sign", rel.get("subtype"))
            assertEquals(1, rel.membersCount)
            assertEquals("refers", rel.members[0].role)
            undo.undo()
            assertTrue(rel !in ds.relations)
        }
    }

    @Test
    fun trafficSignRefersThenRefLine() {
        val sign = typedWay("traffic_sign", 0.0 to 2.0, 1.0 to 2.0)
        val ll = lanelet()
        val stop = stopLine()
        val ds = OsmFixtures.dataSet(stop, sign, ll)
        OsmFixtures.withUndo { undo ->
            val rel = CreateTrafficSignRelation.apply(ds, stop, listOf(sign), listOf(ll), undo = undo)
            assertEquals(2, rel.membersCount)
            assertEquals("refers", rel.members[0].role)
            assertEquals("ref_line", rel.members[1].role)
            assertSame(stop, rel.members[1].member)
        }
    }

    @Test
    fun speedLimitSignTypeTagWithoutSigns() {
        val ll = lanelet()
        val ds = OsmFixtures.dataSet(ll)
        OsmFixtures.withUndo { undo ->
            val rel = CreateSpeedLimitRelation.apply(
                ds, null, emptyList(), " 50 km/h ", listOf(ll), undo = undo,
            )
            assertEquals("speed_limit", rel.get("subtype"))
            assertEquals("50 km/h", rel.get("sign_type"))
            assertEquals(0, rel.membersCount)
            undo.undo()
            assertTrue(rel !in ds.relations)
        }
    }

    @Test
    fun speedLimitRefersThenRefLine() {
        val ll = lanelet()
        val sign = typedWay("traffic_sign", 0.0 to 2.0, 1.0 to 2.0)
        val stop = stopLine()
        val ds = OsmFixtures.dataSet(sign, stop, ll)
        OsmFixtures.withUndo { undo ->
            val rel = CreateSpeedLimitRelation.apply(
                ds, stop, listOf(sign), null, listOf(ll), undo = undo,
            )
            assertNull(rel.get("sign_type"))
            assertEquals("refers", rel.members[0].role)
            assertEquals("ref_line", rel.members[1].role)
        }
    }

    @Test
    fun rightOfWayYieldThenPriorityThenOptionalRefLine() {
        val yieldLl = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val rowLl = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 3.0, 1.0 to 3.0),
            OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0),
        )
        val stop = stopLine()
        val ds = OsmFixtures.dataSet(yieldLl, rowLl, stop)
        OsmFixtures.withUndo { undo ->
            val rel = CreateRightOfWayRelation.apply(
                ds, stop, listOf(rowLl), listOf(yieldLl), undo = undo,
            )
            assertEquals("right_of_way", rel.get("subtype"))
            assertEquals(3, rel.membersCount)
            assertEquals("yield", rel.members[0].role)
            assertSame(yieldLl, rel.members[0].member)
            assertEquals("right_of_way", rel.members[1].role)
            assertSame(rowLl, rel.members[1].member)
            assertEquals("ref_line", rel.members[2].role)
            assertEquals("regulatory_element", yieldLl.members.last().role)
            assertEquals("regulatory_element", rowLl.members.last().role)
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertTrue(rel !in ds.relations)
            assertEquals(2, yieldLl.membersCount)
            assertEquals(2, rowLl.membersCount)
        }
    }

    @Test
    fun rightOfWayDuplicateInBothListsStillOneMember() {
        val ll = lanelet()
        val ds = OsmFixtures.dataSet(ll)
        OsmFixtures.withUndo { undo ->
            CreateRightOfWayRelation.apply(ds, null, listOf(ll), listOf(ll), undo = undo)
            val regMembers = ll.members.filter { it.role == "regulatory_element" }
            assertEquals(1, regMembers.size)
        }
    }

    @Test
    fun convertBikesPreservesOtherTagsAndUndo() {
        val target = typedWay("traffic_light_bikes", 0.0 to 0.0, 1.0 to 0.0)
        target.put("subtype", "red_yellow_green")
        target.put("direction", "left")
        val other = typedWay("traffic_light", 0.0 to 1.0, 1.0 to 1.0)
        OsmFixtures.dataSet(target, other)
        OsmFixtures.withUndo { undo ->
            val n = ConvertTrafficLightBikes.apply(
                ConvertTrafficLightBikes.extractTargets(listOf(target, other)),
                undo = undo,
            )
            assertEquals(1, n)
            assertEquals("traffic_light", target.get("type"))
            assertEquals("no", target.get("participant:vehicle"))
            assertEquals("yes", target.get("participant:bicycle"))
            assertEquals("red_yellow_green", target.get("subtype"))
            assertEquals("left", target.get("direction"))
            assertEquals("traffic_light", other.get("type"))
            assertNull(other.get("participant:bicycle"))
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertEquals("traffic_light_bikes", target.get("type"))
            assertNull(target.get("participant:vehicle"))
            assertNull(target.get("participant:bicycle"))
        }
    }

    @Test
    fun convertRejectsWhitespaceTypeMismatch() {
        val w = typedWay("  TRAFFIC_LIGHT_BIKES  ", 0.0 to 0.0, 1.0 to 0.0)
        assertTrue(ConvertTrafficLightBikes.isTrafficLightBikes(w))
        val no = typedWay("traffic_light_bicycle", 0.0 to 1.0, 1.0 to 1.0)
        assertFalse(ConvertTrafficLightBikes.isTrafficLightBikes(no))
    }
}
