package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class RegulatoryElementsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun extractMembersByRoleDefaultAndFilter() {
        val refers = Way()
        val yield = Way()
        val extra = Way()
        val node = Node()
        val rel = Relation()
        rel.addMember(RelationMember("refers", refers))
        rel.addMember(RelationMember("yield", yield))
        rel.addMember(RelationMember("not_a_role", extra))
        rel.addMember(RelationMember("ref_line", node))
        rel.addMember(RelationMember("refers", Way()))

        val all = RegulatoryElements.extractMembersByRole(rel)
        assertEquals(setOf("refers", "yield", "ref_line"), all.keys)
        assertEquals(2, all["refers"]!!.size)
        assertSame(yield, all["yield"]!!.single())
        assertTrue("not_a_role" !in all)

        val filtered = RegulatoryElements.extractMembersByRole(rel, listOf("yield"))
        assertEquals(setOf("yield"), filtered.keys)
        val emptyRoles = RegulatoryElements.extractMembersByRole(rel, emptyList())
        assertTrue(emptyRoles.isEmpty())
    }

    @Test
    fun extractRefLineOptionalAndRequired() {
        val stop = Way()
        stop.put("type", "stop_line")
        val other = Way()
        other.put("type", "virtual")
        val mixedCase = Way()
        mixedCase.put("type", "Stop_Line")

        val none = RegulatoryElements.extractRefLine(listOf(other), optional = true)
        assertNull(none.first)
        assertNull(none.second)

        val missingRequired = RegulatoryElements.extractStopLine(listOf(other))
        assertNull(missingRequired.first)
        assertEquals("Select exactly 1 stop line (type=stop_line).", missingRequired.second)

        val one = RegulatoryElements.extractRefLine(listOf(other, stop, null), optional = true)
        assertSame(stop, one.first)
        assertNull(one.second)

        val caseInsensitive = RegulatoryElements.extractStopLine(listOf(mixedCase))
        assertSame(mixedCase, caseInsensitive.first)

        val two = Way()
        two.put("type", "stop_line")
        val tooMany = RegulatoryElements.extractRefLine(listOf(stop, two))
        assertNull(tooMany.first)
        assertEquals("Select at most 1 stop line (type=stop_line). Found 2.", tooMany.second)
    }
}
