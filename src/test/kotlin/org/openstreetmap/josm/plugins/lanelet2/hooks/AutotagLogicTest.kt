package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class AutotagLogicTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun serializeJoinsOnPipeFirstEquals() {
        assertEquals("", AutotagLogic.serializeTags(emptyList()))
        assertEquals(
            "file_origin=/a/b.osm|note=x",
            AutotagLogic.serializeTags(listOf("file_origin" to "/a/b.osm", "note" to "x")),
        )
    }

    @Test
    fun deserializeStripsKeyOnlyKeepsValueSpaces() {
        assertEquals(emptyList<Pair<String, String>>(), AutotagLogic.deserializeTags(null))
        assertEquals(emptyList<Pair<String, String>>(), AutotagLogic.deserializeTags(""))
        assertEquals(
            listOf("k" to " v ", "b" to "2"),
            AutotagLogic.deserializeTags(" k = v |b=2|nope"),
        )
    }

    @Test
    fun textToTagsStripsBothAndSkipsComments() {
        val text = """
            # comment
            file_origin = /tmp/x.osm
            note=hi
        """.trimIndent()
        assertEquals(
            listOf("file_origin" to "/tmp/x.osm", "note" to "hi"),
            AutotagLogic.textToTags(text),
        )
    }

    @Test
    fun fileOriginReplaceKeepsOtherTags() {
        val tags = listOf("note" to "a", "file_origin" to "/old")
        assertEquals(
            listOf("note" to "a", "file_origin" to "/new"),
            AutotagLogic.replaceFileOrigin(tags, "/new"),
        )
        assertEquals(listOf("note" to "a"), AutotagLogic.replaceFileOrigin(tags, null))
        assertEquals(listOf("note" to "a"), AutotagLogic.replaceFileOrigin(tags, ""))
    }

    @Test
    fun fileOriginBasenameFallsBackToPath() {
        assertNull(AutotagLogic.fileOriginBasename(null))
        assertEquals("map.osm", AutotagLogic.fileOriginBasename("/data/map.osm"))
        assertEquals("/", AutotagLogic.fileOriginBasename("/"))
    }

    @Test
    fun uniqueFileOriginsPreserveFirstSeen() {
        assertEquals(
            listOf("/a", "/b"),
            AutotagLogic.uniqueFileOrigins(listOf("/a", null, "/a", "", "/b")),
        )
    }

    @Test
    fun pickerDialogSizeMatchesJythonClamps() {
        val small = AutotagLogic.pickerDialogSize(maxLen = 1, originCount = 1, charW = 8)
        assertEquals(520, small.width)
        assertEquals(320, small.height)
        val wide = AutotagLogic.pickerDialogSize(maxLen = 400, originCount = 50, charW = 8)
        assertEquals(1200, wide.width)
        // table_h is clamped to 420, so height is 420+130=550; the 640 cap is unreachable.
        assertEquals(550, wide.height)
        val mid = AutotagLogic.pickerDialogSize(maxLen = 80, originCount = 8, charW = 8)
        assertEquals((80 * 8 * 0.62).toInt() + 140, mid.width)
        assertEquals(minOf(maxOf(320, minOf(maxOf(160, 8 * 22 + 28), 420) + 130), 640), mid.height)
    }

    @Test
    fun collectNewSkipsOldAndDuplicates() {
        val fresh = Node(LatLon(0.0, 0.0))
        assertTrue(fresh.isNew)
        val pending = ArrayList<OsmPrimitive>()
        AutotagLogic.collectNewPrimitives(listOf(fresh, fresh, null), pending)
        assertEquals(1, pending.size)
    }

    @Test
    fun targetsForTagRequireSameDatasetNewAndDifferentValue() {
        val n = Node(LatLon(1.0, 2.0))
        val ds = OsmFixtures.dataSet(n)
        n.put("file_origin", "old")
        assertEquals(listOf(n), AutotagLogic.targetsForTag(listOf(n), ds, "file_origin", "new"))
        assertTrue(AutotagLogic.targetsForTag(listOf(n), ds, "file_origin", "old").isEmpty())
    }

    @Test
    fun deleteSetNullWhenNoWaySelected() {
        val n = Node(LatLon(0.0, 0.0))
        OsmFixtures.dataSet(n)
        assertNull(AutotagLogic.collectDeleteSet(listOf(n), protectAnchors = true))
    }

    @Test
    fun deleteSetIncludesTaggedOrphansButNotSharedNodes() {
        val shared = Node(LatLon(0.0, 0.0))
        shared.put("foo", "bar")
        val orphan = Node(LatLon(0.0, 1.0))
        orphan.put("foo", "bar")
        val extra = Node(LatLon(1.0, 1.0))
        val w1 = Way()
        w1.setNodes(listOf(shared, orphan))
        val w2 = Way()
        w2.setNodes(listOf(shared, extra))
        val ds = OsmFixtures.dataSet(w1, w2)
        ds.setSelected(listOf(w1))
        val result = AutotagLogic.collectDeleteSet(ds.selected.toList(), protectAnchors = false)!!
        assertTrue(w1 in result)
        assertTrue(orphan in result)
        assertFalse(shared in result)
        assertFalse(extra in result)
        assertFalse(w2 in result)
    }

    @Test
    fun deleteSetSkipsProtectedMergeAnchors() {
        val anchor = Node(LatLon(0.0, 0.0))
        anchor.put(AutotagLogic.MERGE_ANCHOR_TAG, "yes")
        val other = Node(LatLon(0.0, 1.0))
        val w = Way()
        w.setNodes(listOf(anchor, other))
        val ds = OsmFixtures.dataSet(w)
        ds.setSelected(listOf(w))
        val protectedSet = AutotagLogic.collectDeleteSet(ds.selected.toList(), protectAnchors = true)!!
        assertTrue(w in protectedSet)
        assertTrue(other in protectedSet)
        assertFalse(anchor in protectedSet)
        val open = AutotagLogic.collectDeleteSet(ds.selected.toList(), protectAnchors = false)!!
        assertTrue(anchor in open)
    }

    @Test
    fun anchorCheckDetectsDeletedMovedUntagged() {
        val node = Node(LatLon(49.0, 8.4))
        node.put(AutotagLogic.MERGE_ANCHOR_TAG, "yes")
        val ds = OsmFixtures.dataSet(node)
        val snap = mapOf(node.uniqueId to (49.0 to 8.4))
        val lookup = { id: Long -> ds.getPrimitiveById(id, org.openstreetmap.josm.data.osm.OsmPrimitiveType.NODE) as Node? }
        assertNull(AutotagLogic.checkAnchorCommand(snap, lookup))
        node.setCoor(LatLon(49.1, 8.4))
        assertEquals("moved", AutotagLogic.checkAnchorCommand(snap, lookup)!!.kind)
        node.setCoor(LatLon(49.0, 8.4))
        node.remove(AutotagLogic.MERGE_ANCHOR_TAG)
        assertEquals("untagged", AutotagLogic.checkAnchorCommand(snap, lookup)!!.kind)
        node.setDeleted(true)
        assertEquals("deleted", AutotagLogic.checkAnchorCommand(snap, lookup)!!.kind)
    }
}
