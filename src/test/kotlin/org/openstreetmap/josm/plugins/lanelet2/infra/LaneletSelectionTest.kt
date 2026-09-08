package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class LaneletSelectionTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun extractLaneletsIsCaseInsensitiveOnType() {
        val ll = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val mixed = OsmFixtures.relation("Lanelet")
        assertEquals(listOf(ll, mixed), LaneletSelection.extractLanelets(listOf(ll, mixed)))
        mixed.put("type", "LANELET")
        assertEquals(listOf(ll, mixed), LaneletSelection.extractLanelets(listOf(ll, mixed)))
    }

    @Test
    fun inferFromLinestringsAndSkipAlreadyPresent() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        val fromDirect = LaneletSelection.extractLaneletsOrFromLinestrings(ds, listOf(ll, left))
        assertEquals(1, fromDirect.size)
        assertSame(ll, fromDirect[0])
        val fromWay = LaneletSelection.extractLaneletsOrFromLinestrings(ds, listOf(left))
        assertEquals(listOf(ll), fromWay)
    }

    @Test
    fun findLaneletsRequiresLeftOrRightRole() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.relation("lanelet", "road", "centerline" to w)
        val ds = OsmFixtures.dataSet(rel)
        assertTrue(LaneletSelection.findLaneletsContainingLinestrings(ds, listOf(w)).isEmpty())
    }

    @Test
    fun extractRelationsFiltersSubtypeCaseInsensitively() {
        val a = OsmFixtures.relation("regulatory_element", "Traffic_Light")
        val b = OsmFixtures.relation("regulatory_element", "traffic_sign")
        val c = OsmFixtures.relation("lanelet", "road")
        assertEquals(
            listOf(a),
            LaneletSelection.extractRelations(listOf(a, b, c), "regulatory_element", "traffic_light"),
        )
        assertEquals(
            listOf(a, b),
            LaneletSelection.extractRelations(listOf(a, b, c), "REGULATORY_ELEMENT", null),
        )
    }

    @Test
    fun extractRelationsOrFromWaysInfersParents() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.relation("regulatory_element", "traffic_light", "refers" to w)
        val ds = OsmFixtures.dataSet(rel)
        val found = LaneletSelection.extractRelationsOrFromWays(
            ds, listOf(w), "regulatory_element", "traffic_light",
        )
        assertEquals(listOf(rel), found)
    }
}
