package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.BBox
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.data.osm.DataSelectionListener
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
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
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
    private val selectionListener = DataSelectionListener { scheduleSelection() }
    private var activeListener: ActiveLayerChangeListener? = null
    private var zoomListener: NavigatableComponent.ZoomChangeListener? = null
    private var attachedDs: DataSet? = null

    private var editTimer: Timer? = null
    private var viewportTimer: Timer? = null
    private var selectionTimer: Timer? = null
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
        selectionTimer?.stop()
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
        scheduleSelection()
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
            ds.addSelectionListener(selectionListener)
            attachedDs = ds
        } catch (_: Exception) {
            attachedDs = null
            return
        }
        scheduleEdit()
        scheduleViewport()
        scheduleSelection()
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
        try {
            ds?.removeSelectionListener(selectionListener)
        } catch (_: Exception) {
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

    private fun scheduleSelection() {
        val timer = selectionTimer ?: Timer(Viewer3dConstants.SELECTION_DEBOUNCE_MS) {
            try {
                sendSelection()
            } catch (e: Exception) {
                Logging.error(e)
            }
        }.also {
            it.isRepeats = false
            selectionTimer = it
        }
        timer.restart()
    }

    /** Mirror JOSM's selection to the viewer (it shows it while in edit mode). */
    private fun sendSelection() {
        if (socket?.connected != true) return
        val ds = attachedDs ?: return
        socket?.enqueue(selectionMessage(ds.selected))
    }

    private fun computeAndSend() {
        if (socket?.connected != true) {
            engine.forceSnapshot = true
            return
        }
        val ds = attachedDs ?: return
        val viewCenter = mapViewCenter()
        if (engine.forceSnapshot || engine.sent.isEmpty() || engine.anchor == null) {
            if (engine.anchor == null) engine.anchor = anchorOf(ds)
            val ways = candidateWayPrimitives(ds, viewCenter)
            engine.computeFull(ways.map { it.toSnapshot() }, viewCenter, laneletsOf(ways))
                ?.let { socket?.enqueue(it) }
            return
        }
        if (engine.dirtyAll) {
            val ways = candidateWayPrimitives(ds, viewCenter)
            engine.seedRescan(ways.map { it.toSnapshot() }, viewCenter, laneletsOf(ways))?.let { socket?.enqueue(it) }
        }
        // Only the dirty ways are read: one edit no longer copies the dataset
        // (~150 ms on the EDT for Karlsruhe's 144k ways).
        val result = engine.computeIncremental(
            { id -> ds.wayById(id) },
            viewCenter,
            { id -> (ds.getPrimitiveById(id, OsmPrimitiveType.RELATION) as? Relation)?.toLaneletSnapshot() },
        )
        result.patch?.let { socket?.enqueue(it) }
        if (result.morePending) scheduleEdit()
    }

    /**
     * Ways that can be streamed: with culling, those JOSM's spatial index
     * finds in the cull square (the engine still applies the exact test);
     * without, every way.
     */
    private fun candidateWayPrimitives(ds: DataSet, viewCenter: Pair<Double, Double>?): Collection<Way> {
        val a = engine.anchor
        if (!engine.cullEnabled || a == null) return ds.ways
        val bounds = Viewer3dFeatures.cullBoundsEnu(viewCenter, a, engine.cullRangeM) ?: return emptyList()
        val box = Viewer3dFeatures.latLonBoxOf(bounds, a)
        return ds.searchWays(BBox(box[0], box[1], box[2], box[3]))
    }

    /** Lanelets bounded by any of [ways] (all lanelets when [ways] is the whole dataset). */
    private fun laneletsOf(ways: Collection<Way>): List<LaneletSnapshot> {
        val out = LinkedHashMap<Long, LaneletSnapshot>()
        for (w in ways) {
            for (r in w.referrers) {
                if (r is Relation && r.isLanelet() && r.uniqueId !in out) {
                    r.toLaneletSnapshot()?.let { out[r.uniqueId] = it }
                }
            }
        }
        return out.values.toList()
    }

    /** Same anchor as [Viewer3dFeatures.computeAnchor] over all ways, without snapshotting them. */
    private fun anchorOf(ds: DataSet): Anchor? {
        var minLat = 1.0e9
        var maxLat = -1.0e9
        var minLon = 1.0e9
        var maxLon = -1.0e9
        var found = false
        for (w in ds.ways) {
            for (n in w.nodes) {
                val c = n.coor ?: continue
                found = true
                minLat = minOf(minLat, c.lat())
                maxLat = maxOf(maxLat, c.lat())
                minLon = minOf(minLon, c.lon())
                maxLon = maxOf(maxLon, c.lon())
            }
        }
        return if (found) Anchor((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0) else null
    }

    private fun sendViewport() {
        if (socket?.connected != true) return
        if (engine.cullEnabled) {
            val ds = attachedDs ?: return
            val ways = candidateWayPrimitives(ds, mapViewCenter())
            engine.syncCullVisibility(ways.map { it.toSnapshot() }, mapViewCenter(), laneletsOf(ways))
                ?.let { socket?.enqueue(it) }
        }
        engine.viewportPatch(mapViewBounds(), mapViewCenter(), engine.followView)?.let {
            socket?.enqueue(it)
        }
    }

    private fun onCommandLine(line: String) {
        val cmd = Viewer3dJson.parseCommand(line) ?: return
        // The raw edit layer, not requireVisibleEditLayer(): the reply has to
        // tell "no layer" from "layer hidden".
        val result = Viewer3dCommands.applyInbound(
            command = cmd,
            target = EditTarget.of(LaneletUtils.getEditLayer()),
            anchor = engine.anchor,
            driveView = Viewer3dSettings.driveViewEnabled(),
            setView = { op, anchor -> applySetView(op, anchor) },
        )
        result?.let { socket?.enqueue(it) }
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

        override fun relationMembersChanged(event: RelationMembersChangedEvent) {
            markEventDirty(event)
            scheduleEdit()
        }

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
                // A node moves its ways, a way reshapes its lanelets.
                fun markWay(w: Way) {
                    engine.markWayDirty(w.uniqueId)
                    for (r in w.referrers) if (r is Relation && r.isLanelet()) engine.markLaneletDirty(r.uniqueId)
                }
                for (p in prims) {
                    when (p) {
                        is Way -> markWay(p)
                        is Node -> for (r in p.referrers) if (r is Way) markWay(r)
                        is Relation -> if (p.isLanelet() || p.isDeleted) engine.markLaneletDirty(p.uniqueId)
                    }
                }
            } catch (_: Exception) {
                engine.markAllDirty()
            }
        }
    }
}

