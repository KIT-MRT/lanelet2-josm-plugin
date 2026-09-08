package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingRefreshMachineTest {

    private fun machine(
        fullMap: Boolean,
        debounceMs: Int = 4000,
        scheduled: MutableList<Int> = mutableListOf(),
    ): RoutingRefreshMachine {
        return RoutingRefreshMachine(
            fullMap = { fullMap },
            debounceMs = { debounceMs },
            schedule = { scheduled.add(it) },
            stopSchedule = {},
        )
    }

    @Test
    fun smallPathCoalescesAt300Ms() {
        val scheduled = mutableListOf<Int>()
        val m = machine(fullMap = false, scheduled = scheduled)
        assertEquals(
            RoutingRefreshEffect.Schedule(RoutingRefreshMachine.SMALL_COALESCE_MS),
            m.requestUpdate(),
        )
        assertEquals(listOf(300), scheduled)
        assertTrue(m.scheduled)
        assertEquals(RoutingRefreshEffect.Execute, m.onTimer())
        assertFalse(m.scheduled)
    }

    @Test
    fun fullMapUsesDebounceAndZeroRunsImmediately() {
        val scheduled = mutableListOf<Int>()
        val delayed = machine(fullMap = true, debounceMs = 4000, scheduled = scheduled)
        assertEquals(RoutingRefreshEffect.Schedule(4000), delayed.requestUpdate())
        assertEquals(listOf(4000), scheduled)
        val immediate = machine(fullMap = true, debounceMs = 0)
        assertEquals(RoutingRefreshEffect.Execute, immediate.requestUpdate())
    }

    @Test
    fun inFlightRequestSetsPendingRerunAndDoesNotStartSecondBackend() {
        val m = machine(fullMap = false)
        assertEquals(RoutingRefreshEffect.Execute, m.onTimer())
        m.markRunning()
        assertEquals(RoutingRefreshEffect.CoalesceInFlight, m.requestUpdate())
        assertTrue(m.hasPendingRerun())
        val again = m.executeUpdate()
        assertEquals(RoutingRefreshEffect.CoalesceInFlight, again)
    }

    @Test
    fun onFinishedSmallPathDefersPendingBy300Ms() {
        val scheduled = mutableListOf<Int>()
        val m = machine(fullMap = false, scheduled = scheduled)
        m.markRunning()
        m.requestUpdate()
        assertTrue(m.hasPendingRerun())
        val effect = m.onFinished()
        assertEquals(RoutingRefreshEffect.Schedule(300), effect)
        assertFalse(m.hasPendingRerun())
        assertTrue(m.scheduled)
        assertEquals(listOf(300), scheduled)
    }

    @Test
    fun onFinishedFullMapRerunsImmediately() {
        val m = machine(fullMap = true, debounceMs = 4000)
        m.markRunning()
        m.requestUpdate()
        assertEquals(RoutingRefreshEffect.Execute, m.onFinished())
        assertFalse(m.running)
    }

    @Test
    fun flushStopsTimerAndExecutes() {
        val m = machine(fullMap = true, debounceMs = 4000)
        m.requestUpdate()
        assertEquals(RoutingRefreshEffect.Execute, m.flush())
        assertFalse(m.scheduled)
    }

    @Test
    fun cancelDropsPendingWithoutExecuting() {
        val m = machine(fullMap = false)
        m.requestUpdate()
        m.markRunning()
        m.requestUpdate()
        m.cancel()
        assertFalse(m.scheduled)
        assertFalse(m.hasPendingRerun())
        assertTrue(m.running)
    }

    @Test
    fun backendDidNotStartClearsRunningAndPending() {
        val m = machine(fullMap = true, debounceMs = 0)
        m.markRunning()
        m.requestUpdate()
        m.backendDidNotStart()
        assertFalse(m.running)
        assertFalse(m.hasPendingRerun())
    }
}
