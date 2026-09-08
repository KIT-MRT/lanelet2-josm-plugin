package org.openstreetmap.josm.plugins.lanelet2.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class NotesDatasetTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        OsmFixtures.ensurePrefs()
    }

    private fun store(): NotesDataset = NotesDataset(DataSet())

    @Test
    fun addPointNoteWritesAnchorAndSkipsEmptyText() {
        val s = store()
        val tags = baseTags("", "issue", "minor", "", id = "aa", created = "t", author = "a")
        val n = s.addPointNote(49.0, 8.4, tags)
        assertEquals("yes", n.get(NotesTags.TAG_ANCHOR))
        assertEquals("yes", n.get(NotesTags.TAG_MARKER))
        assertNull(n.get(NotesTags.TAG_TEXT))
        assertEquals(1, s.anchors().size)
        assertEquals(n, s.anchors()[0])
    }

    @Test
    fun addNoteFromSelectionPromotesSingleNodeAndKeepsMemberTag() {
        val s = store()
        val src = Node(LatLon(1.0, 2.0))
        val tags = baseTags("hi", "todo", "major", "n1", id = "bb", created = "t", author = "a")
        val marker = s.addNoteFromSelection(listOf(src), tags)
        assertNotNull(marker)
        assertEquals("yes", marker!!.get(NotesTags.TAG_ANCHOR))
        assertEquals("yes", marker.get(NotesTags.TAG_MEMBER))
        assertEquals("hi", marker.get(NotesTags.TAG_TEXT))
        assertEquals(1, s.dataset.nodes.size)
        assertEquals(0, s.dataset.ways.size)
    }

    @Test
    fun addNoteFromSelectionCopiesWayAndPlacesCentroidMarker() {
        val s = store()
        val a = Node(LatLon(0.0, 0.0))
        val b = Node(LatLon(2.0, 4.0))
        val w = Way()
        w.addNode(a)
        w.addNode(b)
        val tags = baseTags("way", "issue", "minor", "w1", id = "cc", created = "t", author = "a")
        val marker = s.addNoteFromSelection(listOf(w), tags)!!
        assertEquals(1.0, marker.coor.lat(), 1e-12)
        assertEquals(2.0, marker.coor.lon(), 1e-12)
        assertEquals(1, s.dataset.ways.size)
        assertEquals("yes", s.dataset.ways.first().get(NotesTags.TAG_MARKER))
        assertEquals("cc", s.dataset.ways.first().get(NotesTags.TAG_ID))
        assertEquals(3, s.dataset.nodes.size)
        val grp = s.groupFor(marker)
        assertTrue(marker in grp)
        assertTrue(s.dataset.ways.first() in grp)
    }

    @Test
    fun copyWaySharesNodesOnlyWithinThatWay() {
        val s = store()
        val n1 = Node(LatLon(0.0, 0.0))
        val n2 = Node(LatLon(1.0, 1.0))
        val closed = Way()
        closed.addNode(n1)
        closed.addNode(n2)
        closed.addNode(n1)
        val copy = s.copyWay(closed)
        assertEquals(3, copy.nodesCount)
        assertTrue(copy.nodes[0] === copy.nodes[2])
        assertEquals(2, s.dataset.nodes.size)
    }

    @Test
    fun setNoteFieldPropagatesTypeDoneSeverity() {
        val s = store()
        val tags = baseTags("x", "issue", "minor", "", id = "dd", created = "t", author = "a")
        val a = Node(LatLon(0.0, 0.0))
        val w = Way()
        w.addNode(a)
        val srcDs = DataSet()
        srcDs.addPrimitive(a)
        srcDs.addPrimitive(w)
        val marker = s.addNoteFromSelection(listOf(w), tags)!!
        s.setNoteField(marker, NotesTags.TAG_DONE, "yes", propagate = true)
        s.setNoteField(marker, NotesTags.TAG_TYPE, "review", propagate = true)
        s.setNoteField(marker, NotesTags.TAG_TEXT, "edited", propagate = false)
        assertEquals("yes", marker.get(NotesTags.TAG_DONE))
        assertEquals("edited", marker.get(NotesTags.TAG_TEXT))
        val way = s.dataset.ways.first()
        assertEquals("yes", way.get(NotesTags.TAG_DONE))
        assertEquals("review", way.get(NotesTags.TAG_TYPE))
        assertNull(way.get(NotesTags.TAG_TEXT))
    }

    @Test
    fun deleteAndUndoRestoresTagsAndCoords() {
        val s = store()
        val tags = baseTags("gone", "issue", "breaking", "", id = "ee", created = "t", author = "a")
        val marker = s.addPointNote(9.0, 8.0, tags)
        val snaps = s.snapshotGroup(marker)
        s.undoStack.push(snaps)
        s.deletePrimitives(s.groupFor(marker))
        assertTrue(s.anchors().isEmpty())
        val restored = s.undoStack.pop()!!
        s.restorePrims(restored)
        val again = s.anchors().single()
        assertEquals("gone", NotesDataset.noteText(again))
        assertEquals("breaking", NotesDataset.noteSeverity(again))
        assertEquals(9.0, again.coor.lat(), 1e-12)
        assertEquals(8.0, again.coor.lon(), 1e-12)
    }

    @Test
    fun restoreClosedWayDoesNotReshareFirstLastNode() {
        val snap = NoteSnapshot(
            kind = "way",
            tags = mapOf(NotesTags.TAG_MARKER to "yes", NotesTags.TAG_ID to "ff"),
            coords = listOf(0.0 to 0.0, 1.0 to 1.0, 0.0 to 0.0),
        )
        val s = store()
        s.restorePrims(listOf(snap))
        val w = s.dataset.ways.single()
        assertEquals(3, w.nodesCount)
        assertFalse(w.nodes[0] === w.nodes[2], "Jython recreate does not share the closing node")
        assertEquals(3, s.dataset.nodes.size)
    }

    @Test
    fun anchorsSortByCreatedThenId() {
        val s = store()
        s.addPointNote(0.0, 0.0, baseTags("b", "issue", "minor", "", id = "b", created = "2020-01-02T00:00:00", author = "a"))
        s.addPointNote(0.0, 0.0, baseTags("a", "issue", "minor", "", id = "a", created = "2020-01-01T00:00:00", author = "a"))
        s.addPointNote(0.0, 0.0, baseTags("c", "issue", "minor", "", id = "c", created = "2020-01-01T00:00:00", author = "a"))
        assertEquals(listOf("a", "c", "b"), s.anchors().map { NotesDataset.noteId(it) })
    }

    @Test
    fun serializeFromDatasetRemapsNegativeIds() {
        val s = store()
        s.addPointNote(49.0, 8.4, baseTags("z", "issue", "minor", "", id = "gg", created = "t", author = "a"))
        val xml = s.serialize()
        assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8'?>\n"))
        assertTrue(xml.contains("generator='lanelet2_notes'"))
        assertTrue(xml.contains("lat='49.000000000'"))
        assertTrue(xml.contains("lon='8.400000000'"))
        assertTrue(xml.contains("<tag k='note_id' v='gg' />"))
        assertTrue(xml.endsWith("\n"))
        val ids = Regex("id='(\\d+)'").findAll(xml).map { it.groupValues[1].toLong() }.toList()
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun refTokenAndResolve() {
        val ds = DataSet()
        val n = Node(LatLon(1.0, 2.0))
        ds.addPrimitive(n)
        val tok = NotesDataset.refToken(n)
        assertTrue(tok.startsWith("n"))
        assertEquals(n, NotesDataset.resolveRef(ds, tok))
        assertEquals(n, NotesDataset.resolveRef(ds, "n${n.uniqueId}"))
        assertNull(NotesDataset.resolveRef(ds, "w${n.uniqueId}"))
        assertEquals(OsmPrimitiveType.NODE, n.type)
    }

    @Test
    fun emptySelectionReturnsNull() {
        val s = store()
        assertNull(s.addNoteFromSelection(emptyList(), baseTags("x", "issue", "minor", "", id = "hh", created = "t", author = "a")))
    }
}
