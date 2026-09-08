package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
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
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.tools.Logging
import java.util.ArrayList
import javax.swing.Timer

/**
 * Autotag engine from `autotag_hook.py`.
 *
 * Re-entrancy: the Jython never mutates inside `primitivesAdded`. New primitives
 * are collected and tagged after a 400 ms debounce via Commands. `tagsChanged`
 * is a no-op, so the ChangePropertyCommand cannot recurse into another collect.
 */
object AutotagHook {
    private var dsListener: DataSetListener? = null
    private var activeListener: ActiveLayerChangeListener? = null
    private var attachedDs: DataSet? = null
    private var timer: Timer? = null
    private val pending = ArrayList<OsmPrimitive>()

    fun isEnabled(): Boolean = LaneletSettings.isAutotagEnabled()

    fun getTags(): List<Pair<String, String>> =
        AutotagLogic.deserializeTags(LaneletSettings.getAutotagTagsRaw())

    fun setTags(pairs: List<Pair<String, String>>) {
        LaneletSettings.setAutotagTagsRaw(AutotagLogic.serializeTags(pairs))
        AutotagHud.refresh()
    }

    fun setEnabled(enabled: Boolean) {
        LaneletSettings.setAutotagEnabled(enabled)
        if (enabled) install() else uninstall()
    }

    fun getFileOrigin(): String? = AutotagLogic.fileOriginOf(getTags())

    fun setFileOrigin(path: String?) {
        setTags(AutotagLogic.replaceFileOrigin(getTags(), path))
    }

    fun installIfEnabled() {
        if (isEnabled()) install()
    }

    fun install() {
        if (activeListener == null) {
            activeListener = ActiveLayerChangeListener { attachToCurrent() }
            try {
                MainApplication.getLayerManager().addActiveLayerChangeListener(activeListener)
            } catch (e: Exception) {
                Logging.debug("lanelet2: autotag active-layer listener: {0}", e.message)
            }
        }
        attachToCurrent()
        AutotagHud.installListener()
        AutotagHud.show()
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
        timer?.stop()
        pending.clear()
        AutotagHud.hide()
        AutotagHud.uninstallListener()
    }

    fun installDeleteOverride() = AutotagDeleteOverride.install()

    fun installAnchorProtection() = AutotagAnchorGuard.install()

    fun uninstallDeleteOverride() = AutotagDeleteOverride.uninstall()

    fun uninstallAnchorProtection() = AutotagAnchorGuard.uninstall()

    internal fun attachToCurrent() {
        val ds = currentDataset()
        attachTo(ds)
    }

    /** Visible for tests: attach the (single) dataset listener to [ds]. */
    fun attachTo(ds: DataSet?) {
        if (ds === attachedDs) return
        detachDs()
        if (ds == null) return
        if (dsListener == null) {
            dsListener = AutotagDataSetListener()
        }
        try {
            ds.addDataSetListener(dsListener)
            attachedDs = ds
        } catch (_: Exception) {
            attachedDs = null
        }
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

    private fun currentDataset(): DataSet? =
        try {
            requireVisibleEditLayer()?.data
        } catch (_: Exception) {
            null
        }

    internal fun onPrimitivesAdded(primitives: Collection<OsmPrimitive?>) {
        AutotagLogic.collectNewPrimitives(primitives, pending)
        if (pending.isEmpty()) return
        restartTimer()
    }

    private fun restartTimer() {
        val t = timer ?: Timer(AutotagLogic.DEBOUNCE_MS) {
            try {
                flush()
            } catch (e: Exception) {
                Logging.debug("lanelet2: autotag flush: {0}", e.message)
            }
        }.also {
            it.isRepeats = false
            timer = it
        }
        t.restart()
    }

    /** Apply pending tags now (timer path and tests). */
    fun flush() {
        val batch = ArrayList(pending)
        pending.clear()
        if (batch.isEmpty()) return
        if (!isEnabled()) return
        val tags = getTags()
        if (tags.isEmpty()) return
        val layer = try {
            requireVisibleEditLayer()
        } catch (_: Exception) {
            null
        }
        if (layer == null) return
        val ds = layer.data
        applyTags(batch, ds, tags)
        try {
            layer.invalidate()
        } catch (_: Exception) {
        }
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    /**
     * Test helper: flush [batch] against [ds] without requiring a visible
     * edit layer. Still uses Commands so undo works.
     */
    fun flushForTest(batch: List<OsmPrimitive>, ds: DataSet, tags: List<Pair<String, String>>) {
        pending.clear()
        applyTags(batch, ds, tags)
    }

    private fun applyTags(
        batch: List<OsmPrimitive>,
        ds: DataSet,
        tags: List<Pair<String, String>>,
    ) {
        val commands = ArrayList<Command>()
        for ((key, value) in tags) {
            val targets = AutotagLogic.targetsForTag(batch, ds, key, value)
            if (targets.isNotEmpty()) {
                commands.add(ChangePropertyCommand(targets, key, value))
            }
        }
        if (commands.isEmpty()) return
        try {
            LaneletUtils.getUndo().add(SequenceCommand(AutotagLogic.SEQUENCE_TITLE, commands))
        } catch (e: Exception) {
            Logging.debug("lanelet2: autotag command: {0}", e.message)
        }
    }

    fun pendingSize(): Int = pending.size

    fun attachedDataset(): DataSet? = attachedDs

    fun dataSetListener(): DataSetListener? = dsListener

    fun activeListenerInstalled(): Boolean = activeListener != null

    internal fun existingFileOrigins(): List<String> {
        val values = HashSet<String>()
        try {
            val layer = LaneletUtils.getEditLayer() ?: return emptyList()
            for (prim in layer.data.allPrimitives()) {
                val v = prim.get(AutotagLogic.FILE_ORIGIN_TAG)
                if (!v.isNullOrEmpty()) values.add(v)
            }
        } catch (_: Exception) {
        }
        return values.sorted()
    }

    internal fun uniqueFileOriginsInSelection(): List<String> {
        return try {
            val layer = LaneletUtils.getEditLayer() ?: return emptyList()
            AutotagLogic.uniqueFileOrigins(layer.data.selected.map { prim ->
                try {
                    prim.get(AutotagLogic.FILE_ORIGIN_TAG)
                } catch (_: Exception) {
                    null
                }
            })
        } catch (_: Exception) {
            emptyList()
        }
    }

    private class AutotagDataSetListener : DataSetListener {
        override fun primitivesAdded(event: PrimitivesAddedEvent) {
            try {
                onPrimitivesAdded(event.primitives)
            } catch (_: Exception) {
            }
        }

        override fun primitivesRemoved(event: PrimitivesRemovedEvent) = Unit
        override fun tagsChanged(event: TagsChangedEvent) = Unit
        override fun nodeMoved(event: NodeMovedEvent) = Unit
        override fun wayNodesChanged(event: WayNodesChangedEvent) = Unit
        override fun relationMembersChanged(event: RelationMembersChangedEvent) = Unit
        override fun otherDatasetChange(event: AbstractDatasetChangedEvent) = Unit
        override fun dataChanged(event: DataChangedEvent) = Unit
    }
}
