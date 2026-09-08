package org.openstreetmap.josm.plugins.lanelet2.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NotesTextIoTest {

    @Test
    fun exportThenParseRoundTrip() {
        val notes = listOf(
            ExportNote(
                id = "abcd1234",
                severity = "major",
                type = "issue",
                done = false,
                text = "line1\nline2",
                lat = 49.0,
                lon = 8.4,
                refs = "w123 n456",
            ),
        )
        val text = exportNotesText(notes)
        assertEquals(
            "# note abcd1234 [major] type=issue done=no\n" +
                "text: line1 / line2\n" +
                "coord: 49.000000000, 8.400000000\n" +
                "refs: w123 n456\n",
            text,
        )
        val blocks = parseImport(text)
        assertEquals(1, blocks.size)
        assertEquals("abcd1234", blocks[0].id)
        assertEquals("major", blocks[0].severity)
        assertEquals("issue", blocks[0].type)
        assertEquals("no", blocks[0].done)
        assertEquals("line1 / line2", blocks[0].text)
        assertEquals(49.0 to 8.4, blocks[0].coord)
        assertEquals("w123 n456", blocks[0].refs)
    }

    @Test
    fun parseDoneTrueSynonymsAndSemicolonCoords() {
        val text = """
            # note xyz [breaking] type=question done=true
            text: hello
            coord: 1.5; 2.5
        """.trimIndent()
        val b = parseImport(text).single()
        assertEquals("xyz", b.id)
        assertEquals("breaking", b.severity)
        assertEquals("question", b.type)
        assertEquals("yes", b.done)
        assertEquals(1.5 to 2.5, b.coord)
    }

    @Test
    fun parseSkipsPreambleUntilHashNote() {
        val text = "not a note\n# note only\ntext: x\ncoord: 0, 1\n"
        val b = parseImport(text).single()
        assertEquals("only", b.id)
        assertEquals("x", b.text)
        assertEquals(0.0 to 1.0, b.coord)
    }

    @Test
    fun parseUnknownSeverityStaysMinor() {
        val b = parseImport("# note a [nope] type=todo done=0\ntext: z\n").single()
        assertEquals("minor", b.severity)
        assertEquals("no", b.done)
        assertNull(b.coord)
    }
}