/**
 * JOSM selection as the viewer shows it: nodes, ways, and for a selected
 * relation (a lanelet, say) its member ways and nodes. Capped at
 * [Viewer3dConstants.MAX_SELECTION_SYNC] items.
 */
internal fun selectionMessage(selected: Collection<OsmPrimitive>): OutboundMessage.Selection {
    val nodes = LinkedHashSet<Long>()
    val ways = LinkedHashSet<String>()
    fun add(p: OsmPrimitive) {
        if (p.isDeleted) return
        when (p) {
            is Node -> nodes.add(p.uniqueId)
            is Way -> ways.add("way/${p.uniqueId}")
            else -> Unit
        }
    }
    for (p in selected) {
        if (p is Relation) p.memberPrimitives.forEach(::add) else add(p)
    }
    val cap = Viewer3dConstants.MAX_SELECTION_SYNC
    val truncated = nodes.size + ways.size > cap
    return OutboundMessage.Selection(
        nodeIds = nodes.take(cap),
        wayIds = ways.take((cap - minOf(nodes.size, cap)).coerceAtLeast(0)),
        truncated = truncated,
    )
}

internal fun Relation.isLanelet(): Boolean = get("type") == "lanelet"

/**
 * Bounds in driving direction via [Lanelet] (the port of lanelet2's
 * `geometry::align`, signed distance of each bound's middle to the other).
 */
internal fun Relation.toLaneletSnapshot(): LaneletSnapshot? {
    if (!isLanelet() && !isDeleted) return null
    val l = try {
        Lanelet(this)
    } catch (_: Exception) {
        return null
    }
    val left = l.leftBound()
    val right = l.rightBound()
    return LaneletSnapshot(
        uniqueId = uniqueId,
        deleted = isDeleted || !isLanelet(),
        leftWayId = left?.way?.uniqueId,
        rightWayId = right?.way?.uniqueId,
        leftReversed = left?.isReversed ?: false,
        rightReversed = right?.isReversed ?: false,
        left = left?.getNodes()?.map { it.toSnapshot() }.orEmpty(),
        right = right?.getNodes()?.map { it.toSnapshot() }.orEmpty(),
        subtype = get("subtype"),
        oneWay = get("one_way"),
    )
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
