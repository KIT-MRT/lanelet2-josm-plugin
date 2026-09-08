package org.openstreetmap.josm.plugins.lanelet2.hooks

/**
 * Debounce / coalesce state machine from `routing_refresh_hook.py`.
 *
 * Timer and backend I/O stay outside: this only decides when to schedule,
 * execute, or fold a request into an in-flight run.
 */
class RoutingRefreshMachine(
    private val fullMap: () -> Boolean,
    private val debounceMs: () -> Int,
    private val schedule: (Int) -> Unit,
    private val stopSchedule: () -> Unit,
) {
    var running: Boolean = false
        private set
    var pendingRerun: Boolean = false
        private set
    var scheduled: Boolean = false
        private set

    fun hasPendingRerun(): Boolean = pendingRerun

    fun isUpdatePending(): Boolean = scheduled || running || pendingRerun

    fun requestUpdate(): RoutingRefreshEffect {
        if (running) {
            pendingRerun = true
            return RoutingRefreshEffect.CoalesceInFlight
        }
        if (!fullMap()) {
            scheduled = true
            schedule(SMALL_COALESCE_MS)
            return RoutingRefreshEffect.Schedule(SMALL_COALESCE_MS)
        }
        val ms = debounceMs()
        if (ms <= 0) {
            return executeUpdate()
        }
        scheduled = true
        schedule(ms)
        return RoutingRefreshEffect.Schedule(ms)
    }

    fun flush(): RoutingRefreshEffect {
        stopSchedule()
        scheduled = false
        return executeUpdate()
    }

    fun cancel() {
        stopSchedule()
        scheduled = false
        pendingRerun = false
    }

    fun onTimer(): RoutingRefreshEffect {
        scheduled = false
        return executeUpdate()
    }

    /**
     * Invoke [backend]. Returns whether a run was started. Mirrors Jython:
     * a second [executeUpdate] while [running] only sets [pendingRerun].
     */
    fun executeUpdate(): RoutingRefreshEffect {
        if (running) {
            pendingRerun = true
            return RoutingRefreshEffect.CoalesceInFlight
        }
        return RoutingRefreshEffect.Execute
    }

    fun markRunning() {
        running = true
    }

    fun backendFailed() {
        running = false
        pendingRerun = false
    }

    fun backendDidNotStart() {
        running = false
        pendingRerun = false
    }

    fun onFinished(): RoutingRefreshEffect {
        running = false
        if (!pendingRerun) return RoutingRefreshEffect.None
        pendingRerun = false
        if (!fullMap()) {
            scheduled = true
            schedule(SMALL_COALESCE_MS)
            return RoutingRefreshEffect.Schedule(SMALL_COALESCE_MS)
        }
        return executeUpdate()
    }

    fun reset() {
        stopSchedule()
        running = false
        pendingRerun = false
        scheduled = false
    }

    companion object {
        const val SMALL_COALESCE_MS = 300
    }
}

sealed class RoutingRefreshEffect {
    object None : RoutingRefreshEffect()
    object Execute : RoutingRefreshEffect()
    object CoalesceInFlight : RoutingRefreshEffect()
    data class Schedule(val delayMs: Int) : RoutingRefreshEffect()
}
