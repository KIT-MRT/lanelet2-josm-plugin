package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class DeleteRelationsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun removesFromReferrersThenDeletesAndUndoRestoresBoth() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        ll.addMember(RelationMember("regulatory_element", reg))
        val ds = OsmFixtures.dataSet(ll, reg)
        OsmFixtures.withUndo { undo ->
            val result = DeleteRelations.apply(ds, listOf(reg), undo = undo)
            assertEquals(1, result.deleted)
            assertEquals(1, result.referrersUpdated)
            assertTrue(reg.isDeleted)
            assertFalse(ll.isDeleted)
            assertEquals(2, ll.membersCount)
            assertTrue(ll.members.none { it.member === reg })
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertFalse(reg.isDeleted)
            assertEquals(3, ll.membersCount)
            assertTrue(ll.members.any { it.member === reg && it.role == "regulatory_element" })
        }
    }

    @Test
    fun deletingARelationThatIsNotAMemberOnlyDeletesIt() {
        val reg = OsmFixtures.relation("regulatory_element", "traffic_sign")
        val ds = OsmFixtures.dataSet(reg)
        OsmFixtures.withUndo { undo ->
            val result = DeleteRelations.apply(ds, listOf(reg), undo = undo)
            assertEquals(1, result.deleted)
            assertEquals(0, result.referrersUpdated)
            assertTrue(reg.isDeleted)
            undo.undo()
            assertFalse(reg.isDeleted)
        }
    }

    @Test
    fun emptyInputIsNoOp() {
        val ds = OsmFixtures.dataSet()
        OsmFixtures.withUndo { undo ->
            val result = DeleteRelations.apply(ds, emptyList(), undo = undo)
            assertEquals(0, result.deleted)
            assertTrue(undo.undoCommands.isEmpty())
        }
    }

    @Test
    fun typeSubtypeResolveMatchesJython() {
        assertEquals(TypeSubtype.Choice("lanelet", null), TypeSubtype.resolve("lanelet", "", "(any)", ""))
        assertEquals(TypeSubtype.Choice("lanelet", "road"), TypeSubtype.resolve("lanelet", "", "road", ""))
        assertEquals(TypeSubtype.Choice("foo", "bar"), TypeSubtype.resolve("Custom", "foo", "Custom", "bar"))
        assertEquals(TypeSubtype.Choice("foo", null), TypeSubtype.resolve("Custom", "foo", "Custom", "  "))
        assertNull(TypeSubtype.resolve("Custom", "  ", "(any)", ""))
        assertEquals(TypeSubtype.LANELET_SUBTYPES, TypeSubtype.subtypesFor("lanelet"))
        assertTrue("traffic_light" in TypeSubtype.subtypesFor("regulatory_element"))
        assertEquals(listOf("(any)", "Custom"), TypeSubtype.subtypesFor("area"))
    }

    @Test
    fun typeSubtypePromptUsesPicksAndCustomFields() {
        val ui = ScriptedPrompts(
            picks = mutableListOf("Custom", "Custom"),
            asks = mutableListOf("mytype", "mysub"),
        )
        assertEquals(
            TypeSubtype.Choice("mytype", "mysub"),
            TypeSubtype.prompt(ui, TypeSubtype.DELETE_RELATION_TYPES, "t"),
        )
        ui.picks += listOf("Custom", "(any)")
        ui.asks += "  "
        assertNull(TypeSubtype.prompt(ui, TypeSubtype.DELETE_RELATION_TYPES, "t"))
        assertTrue(ui.warnings.any { it.first == "Enter a custom type." })
    }
}

private class ScriptedPrompts(
    val picks: MutableList<String> = mutableListOf(),
    val asks: MutableList<String> = mutableListOf(),
    val warnings: MutableList<Pair<String, String>> = mutableListOf(),
) : org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts {
    override fun warn(message: String, title: String) {
        warnings.add(message to title)
    }
    override fun info(message: String, title: String) {}
    override fun infoAutoClose(message: String, title: String, delayMs: Int) {}
    override fun confirm(message: String, title: String) = false
    override fun pick(title: String, message: String, options: List<String>): String? =
        if (picks.isEmpty()) null else picks.removeAt(0)
    override fun ask(title: String, message: String): String? =
        if (asks.isEmpty()) null else asks.removeAt(0)
}
