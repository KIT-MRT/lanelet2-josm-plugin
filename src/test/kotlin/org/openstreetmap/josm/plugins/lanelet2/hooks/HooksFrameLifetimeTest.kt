package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.event.DataSetListener
import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dHook
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/**
 * JOSM calls [Lanelet2Plugin.mapFrameInitialized] with `newFrame == null` when
 * the last layer closes, then again with a fresh frame when a file is opened.
 * Autotag / zoom-filter / routing-refresh and the 3D bridge must keep
 * working (Jython installs them once and never uninstalls on teardown).
 */
class HooksFrameLifetimeTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        AutotagHook.uninstall()
        AutotagHook.uninstallDeleteOverride()
        AutotagHook.uninstallAnchorProtection()
        ZoomFilterHook.uninstall()
        Viewer3dHook.uninstall()
        RoutingRefreshHook.resetForTests()
    }

    @AfterEach
    fun tearDown() {
        AutotagHook.uninstall()
        AutotagHook.uninstallDeleteOverride()
        AutotagHook.uninstallAnchorProtection()
        ZoomFilterHook.uninstall()
        Viewer3dHook.uninstall()
        RoutingRefreshHook.resetForTests()
    }

    @Test
    fun hooksSurviveMapFrameTeardownAndNewFrameWithoutDuplicates() {
        LaneletSettings.setAutotagEnabled(true)
        LaneletSettings.setZoomFilterEnabled(true)
        val ds = DataSet()
        AutotagHook.install()
        AutotagHook.attachTo(ds)
        ZoomFilterHook.install()
        // A port nothing listens on: the bridge only has to stay installed here,
        // and the default would dial a viewer the developer may have running.
        Viewer3dSettings.saveConfig(enabled = true, host = "127.0.0.1", ingestPort = 49999)
        Viewer3dHook.install()
        RoutingRefreshHook.setBackend { false }
        RoutingRefreshHook.install()
        LaneletSettings.setRoutingHookFullMap(true)
        LaneletSettings.setRoutingAutoDebounceMs(60_000)
        RoutingRefreshHook.requestUpdate()

        val dsListener = AutotagHook.dataSetListener()
        assertNotNull(dsListener)
        assertTrue(dataSetHasListener(ds, dsListener!!))
        assertEquals(ds, AutotagHook.attachedDataset())
        assertTrue(ZoomFilterHook.listenerInstalled())
        assertTrue(RoutingRefreshHook.timerIsLive())

        val dsCount = AutotagHookTest.dataSetListenerCount(ds)
        val zoomCount = ZoomFilterHookTest.zoomListenerCount()
        val layerCount = AutotagHookTest.activeLayerListenerCount()

        val frame1 = allocate(MapFrame::class.java)
        val frame2 = allocate(MapFrame::class.java)
        val plugin = allocate(Lanelet2Plugin::class.java)
        plugin.mapFrameInitialized(frame1, null)
        plugin.mapFrameInitialized(null, frame2)

        assertSame(dsListener, AutotagHook.dataSetListener())
        assertEquals(ds, AutotagHook.attachedDataset())
        assertTrue(dataSetHasListener(ds, dsListener), "autotag DataSetListener dropped after frame cycle")
        assertEquals(dsCount, AutotagHookTest.dataSetListenerCount(ds), "autotag DataSetListener duplicated or dropped")
        assertTrue(ZoomFilterHook.listenerInstalled(), "zoom ZoomChangeListener dropped after frame cycle")
        assertEquals(zoomCount, ZoomFilterHookTest.zoomListenerCount(), "zoom ZoomChangeListener duplicated or dropped")
        assertEquals(layerCount, AutotagHookTest.activeLayerListenerCount(), "autotag ActiveLayerChangeListener duplicated or dropped")
        assertTrue(RoutingRefreshHook.timerIsLive(), "routing timer died after map-frame teardown")
        assertTrue(RoutingRefreshHook.hasBackend())
        assertTrue(RoutingRefreshHook.isUpdatePending())
        assertTrue(Viewer3dHook.bridgeInstalled(), "3D bridge torn down by map-frame teardown")

        AutotagHook.install()
        AutotagHook.attachTo(ds)
        ZoomFilterHook.install()
        RoutingRefreshHook.install()
        assertEquals(dsCount, AutotagHookTest.dataSetListenerCount(ds), "re-install after new frame duplicated autotag")
        assertEquals(zoomCount, ZoomFilterHookTest.zoomListenerCount(), "re-install after new frame duplicated zoom")
        assertEquals(layerCount, AutotagHookTest.activeLayerListenerCount(), "re-install after new frame duplicated layer listener")
    }

    private fun dataSetHasListener(ds: DataSet, listener: DataSetListener): Boolean {
        val f = DataSet::class.java.getDeclaredField("listeners")
        f.isAccessible = true
        return (f.get(ds) as Collection<*>).contains(listener)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, type) as T
    }
}
