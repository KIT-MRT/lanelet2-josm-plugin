package org.openstreetmap.josm.plugins.lanelet2.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class NotesModelTest {

    @Test
    fun accessorsMatchJythonDefaults() {
        assertEquals("", noteText(emptyMap()))
        assertEquals("issue", noteType(emptyMap()))
        assertEquals("issue", noteType(mapOf(NotesTags.TAG_TYPE to "")))
        assertFalse(noteDone(emptyMap()))
        assertTrue(noteDone(mapOf(NotesTags.TAG_DONE to "YES")))
        assertTrue(noteDone(mapOf(NotesTags.TAG_DONE to "true")))
        assertTrue(noteDone(mapOf(NotesTags.TAG_DONE to "1")))
        assertFalse(noteDone(mapOf(NotesTags.TAG_DONE to "no")))
        assertEquals("minor", noteSeverity(emptyMap()))
        assertEquals("minor", noteSeverity(mapOf(NotesTags.TAG_SEVERITY to "weird")))
        assertEquals("breaking", noteSeverity(mapOf(NotesTags.TAG_SEVERITY to "BREAKING")))
        assertEquals(0, severityRank("minor"))
        assertEquals(1, severityRank("major"))
        assertEquals(2, severityRank("breaking"))
        assertEquals(0, severityRank("nope"))
    }

    @Test
    fun asBoolMatchesJython() {
        assertFalse(asBool(null))
        assertTrue(asBool(true))
        assertFalse(asBool(false))
        assertTrue(asBool("Yes"))
        assertTrue(asBool("1"))
        assertFalse(asBool("no"))
    }

    @Test
    fun putAllSkipsEmptyValues() {
        val tags = baseTags(
            text = "",
            ntype = "",
            severity = "nope",
            refs = "",
            id = "deadbeef",
            created = "2020-01-01T00:00:00",
            author = "x",
        )
        assertEquals("", tags[NotesTags.TAG_TEXT])
        assertEquals("issue", tags[NotesTags.TAG_TYPE])
        assertEquals("minor", tags[NotesTags.TAG_SEVERITY])
        val written = filterNonEmpty(tags)
        assertFalse(NotesTags.TAG_TEXT in written)
        assertFalse(NotesTags.TAG_REFS in written)
        assertEquals("yes", written[NotesTags.TAG_MARKER])
        assertEquals("deadbeef", written[NotesTags.TAG_ID])
    }

    @Test
    fun memberTagsCopyStyleFieldsOnly() {
        val base = baseTags("hi", "todo", "major", "w1", id = "ab", created = "t", author = "a")
        assertEquals("yes", memberWayTags(base)[NotesTags.TAG_MARKER])
        assertEquals("ab", memberWayTags(base)[NotesTags.TAG_ID])
        assertNull(memberWayTags(base)[NotesTags.TAG_ANCHOR])
        assertEquals("yes", memberNodeTags(base)[NotesTags.TAG_MEMBER])
        assertNull(memberNodeTags(base)[NotesTags.TAG_MARKER])
    }

    @Test
    fun newNoteIdTakesFirstEightHexChars() {
        assertEquals("550e8400", newNoteId { "550e8400e29b41d4a716446655440000" })
    }

    @Test
    fun nowIsoIsLocalWithoutZone() {
        val t = LocalDateTime.of(2024, 1, 2, 3, 4, 5)
        assertEquals("2024-01-02T03:04:05", nowIso { t })
    }

    @Test
    fun gitAuthorFallsThrough() {
        assertEquals("Pat", gitAuthor(runGitName = { "Pat\n" }, javaUser = { "os" }))
        assertEquals("os", gitAuthor(runGitName = { "  " }, javaUser = { "os" }))
        assertEquals("unknown", gitAuthor(runGitName = { null }, javaUser = { null }))
        assertEquals("os", gitAuthor(runGitName = { throw RuntimeException("git") }, javaUser = { "os" }))
    }

    @Test
    fun refTokensRoundTripIncludingNegativeIds() {
        assertEquals("w-3", refToken(RefKind.WAY, -3))
        assertEquals("n12", refToken(RefKind.NODE, 12))
        assertEquals("r7", refToken(RefKind.RELATION, 7))
        assertEquals(RefToken(RefKind.WAY, -3), parseRefToken("w-3"))
        assertNull(parseRefToken("x1"))
        assertNull(parseRefToken("n"))
        assertNull(parseRefToken("  "))
    }

    @Test
    fun centroidIsArithmeticMean() {
        assertEquals(2.0 to 4.0, centroid(listOf(1.0 to 2.0, 3.0 to 6.0)))
        assertNull(centroid(emptyList()))
    }

    @Test
    fun rowFilterMatchesJython() {
        assertTrue(rowMatchesFilter("", false, true, "x", "issue", "minor"))
        assertFalse(rowMatchesFilter("", true, true, "x", "issue", "minor"))
        assertTrue(rowMatchesFilter("ISS", false, false, "hello", "issue", "minor"))
        assertTrue(rowMatchesFilter("maj", false, false, "hello", "issue", "major"))
        assertFalse(rowMatchesFilter("zzz", false, false, "hello", "issue", "minor"))
    }

    @Test
    fun deriveNotesPathUsesSplitext() {
        assertEquals("/maps/city.notes" to "city.notes", deriveNotesPath("/maps/city.osm"))
        assertEquals("/maps/map.osm.notes" to "map.osm.notes", deriveNotesPath("/maps/map.osm.bz2"))
        assertNull(deriveNotesPath(null))
        assertNull(deriveNotesPath(""))
    }

    @Test
    fun undoStackPushPopClear() {
        val stack = NotesUndoStack()
        assertFalse(stack.canUndo())
        assertNull(stack.pop())
        stack.push(emptyList())
        assertFalse(stack.canUndo())
        val snap = listOf(NoteSnapshot("node", mapOf("k" to "v"), listOf(1.0 to 2.0)))
        stack.push(snap)
        assertTrue(stack.canUndo())
        assertEquals(snap, stack.pop())
        assertFalse(stack.canUndo())
        stack.push(snap)
        stack.clear()
        assertFalse(stack.canUndo())
    }
}
