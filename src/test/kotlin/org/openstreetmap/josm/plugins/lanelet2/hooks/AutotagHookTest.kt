package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent
import org.openstreetmap.josm.data.osm.event.DataChangedEvent
import org.openstreetmap.josm.data.osm.event.DataSetListener
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.MainLayerManager
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.util.concurrent.CopyOnWriteArrayList

class AutotagHookTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        AutotagHook.uninstall()
        AutotagHook.uninstallDeleteOverride()
        AutotagHook.uninstallAnchorProtection()
    }

    @AfterEach
    fun tearDown() {
        AutotagHook.uninstall()
        AutotagHook.uninstallDeleteOverride()
        AutotagHook.uninstallAnchorProtection()
    }

    @Test
    fun installUninstallIsIdempotentOnDataSetAndLayerManager() {
        val ds = DataSet()
        val dsBefore = dataSetListenerCount(ds)
        val layerBefore = activeLayerListenerCount()
        repeat(3) {
            AutotagHook.install()
            AutotagHook.attachTo(ds)
            assertEquals(dsBefore + 1, dataSetListenerCount(ds), "dataset listeners accumulated")
            assertEquals(layerBefore + 1, activeLayerListenerCount(), "active-layer listeners accumulated")
            assertSame(AutotagHook.dataSetListener(), AutotagHook.dataSetListener())
        }
        AutotagHook.uninstall()
        assertEquals(dsBefore, dataSetListenerCount(ds))
        assertEquals(layerBefore, activeLayerListenerCount())
        AutotagHook.install()
        AutotagHook.attachTo(ds)
        assertEquals(dsBefore + 1, dataSetListenerCount(ds))
        AutotagHook.uninstall()
        assertEquals(dsBefore, dataSetListenerCount(ds))
    }

    @Test
    fun deleteOverrideAndAnchorInstallAreIdempotent() {
        val undoBefore = preciseListenerCount()
        repeat(3) {
            AutotagHook.installDeleteOverride()
            AutotagHook.installAnchorProtection()
            assertTrue(AutotagDeleteOverride.listenerInstalled())
            assertEquals(undoBefore + 1, preciseListenerCount())
        }
        AutotagHook.uninstallDeleteOverride()
        AutotagHook.uninstallAnchorProtection()
        assertEquals(undoBefore, preciseListenerCount())
    }

    @Test
    fun flushAppliesTagsAsOneUndoableSequenceAndDoesNotRecurse() {
        LaneletSettings.setAutotagEnabled(true)
        AutotagHook.setTags(listOf("file_origin" to "/tmp/a.osm", "note" to "x"))
        val node = Node(LatLon(49.0, 8.4))
        val ds = OsmFixtures.dataSet(node)
        AutotagHook.attachTo(ds)
        val counts = CountingListener()
        ds.addDataSetListener(counts)
        AutotagHook.onPrimitivesAdded(listOf(node))
        assertEquals(1, AutotagHook.pendingSize())
        OsmFixtures.withUndo { undo ->
            AutotagHook.flushForTest(listOf(node), ds, AutotagHook.getTags())
            assertEquals(0, AutotagHook.pendingSize())
            assertEquals("/tmp/a.osm", node.get("file_origin"))
            assertEquals("x", node.get("note"))
            assertTrue(undo.lastCommand is SequenceCommand)
            assertEquals(1, undo.undoCommands.size)
            assertEquals(0, counts.primitivesAdded)
            assertTrue(counts.tagsChanged >= 1)
            AutotagHook.flushForTest(listOf(node), ds, AutotagHook.getTags())
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertNull(node.get("file_origin"))
        }
    }

    @Test
    fun tagsChangedDoesNotCollectPending() {
        val node = Node(LatLon(0.0, 0.0))
        val ds = OsmFixtures.dataSet(node)
        AutotagHook.attachTo(ds)
        node.put("file_origin", "/x")
        assertEquals(0, AutotagHook.pendingSize())
    }

    @Test
    fun enabledFlagIsOneOnly() {
        LaneletSettings.put(LaneletSettings.KEY_AUTOTAG_ENABLED, "true")
        assertEquals(false, AutotagHook.isEnabled())
        LaneletSettings.setAutotagEnabled(true)
        assertTrue(AutotagHook.isEnabled())
    }

    private class CountingListener : DataSetListener {
        var primitivesAdded = 0
        var tagsChanged = 0
        override fun primitivesAdded(event: PrimitivesAddedEvent) {
            primitivesAdded++
        }
        override fun tagsChanged(event: TagsChangedEvent) {
            tagsChanged++
        }
        override fun primitivesRemoved(event: PrimitivesRemovedEvent) = Unit
        override fun nodeMoved(event: NodeMovedEvent) = Unit
        override fun wayNodesChanged(event: WayNodesChangedEvent) = Unit
        override fun relationMembersChanged(event: RelationMembersChangedEvent) = Unit
        override fun otherDatasetChange(event: AbstractDatasetChangedEvent) = Unit
        override fun dataChanged(event: DataChangedEvent) = Unit
    }

    companion object {
        fun dataSetListenerCount(ds: DataSet): Int {
            val f = DataSet::class.java.getDeclaredField("listeners")
            f.isAccessible = true
            return (f.get(ds) as Collection<*>).size
        }

        fun activeLayerListenerCount(): Int {
            val lm = MainApplication.getLayerManager()
            val f = MainLayerManager::class.java.getDeclaredField("activeLayerChangeListeners")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (f.get(lm) as CopyOnWriteArrayList<*>).size
        }

        fun preciseListenerCount(): Int {
            val undo = org.openstreetmap.josm.data.UndoRedoHandler.getInstance()
            val f = undo.javaClass.getDeclaredField("preciseListenerCommands")
            f.isAccessible = true
            return (f.get(undo) as Collection<*>).size
        }
    }
}
