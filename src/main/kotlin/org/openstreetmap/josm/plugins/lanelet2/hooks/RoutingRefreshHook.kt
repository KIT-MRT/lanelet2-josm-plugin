package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.dependent.DebugRoutingGraph
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.tools.Logging
import javax.swing.Timer

/**
 * Pluggable debounced routing-graph refresh from `routing_refresh_hook.py`.
 *
 * Reads [LaneletSettings.getRoutingHookFullMap] and
 * [LaneletSettings.getRoutingAutoDebounceMs] on every request. Default
 * backend is [DebugRoutingGraph.runUpdateAuto].
 */
object RoutingRefreshHook {
    private var backend: ((() -> Unit) -> Boolean)? = null
    private var timer: Timer? = null

    private val machine = RoutingRefreshMachine(
        fullMap = { LaneletSettings.getRoutingHookFullMap() },
        debounceMs = { LaneletSettings.getRoutingAutoDebounceMs() },
        schedule = { ms -> restartTimer(ms) },
        stopSchedule = { stopTimer() },
    )

    fun setBackend(fn: ((() -> Unit) -> Boolean)?) {
        backend = fn
    }

    fun hasBackend(): Boolean = backend != null

    fun hasPendingRerun(): Boolean = machine.hasPendingRerun()

    fun isUpdatePending(): Boolean = machine.isUpdatePending()

    fun prepareForEditLayerPaint() = Unit

    /**
     * Launcher-symmetric install. Registers the debug-routing backend if none
     * is set. The Jython `install()` was a no-op because the dependent
     * launcher called `set_backend` separately.
     */
    fun install() {
        if (backend == null) {
            setBackend { onFinished -> DebugRoutingGraph.runUpdateAuto(onFinished) }
        }
    }

    fun requestUpdate() {
        if (backend == null) return
        apply(machine.requestUpdate())
    }

    fun flush() {
        if (backend == null) return
        apply(machine.flush())
    }

    fun cancel() {
        machine.cancel()
    }

    /** True while a Swing debounce timer exists and is running. */
    fun timerIsLive(): Boolean = timer?.isRunning == true

    fun resetForTests() {
        machine.reset()
        timer?.stop()
        timer = null
        backend = null
    }

    internal fun machineForTest(): RoutingRefreshMachine = machine

    private fun apply(effect: RoutingRefreshEffect) {
        when (effect) {
            RoutingRefreshEffect.None,
            RoutingRefreshEffect.CoalesceInFlight,
            is RoutingRefreshEffect.Schedule,
            -> Unit
            RoutingRefreshEffect.Execute -> runBackend()
        }
    }

    private fun runBackend() {
        val fn = backend ?: return
        val nested = machine.executeUpdate()
        if (nested is RoutingRefreshEffect.CoalesceInFlight) return
        machine.markRunning()
        val started = try {
            fn { apply(machine.onFinished()) }
        } catch (e: Exception) {
            machine.backendFailed()
            Logging.debug("lanelet2: routing refresh backend: {0}", e.message)
            return
        }
        if (!started) {
            machine.backendDidNotStart()
        }
    }

    private fun stopTimer() {
        try {
            timer?.stop()
        } catch (_: Exception) {
        }
    }

    private fun restartTimer(debounceMs: Int) {
        val t = timer
        if (t == null) {
            val created = Timer(debounceMs) {
                apply(machine.onTimer())
            }
            created.isRepeats = false
            timer = created
            created.restart()
        } else {
            t.initialDelay = debounceMs
            t.delay = debounceMs
            t.restart()
        }
    }
}
