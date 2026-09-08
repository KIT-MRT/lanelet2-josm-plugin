package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.gui.NavigatableComponent
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.util.concurrent.CopyOnWriteArrayList

class ZoomFilterHookTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        ZoomFilterHook.uninstall()
    }

    @AfterEach
    fun tearDown() {
        ZoomFilterHook.uninstall()
    }

    @Test
    fun installUninstallDoesNotAccumulateZoomListeners() {
        val before = zoomListenerCount()
        repeat(3) {
            ZoomFilterHook.install()
            assertTrue(ZoomFilterHook.listenerInstalled())
            assertEquals(before + 1, zoomListenerCount(), "zoom listeners accumulated")
        }
        ZoomFilterHook.uninstall()
        assertFalse(ZoomFilterHook.listenerInstalled())
        assertEquals(before, zoomListenerCount())
        ZoomFilterHook.install()
        assertEquals(before + 1, zoomListenerCount())
        ZoomFilterHook.uninstall()
        assertEquals(before, zoomListenerCount())
    }

    @Test
    fun decideAndApplyIsStickyUntilThresholdCrossed() {
        LaneletSettings.setZoomFilterEnabled(true)
        assertTrue(ZoomFilterHook.decideAndApply(10.0, 17.0))
        assertEquals(true, ZoomFilterHook.lastActive())
        assertFalse(ZoomFilterHook.decideAndApply(16.0, 17.0))
        assertTrue(ZoomFilterHook.decideAndApply(18.0, 17.0))
        assertEquals(false, ZoomFilterHook.lastActive())
        assertFalse(ZoomFilterHook.decideAndApply(null, 17.0))
    }

    @Test
    fun decideAndApplyNoopsWhenDisabled() {
        LaneletSettings.setZoomFilterEnabled(false)
        assertFalse(ZoomFilterHook.decideAndApply(10.0, 17.0))
        assertNull(ZoomFilterHook.lastActive())
    }

    @Test
    fun enabledFlagIsOneOnly() {
        LaneletSettings.put(LaneletSettings.KEY_ZOOMFILTER_ENABLED, "yes")
        assertFalse(ZoomFilterHook.isEnabled())
        LaneletSettings.setZoomFilterEnabled(true)
        assertTrue(ZoomFilterHook.isEnabled())
    }

    companion object {
        fun zoomListenerCount(): Int {
            val f = NavigatableComponent::class.java.getDeclaredField("zoomChangeListeners")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return (f.get(null) as CopyOnWriteArrayList<*>).size
        }
    }
}
