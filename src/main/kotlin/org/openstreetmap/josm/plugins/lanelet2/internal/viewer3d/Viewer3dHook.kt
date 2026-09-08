package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Way
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
import org.openstreetmap.josm.gui.NavigatableComponent
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.tools.Logging
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * JOSM-side bridge for the live 3D viewer. All DataSet / MapView reads happen
 * on the EDT; JSON serialization and socket writes run on [Viewer3dSocketClient]'s
 * sender thread.
 */
object Viewer3dHook {
    private val engine = Viewer3dDiffEngine()
    private val server = Viewer3dServerProcess()
    private var socket: Viewer3dSocketClient? = null

    private var dsListener: DataSetListener? = null
    private var activeListener: ActiveLayerChangeListener? = null
    private var zoomListener: NavigatableComponent.ZoomChangeListener? = null
    private var attachedDs: DataSet? = null

    private var editTimer: Timer? = null
    private var viewportTimer: Timer? = null
    @Volatile private var viewFrom3d = false

    fun serverProcess(): Viewer3dServerProcess = server

    /** Test seam: whether the JOSM-side half of the bridge is live. */
    internal fun bridgeInstalled(): Boolean = activeListener != null

    fun installIfEnabled() {
        refreshSettings()
        if (Viewer3dSettings.isEnabled()) install() else uninstall()
    }

    fun reinstallFromSettings() {
        uninstall()
        refreshSettings()
        if (Viewer3dSettings.isEnabled()) install()
    }

    fun install() {
        ensureSocket()
        if (activeListener == null) {
            activeListener = ActiveLayerChangeListener { attachToCurrent(resetAnchor = true) }
            try {
                MainApplication.getLayerManager().addActiveLayerChangeListener(activeListener)
            } catch (e: Exception) {
                Logging.error(e)
            }
        }
        if (zoomListener == null) {
            zoomListener = NavigatableComponent.ZoomChangeListener {
                if (viewFrom3d) return@ZoomChangeListener
                scheduleViewport()
            }
            try {
                NavigatableComponent.addZoomChangeListener(zoomListener)
            } catch (e: Exception) {
                Logging.error(e)
            }
        }
        attachToCurrent(resetAnchor = true)
    }

    fun uninstall() {
        detachDs()
        activeListener?.let {
            try {
                MainApplication.getLayerManager().removeActiveLayerChangeListener(it)
            } catch (_: Exception) {
            }
        }
        activeListener = null
        zoomListener?.let {
            try {
                NavigatableComponent.removeZoomChangeListener(it)
            } catch (_: Exception) {
            }
        }
        zoomListener = null
        editTimer?.stop()
        viewportTimer?.stop()
        socket?.stop()
        socket = null
        engine.resetForLayerChange()
    }

    fun shutdown() {
        uninstall()
        server.shutdown()
    }

    private fun refreshSettings() {
        engine.cullEnabled = Viewer3dSettings.cullEnabled()
        engine.cullRangeM = Viewer3dSettings.getCullRangeM().toDouble()
        engine.followView = Viewer3dSettings.followViewEnabled()
    }

    private fun ensureSocket() {
        if (socket != null) return
        socket = Viewer3dSocketClient(
            host = { Viewer3dSettings.getHost() },
            port = { Viewer3dSettings.getIngestPort() },
            onCommand = { line -> SwingUtilities.invokeLater { onCommandLine(line) } },
            onConnected = { SwingUtilities.invokeLater { requestResync() } },
        ).also { it.start() }
    }

    private fun requestResync() {
        socket?.requestResync()
        engine.forceSnapshot = true
        scheduleEdit()
        scheduleViewport()
    }

    private fun attachToCurrent(resetAnchor: Boolean) {
        if (resetAnchor) engine.resetForLayerChange()
        val ds = currentDataSet()
        if (ds === attachedDs) {
            if (ds != null) {
                scheduleEdit()
                scheduleViewport()
            }
            return
        }
        detachDs()
        if (ds == null) return
        if (dsListener == null) {
            dsListener = Viewer3dDataSetListener()
        }
        try {
            ds.addDataSetListener(dsListener)
            attachedDs = ds
        } catch (_: Exception) {
            attachedDs = null
            return
        }
        scheduleEdit()
        scheduleViewport()
    }

    private fun detachDs() {
        val ds = attachedDs
        val listener = dsListener
        if (ds != null && listener != null) {
            try {
                ds.removeDataSetListener(listener)
            } catch (_: Exception) {
            }
        }
        attachedDs = null
    }

    /**
     * Streaming follows the edit layer even while it is hidden, as the Jython
     * hook does. Only inbound edits from the browser require a visible layer.
     */
    private fun currentDataSet(): DataSet? =
        try {
            LaneletUtils.getEditLayer()?.data
        } catch (_: Exception) {
            null
        }

    private fun scheduleEdit() {
        val timer = editTimer ?: Timer(Viewer3dConstants.DEBOUNCE_MS) {
            try {
                computeAndSend()
            } catch (e: Exception) {
                Logging.error(e)
            }
        }.also {
            it.isRepeats = false
            editTimer = it
        }
        timer.restart()
    }

    private fun scheduleViewport() {
        val timer = viewportTimer ?: Timer(Viewer3dConstants.VIEWPORT_DEBOUNCE_MS) {
            try {
                sendViewport()
            } catch (e: Exception) {
                Logging.error(e)
            }
        }.also {
            it.isRepeats = false
            viewportTimer = it
        }
        timer.restart()
    }

