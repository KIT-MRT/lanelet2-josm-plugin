package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class RoutingRefreshHookTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        RoutingRefreshHook.resetForTests()
    }

    @AfterEach
    fun tearDown() {
        RoutingRefreshHook.resetForTests()
    }

    @Test
    fun requestWithoutBackendIsNoop() {
        RoutingRefreshHook.requestUpdate()
        RoutingRefreshHook.flush()
        assertFalse(RoutingRefreshHook.hasBackend())
        assertFalse(RoutingRefreshHook.isUpdatePending())
    }

    @Test
    fun installRegistersDefaultBackend() {
        RoutingRefreshHook.install()
        assertTrue(RoutingRefreshHook.hasBackend())
        RoutingRefreshHook.install()
        assertTrue(RoutingRefreshHook.hasBackend())
    }

    @Test
    fun debounceAndFullMapSettingsDriveTheMachine() {
        var starts = 0
        var finish: (() -> Unit)? = null
        RoutingRefreshHook.setBackend { onFinished ->
            starts++
            finish = onFinished
            true
        }
        LaneletSettings.setRoutingHookFullMap(true)
        LaneletSettings.setRoutingAutoDebounceMs(0)
        RoutingRefreshHook.requestUpdate()
        assertEquals(1, starts)
        assertTrue(RoutingRefreshHook.isUpdatePending())

        LaneletSettings.setRoutingHookFullMap(false)
        RoutingRefreshHook.requestUpdate()
        assertTrue(RoutingRefreshHook.hasPendingRerun())
        assertEquals(1, starts)

        finish!!()
        assertFalse(RoutingRefreshHook.hasPendingRerun())
        assertEquals(1, starts)
    }

    @Test
    fun reentrancyWhileRunningDoesNotStartSecondBackend() {
        var starts = 0
        RoutingRefreshHook.setBackend { _ ->
            starts++
            true
        }
        LaneletSettings.setRoutingHookFullMap(true)
        LaneletSettings.setRoutingAutoDebounceMs(0)
        RoutingRefreshHook.requestUpdate()
        RoutingRefreshHook.requestUpdate()
        RoutingRefreshHook.flush()
        assertEquals(1, starts)
        assertTrue(RoutingRefreshHook.hasPendingRerun())
    }

    @Test
    fun failedBackendClearsRunning() {
        RoutingRefreshHook.setBackend { error("boom") }
        LaneletSettings.setRoutingHookFullMap(true)
        LaneletSettings.setRoutingAutoDebounceMs(0)
        RoutingRefreshHook.requestUpdate()
        assertFalse(RoutingRefreshHook.isUpdatePending())
        assertFalse(RoutingRefreshHook.hasPendingRerun())
    }
}
