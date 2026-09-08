package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class CheckLaneletBordersTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun validLaneletIsNotBroken() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        val result = CheckLaneletBorders.findBrokenLanelets(ds)
        assertTrue(result.broken.isEmpty())
        assertEquals(0, result.countLt1)
        assertEquals(0, result.countGt1)
    }

    @Test
    fun missingSideIncrementsLt1() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel)
        val result = CheckLaneletBorders.findBrokenLanelets(ds)
        assertEquals(listOf(rel), result.broken)
        assertEquals(1, result.countLt1)
        assertEquals(0, result.countGt1)
    }

    @Test
    fun extraSideIncrementsGt1() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val extra = OsmFixtures.way(0.0 to 0.5, 1.0 to 0.5)
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.addMember(RelationMember("left", extra))
        val ds = OsmFixtures.dataSet(rel)
        val result = CheckLaneletBorders.findBrokenLanelets(ds)
        assertEquals(1, result.broken.size)
        assertEquals(0, result.countLt1)
        assertEquals(1, result.countGt1)
    }

    @Test
    fun missingAndExtraIncrementsBothCounters() {
        val leftA = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val leftB = OsmFixtures.way(0.0 to 1.1, 1.0 to 1.1)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", leftA))
        rel.addMember(RelationMember("left", leftB))
        val ds = OsmFixtures.dataSet(rel)
        val result = CheckLaneletBorders.findBrokenLanelets(ds)
        assertEquals(1, result.countLt1)
        assertEquals(1, result.countGt1)
    }

    @Test
    fun typeMustMatchLaneletExactly() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "Lanelet")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel)
        assertTrue(CheckLaneletBorders.findBrokenLanelets(ds).broken.isEmpty())
    }

    @Test
    fun deletedLaneletsAreIgnored() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel)
        rel.setDeleted(true)
        assertTrue(CheckLaneletBorders.findBrokenLanelets(ds).broken.isEmpty())
    }

    @Test
    fun selectBrokenSetsDatasetSelection() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel)
        CheckLaneletBorders.selectBroken(ds, listOf(rel))
        assertTrue(rel in ds.selected)
        assertEquals(1, ds.selected.size)
    }

    @Test
    fun countLeftRightIgnoresNonWayMembers() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.addMember(RelationMember("left", Relation()))
        val (l, r) = CheckLaneletBorders.countLeftRightMembers(rel)
        assertEquals(1, l)
        assertEquals(1, r)
        assertSame(left, rel.members[0].member)
        assertFalse(rel.isDeleted)
    }
}
