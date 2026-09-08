package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class SelectRelationsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun tmpFilePathLaneletCompatAndSanitization() {
        val home = System.getProperty("user.home")
        assertEquals(
            File(home, ".lanelet2_selected_lanelets.txt"),
            SelectRelations.getTmpFilePath("lanelet", null),
        )
        assertEquals(
            File(home, ".lanelet2_selected_lanelets.txt"),
            SelectRelations.getTmpFilePath("lanelet", ""),
        )
        assertEquals(
            File("$home/.lanelet2_selected_relations_lanelet_road.txt"),
            SelectRelations.getTmpFilePath("lanelet", "road"),
        )
        assertEquals(
            File("$home/.lanelet2_selected_relations_regulatory_element.txt"),
            SelectRelations.getTmpFilePath("regulatory_element", null),
        )
        assertEquals(
            File("$home/.lanelet2_selected_relations_regulatory_element_traffic_light.txt"),
            SelectRelations.getTmpFilePath("regulatory_element", "traffic_light"),
        )
        assertEquals(
            File("$home/.lanelet2_selected_relations_foo_a_b_c.txt"),
            SelectRelations.getTmpFilePath("foo", "a/b c"),
        )
    }

    @Test
    fun loadAndSaveIdsRoundTrip(@TempDir dir: Path) {
        val file = dir.resolve("ids.txt").toFile()
        assertTrue(SelectRelations.loadIdsFromFile(file).isEmpty())

        file.writeText(
            """
            10

            not-a-number
            -42
            99
            """.trimIndent(),
        )
        assertEquals(listOf(10L, -42L, 99L), SelectRelations.loadIdsFromFile(file))

        val r1 = Relation()
        val r2 = Relation()
        assertTrue(SelectRelations.saveRelationsToFile(listOf(r1, r2), file))
        val loaded = SelectRelations.loadIdsFromFile(file)
        assertEquals(2, loaded.size)
        assertEquals(setOf(r1.uniqueId, r2.uniqueId), loaded.toSet())
    }

    @Test
    fun findRelationsByIdsFiltersTypeAndSubtype() {
        val data = DataSet()
        val keep = Relation()
        keep.put("type", "lanelet")
        keep.put("subtype", "road")
        val otherType = Relation()
        otherType.put("type", "regulatory_element")
        otherType.put("subtype", "road")
        val otherSubtype = Relation()
        otherSubtype.put("type", "Lanelet")
        otherSubtype.put("subtype", "walkway")
        val caseMatch = Relation()
        caseMatch.put("type", "LANELET")
        caseMatch.put("subtype", "ROAD")
        data.addPrimitive(keep)
        data.addPrimitive(otherType)
        data.addPrimitive(otherSubtype)
        data.addPrimitive(caseMatch)

        val ids = listOf(keep.uniqueId, otherType.uniqueId, otherSubtype.uniqueId, caseMatch.uniqueId)
        val anySubtype = SelectRelations.findRelationsByIds(data, ids, "lanelet", null)
        assertEquals(setOf(keep, otherSubtype, caseMatch), anySubtype.toSet())

        val roads = SelectRelations.findRelationsByIds(data, ids, "lanelet", "road")
        assertEquals(setOf(keep, caseMatch), roads.toSet())

        val missing = SelectRelations.findRelationsByIds(data, listOf(keep.uniqueId + 999_999), "lanelet")
        assertTrue(missing.isEmpty())
    }

    @Test
    fun saveFailsOnUnwritableParent(@TempDir dir: Path) {
        val missing = File(dir.toFile(), "no-such-dir/out.txt")
        assertFalse(SelectRelations.saveRelationsToFile(emptyList(), missing))
    }
}