    private fun computeAndSend() {
        if (socket?.connected != true) {
            engine.forceSnapshot = true
            return
        }
        val ds = attachedDs ?: return
        val ways = ds.ways.map { it.toSnapshot() }
        val viewCenter = mapViewCenter()
        if (engine.forceSnapshot || engine.sent.isEmpty() || engine.anchor == null) {
            engine.computeFull(ways, viewCenter)?.let { socket?.enqueue(it) }
            return
        }
        if (engine.dirtyAll) {
            engine.seedRescan(ways, viewCenter)?.let { socket?.enqueue(it) }
        }
        val waysById = ways.associateBy { it.uniqueId }
        val result = engine.computeIncremental(waysById, viewCenter)
        result.patch?.let { socket?.enqueue(it) }
        if (result.morePending) scheduleEdit()
    }

    private fun sendViewport() {
        if (socket?.connected != true) return
        if (engine.cullEnabled) {
            val ds = attachedDs ?: return
            val ways = ds.ways.map { it.toSnapshot() }
            engine.syncCullVisibility(ways, mapViewCenter())?.let { socket?.enqueue(it) }
        }
        engine.viewportPatch(mapViewBounds(), mapViewCenter(), engine.followView)?.let {
            socket?.enqueue(it)
        }
    }

    private fun onCommandLine(line: String) {
        val cmd = Viewer3dJson.parseCommand(line) ?: return
        Viewer3dCommands.applyInbound(
            command = cmd,
            layer = requireVisibleEditLayer(),
            anchor = engine.anchor,
            driveView = Viewer3dSettings.driveViewEnabled(),
            setView = { op, anchor -> applySetView(op, anchor) },
        )
    }

    private fun applySetView(op: InboundOp.SetView, anchor: Anchor) {
        if (!Viewer3dSettings.driveViewEnabled()) return
        val before = mapViewCenter()
        if (!op.force && before != null) {
            val (cx, cy) = Viewer3dEnu.enu(before.first, before.second, anchor.lat, anchor.lon)
            val dx = op.x - cx
            val dy = op.y - cy
            if (dx * dx + dy * dy < 1.0) return
        }
        viewFrom3d = true
        try {
            val (lat, lon) = Viewer3dEnu.enuToLatLon(op.x, op.y, anchor.lat, anchor.lon)
            val map = MainApplication.getMap() ?: return
            val mv = map.mapView ?: return
            val en = mv.projection.latlon2eastNorth(LatLon(lat, lon))
            mv.zoomTo(en, mv.scale)
            mv.repaint()
            sendViewport()
        } catch (e: Exception) {
            Logging.error(e)
        } finally {
            viewFrom3d = false
        }
    }

    private fun mapViewCenter(): Pair<Double, Double>? = try {
        val mv = MainApplication.getMap()?.mapView ?: return null
        val b = mv.realBounds ?: return null
        ((b.minLat + b.maxLat) / 2.0) to ((b.minLon + b.maxLon) / 2.0)
    } catch (_: Exception) {
        null
    }

    private fun mapViewBounds(): ViewBounds? = try {
        val mv = MainApplication.getMap()?.mapView ?: return null
        val b = mv.realBounds ?: return null
        ViewBounds(b.minLat, b.maxLat, b.minLon, b.maxLon)
    } catch (_: Exception) {
        null
    }

    private class Viewer3dDataSetListener : DataSetListener {
        override fun primitivesAdded(event: PrimitivesAddedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

        override fun primitivesRemoved(event: PrimitivesRemovedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

        override fun tagsChanged(event: TagsChangedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

        override fun nodeMoved(event: NodeMovedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

        override fun wayNodesChanged(event: WayNodesChangedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

        override fun relationMembersChanged(event: RelationMembersChangedEvent) = Unit

        override fun otherDatasetChange(event: AbstractDatasetChangedEvent) {
            engine.markAllDirty()
            scheduleEdit()
        }

        override fun dataChanged(event: DataChangedEvent) {
            val sub = try {
                event.events
            } catch (_: Exception) {
                null
            }
            if (sub.isNullOrEmpty()) {
                engine.markAllDirty()
            } else {
                for (e in sub) markEventDirty(e)
            }
            scheduleEdit()
        }

        private fun markEventDirty(event: AbstractDatasetChangedEvent) {
            try {
                val prims = event.primitives ?: return
                for (p in prims) {
                    when (p) {
                        is Way -> engine.markWayDirty(p.uniqueId)
                        is Node -> {
                            for (r in p.referrers) {
                                if (r is Way) engine.markWayDirty(r.uniqueId)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                engine.markAllDirty()
            }
        }
    }
}

internal fun Way.toSnapshot(): WaySnapshot = WaySnapshot(
    uniqueId = uniqueId,
    deleted = isDeleted,
    nodes = nodes.map { it.toSnapshot() },
    type = get("type"),
    subtype = get("subtype"),
    participantBicycle = get("participant:bicycle"),
)

internal fun Node.toSnapshot(): NodeSnapshot = NodeSnapshot(
    uniqueId = uniqueId,
    lat = coor?.lat(),
    lon = coor?.lon(),
    eleTag = get("ele"),
)

internal fun DataSet.waysSnapshot(): List<WaySnapshot> = ways.map { it.toSnapshot() }

internal fun DataSet.wayById(id: Long): WaySnapshot? =
    try {
        (getPrimitiveById(id, OsmPrimitiveType.WAY) as? Way)?.toSnapshot()
    } catch (_: Exception) {
        null
    }
