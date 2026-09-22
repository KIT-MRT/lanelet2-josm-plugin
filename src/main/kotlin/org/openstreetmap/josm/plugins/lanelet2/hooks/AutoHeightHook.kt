package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
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
import org.openstreetmap.josm.gui.Notification
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener
import org.openstreetmap.josm.gui.util.GuiHelper
import org.openstreetmap.josm.plugins.lanelet2.infra.HeightTools
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.tools.Logging
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import javax.swing.Timer

/**
 * New nodes take the height (`ele`) of the nearest existing node that has one,
 * so a way drawn in JOSM does not start at 0 m in an absolute-height map. On by
 * default (`autoheight.enabled`).
 *
 * Same shape as [AutotagHook]: nodes are only collected in `primitivesAdded`
 * and tagged after a short debounce through one SequenceCommand (its own undo
 * step), so the hook never mutates inside a dataset event. A resulting height
 * jump above `height.jump_warn_m` between neighbours raises a notification.
 */
object AutoHeightHook {
    const val DEBOUNCE_MS = 300
    const val SEQUENCE_TITLE = "Set heights of new nodes"

    private var activeListener: ActiveLayerChangeListener? = null
    private val dsListener = Listener()
    private var attachedDs: DataSet? = null
    private var timer: Timer? = null
    private val pending = LinkedHashSet<Node>()

    fun isEnabled(): Boolean = LaneletSettings.isAutoHeightEnabled()

    /** Installed once per session; the setting is read on every event. */
    fun install() {
        if (activeListener == null) {
            activeListener = ActiveLayerChangeListener { attachTo(currentDataset()) }
            try {
                MainApplication.getLayerManager().addActiveLayerChangeListener(activeListener)
            } catch (e: Exception) {
                Logging.debug("lanelet2: auto-height active-layer listener: {0}", e.message)
            }
        }
        attachTo(currentDataset())
    }

    /** Visible for tests: listen to [ds] (at most one dataset at a time). */
    fun attachTo(ds: DataSet?) {
        if (ds === attachedDs) return
        attachedDs?.let {
            try {
                it.removeDataSetListener(dsListener)
            } catch (_: Exception) {
            }
        }
        attachedDs = null
        pending.clear()
        if (ds == null) return
        try {
            ds.addDataSetListener(dsListener)
            attachedDs = ds
        } catch (_: Exception) {
        }
    }

    private fun currentDataset(): DataSet? = try {
        LaneletUtils.getEditLayer()?.data
    } catch (_: Exception) {
        null
    }

    internal fun onPrimitivesAdded(primitives: Collection<OsmPrimitive?>) {
        if (!isEnabled()) return
        for (p in primitives) {
            if (p is Node && p.isNew && !p.hasKey(HeightTools.ELE_KEY)) pending.add(p)
        }
        if (pending.isEmpty()) return
        val t = timer ?: Timer(DEBOUNCE_MS) {
            try {
                flush()
            } catch (e: Exception) {
                Logging.debug("lanelet2: auto-height flush: {0}", e.message)
            }
        }.also {
            it.isRepeats = false
            timer = it
        }
        t.restart()
    }

    /**
     * Give the pending nodes their heights now. Returns the jump warning, if
     * any (also shown as a notification in the GUI).
     */
    fun flush(): String? {
        val ds = attachedDs ?: return null
        val batch = pending.toList()
        pending.clear()
        if (batch.isEmpty() || !isEnabled()) return null
        val plan = HeightTools.planNewNodeHeights(ds, batch)
        if (plan.changes.isEmpty()) return null
        val commands = plan.changes.map { (n, z) -> ChangePropertyCommand(n, HeightTools.ELE_KEY, HeightTools.formatEle(z)) }
        LaneletUtils.getUndo().add(SequenceCommand(SEQUENCE_TITLE, commands))
        val threshold = LaneletSettings.getHeightJumpWarnM()
        val warning = HeightTools.describeJumps(HeightTools.heightJumps(plan.changes.map { it.first }, threshold), threshold)
            ?.let { "New nodes took the height of the nearest node. $it." }
        if (warning != null) notifyWarning(warning)
        return warning
    }

    fun pendingSize(): Int = pending.size

    fun attachedDataset(): DataSet? = attachedDs

    /** A non-modal JOSM notification; nothing when headless (tests). */
    fun notifyWarning(text: String) {
        Logging.warn("lanelet2: $text")
        if (GraphicsEnvironment.isHeadless()) return
        GuiHelper.runInEDT {
            Notification(text).setIcon(JOptionPane.WARNING_MESSAGE).setDuration(Notification.TIME_LONG).show()
        }
    }

    private class Listener : DataSetListener {
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
