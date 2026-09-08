package org.openstreetmap.josm.plugins.lanelet2.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class NotesFormatTest {

    private fun fixtureText(): String =
        File("testdata/notes/jython_serialize_fixture.notes").readText(StandardCharsets.UTF_8)

    private fun fixtureRecords(): Pair<List<NoteNodeRec>, List<NoteWayRec>> {
        val n1 = NoteNodeRec(
            uniqueId = 1,
            lat = 49.012345678,
            lon = 8.400000001,
            tags = linkedMapOf(
                "ll2_note" to "yes",
                "note_anchor" to "yes",
                "note_author" to "tester",
                "note_created" to "2024-01-02T03:04:05",
                "note_done" to "no",
                "note_id" to "abcd1234",
                "note_refs" to "w123 n456",
                "note_severity" to "major",
                "note_text" to "Hello & <world>\nline2\t'quote' \"q\"",
                "note_type" to "issue",
            ),
        )
        val n2 = NoteNodeRec(2, 49.0, 8.4)
        val n3 = NoteNodeRec(3, 49.000001, 8.400001)
        val nNeg = NoteNodeRec(
            uniqueId = -7,
            lat = 48.5,
            lon = 8.25,
            tags = linkedMapOf(
                "note_member" to "yes",
                "note_id" to "abcd1234",
                "note_type" to "issue",
                "note_severity" to "major",
                "note_done" to "no",
            ),
        )
        val nDel = NoteNodeRec(99, 1.0, 2.0, tags = mapOf("ll2_note" to "yes"), deleted = true)
        val nInc = NoteNodeRec(100, 1.0, 2.0, tags = mapOf("ll2_note" to "yes"), incomplete = true)
        val w4 = NoteWayRec(
            uniqueId = 4,
            nodeUniqueIds = listOf(2, 3),
            tags = linkedMapOf(
                "ll2_note" to "yes",
                "note_id" to "abcd1234",
                "note_type" to "issue",
                "note_severity" to "major",
                "note_done" to "no",
            ),
        )
        return listOf(n1, n2, n3, nNeg, nDel, nInc) to listOf(w4)
    }

    @Test
    fun serializeMatchesJythonFixtureBytes() {
        val (nodes, ways) = fixtureRecords()
        val idMap = LinkedHashMap<Long, Long>()
        val xml = serializeNotes(nodes, ways, idMap)
        val expected = fixtureText()
        assertEquals(expected, xml)
        assertEquals(expected.toByteArray(StandardCharsets.UTF_8).toList(), xml.toByteArray(StandardCharsets.UTF_8).toList())
        assertEquals(mapOf(1L to 1L, 2L to 2L, 3L to 3L, -7L to 5L, 4L to 4L), idMap)
    }

    @Test
    fun secondSaveKeepsAssignedIds() {
        val (nodes, ways) = fixtureRecords()
        val idMap = LinkedHashMap<Long, Long>()
        val first = serializeNotes(nodes, ways, idMap)
        val second = serializeNotes(nodes, ways, idMap)
        assertEquals(first, second)
    }

    @Test
    fun idMapOverrideWinsOverUniqueId() {
        val node = NoteNodeRec(-1, 1.0, 2.0, tags = mapOf("a" to "b"))
        val idMap = mutableMapOf(-1L to 99L)
        val xml = serializeNotes(listOf(node), emptyList(), idMap)
        assertTrue(xml.contains("id='99'"))
        assertEquals(99L, idMap[-1])
    }

    @Test
    fun negativeIdsStartAtOneWhenAllNew() {
        val n = NoteNodeRec(-3, 0.0, 0.0)
        val idMap = LinkedHashMap<Long, Long>()
        val xml = serializeNotes(listOf(n), emptyList(), idMap)
        assertTrue(xml.contains("id='1'"), xml)
        assertEquals(1L, idMap[-3])
    }

    @Test
    fun escapeMatchesJythonOrder() {
        assertEquals(
            "Hello &amp; &lt;world&gt;&#10;line2&#9;&apos;quote&apos; &quot;q&quot;",
            escapeXmlAttr("Hello & <world>\nline2\t'quote' \"q\""),
        )
        assertEquals("None", escapeXmlAttr(null))
        assertEquals("49.000000000", formatCoord(49.0))
        assertEquals("8.400000000", formatCoord(8.4))
        assertEquals("49.000001000", formatCoord(49.000001))
    }

    @Test
    fun atomicWriteIsUtf8WithTrailingNewline(@TempDir dir: Path) {
        val dest = dir.resolve("out.notes").toFile()
        val text = serializeNotes(
            listOf(NoteNodeRec(1, 1.0, 2.0, tags = mapOf("note_text" to "ä"))),
            emptyList(),
            mutableMapOf(),
        )
        atomicWriteUtf8(dest, text)
        assertEquals(text, dest.readText(StandardCharsets.UTF_8))
        assertTrue(text.endsWith("\n"))
        assertTrue(!File(dest.path + ".tmp").exists())
    }
}
