package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.data.osm.Filter
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.NavigatableComponent
import org.openstreetmap.josm.gui.dialogs.FilterDialog
import org.openstreetmap.josm.gui.dialogs.FilterTableModel
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.tools.Logging

/**
 * Zoom-change listener from `zoom_filter_hook.py`. Pushes managed [Filter]
 * objects into the live Filter panel and flips their enabled bit with zoom.
 */
object ZoomFilterHook {
    private var zoomListener: NavigatableComponent.ZoomChangeListener? = null
    private val managed = ArrayList<Filter>()
    private var active: Boolean? = null

    fun isEnabled(): Boolean = LaneletSettings.isZoomFilterEnabled()

    fun getThreshold(): Double = LaneletSettings.getZoomFilterThreshold()

    fun getFiltersConfig(): List<ZoomFilterLogic.ZoomFilterSpec> =
        ZoomFilterLogic.parseFiltersJson(LaneletSettings.getZoomFilterFiltersRaw())

    fun saveConfig(enabled: Boolean, threshold: Double, filters: List<ZoomFilterLogic.ZoomFilterSpec>) {
        LaneletSettings.setZoomFilterEnabled(enabled)
        try {
            LaneletSettings.setZoomFilterThreshold(threshold)
        } catch (_: Exception) {
            LaneletSettings.setZoomFilterThreshold(ZoomFilterLogic.DEFAULT_THRESHOLD)
        }
        LaneletSettings.setZoomFilterFiltersRaw(ZoomFilterLogic.encodeFiltersJson(filters))
    }

    fun installIfEnabled() {
        if (isEnabled()) install()
    }

    fun reinstallFromSettings() {
        uninstall()
        if (isEnabled()) install()
    }

    fun install() {
        if (zoomListener == null) {
            val listener = NavigatableComponent.ZoomChangeListener {
                try {
                    onZoomChanged()
                } catch (_: Exception) {
                }
            }
            zoomListener = listener
            try {
                NavigatableComponent.addZoomChangeListener(listener)
            } catch (e: Exception) {
                Logging.debug("lanelet2: zoom filter listener: {0}", e.message)
            }
        }
        removeManaged()
        managed.clear()
        managed.addAll(buildManagedFilters())
        active = null
        ensureFiltersPresent()
        onZoomChanged()
    }

    fun uninstall() {
        zoomListener?.let {
            try {
                NavigatableComponent.removeZoomChangeListener(it)
            } catch (_: Exception) {
            }
        }
        zoomListener = null
        removeManaged()
        active = null
    }

    fun listenerInstalled(): Boolean = zoomListener != null

    fun lastActive(): Boolean? = active

    fun managedCount(): Int = managed.size

    fun currentZoomLevel(): Double? {
        return try {
            val mapframe = MainApplication.getMap() ?: return null
            val mv = mapframe.mapView ?: return null
            val dist100 = mv.dist100Pixel
            if (dist100 <= 0.0) return null
            val lat = mv.projection.eastNorth2latlon(mv.center).lat()
            ZoomFilterLogic.zoomFromDist100(dist100, lat)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Testable zoom-state update. Returns whether the applied state changed.
     */
    fun decideAndApply(zoom: Double?, threshold: Double = getThreshold()): Boolean {
        if (!isEnabled()) return false
        if (zoom == null) return false
        val next = ZoomFilterLogic.shouldFiltersBeActive(zoom, threshold)
        if (next == active) return false
        active = next
        applyActive(next)
        return true
    }

    internal fun onZoomChanged() {
        decideAndApply(currentZoomLevel(), getThreshold())
    }

    private fun buildManagedFilters(): List<Filter> {
        val out = ArrayList<Filter>()
        for (entry in ZoomFilterLogic.managedSpecs(getFiltersConfig())) {
            val f = Filter()
            f.text = entry.text
            f.enable = false
            f.hiding = entry.hiding
            f.inverted = entry.inverted
            out.add(f)
        }
        return out
    }

    private fun filterModel(): FilterTableModel? {
        return try {
            val mapframe = MainApplication.getMap() ?: return null
            val fd = mapframe.getToggleDialog(FilterDialog::class.java) ?: return null
            fd.filterModel
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureFiltersPresent() {
        val ftm = filterModel() ?: return
        val existing = ftm.filters
        for (mf in managed) {
            if (existing.indexOf(mf) < 0) {
                ftm.addFilter(mf)
            }
        }
    }

    private fun removeManaged() {
        val ftm = filterModel()
        if (ftm != null && managed.isNotEmpty()) {
            try {
                val filters = ftm.filters
                val rows = ArrayList<Int>()
                for (mf in managed) {
                    val idx = filters.indexOf(mf)
                    if (idx >= 0) rows.add(idx)
                }
                if (rows.isNotEmpty()) {
                    ftm.removeFilters(*rows.sorted().toIntArray())
                    ftm.executeFilters(true)
                }
            } catch (_: Exception) {
            }
        }
        managed.clear()
    }

    private fun applyActive(next: Boolean) {
        val ftm = filterModel() ?: return
        ensureFiltersPresent()
        val filters = ftm.filters
        var touched = false
        for (mf in managed) {
            val idx = filters.indexOf(mf)
            if (idx >= 0) {
                ftm.setValueAt(java.lang.Boolean.valueOf(next), idx, FilterTableModel.COL_ENABLED)
                touched = true
            }
        }
        if (touched) {
            try {
                ftm.executeFilters(true)
            } catch (_: Exception) {
            }
        }
    }
}
