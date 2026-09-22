package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
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
 * New nodes get a height (`ele`) so a way drawn in JOSM does not drop to 0 m in
 * an absolute-height map. On by default (`autoheight.enabled`). The rule is
 * [HeightTools.planNewNodeHeights]: a node inserted into a way, or joining
 * ways, is interpolated between the known heights on both sides; a free end or
 * an orphan takes the nearest node's height.
 *
 * Drawing a way click by click gives each node its height while it is still
 * the free end. So the heights this hook wrote are remembered, and when a way
 * holding such nodes changes shape (the new way is finished on an existing
 * node, say), they are planned again, treating each other as unknown. A
 * height someone changed since is no longer ours and is left alone.
 *
 * Same shape as [AutotagHook]: nodes are only collected in dataset events and
 * tagged after a short debounce through one SequenceCommand (its own undo
 * step), so the hook never mutates inside a dataset event. A resulting height
 * jump above `height.jump_warn_m` between neighbours raises a notification.
 */
object AutoHeightHook {
    const val DEBOUNCE_MS = 300
    const val SEQUENCE_TITLE = "Set heights of new nodes"
    const val REFRESH_TITLE = "Update heights of new nodes"

    private var activeListener: ActiveLayerChangeListener? = null
    private val dsListener = Listener()
    private var attachedDs: DataSet? = null
    private var timer: Timer? = null
    private val pending = LinkedHashSet<Node>()
    private val refresh = LinkedHashSet<Node>()
    private val assigned = HashMap<Node, String>() // node -> the ele this hook wrote

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
        refresh.clear()
        assigned.clear()
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
        if (pending.isNotEmpty()) restartTimer()
    }

    /** A way changed shape: its nodes with a height guessed by this hook get planned again. */
    internal fun onWayChanged(way: Way) {
        if (!isEnabled() || assigned.isEmpty()) return
        for (n in way.nodes) if (isOurs(n)) refresh.add(n)
        if (refresh.isNotEmpty()) restartTimer()
    }

    private fun isOurs(n: Node): Boolean =
        !n.isDeleted && n.dataSet === attachedDs && assigned[n]?.let { it == n.get(HeightTools.ELE_KEY) } == true

    private fun restartTimer() {
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
        val batch = pending.filter { !it.hasKey(HeightTools.ELE_KEY) }
        val again = refresh.filter { isOurs(it) && it !in pending }
        pending.clear()
        refresh.clear()
        assigned.keys.removeIf { !isOurs(it) }
        if ((batch.isEmpty() && again.isEmpty()) || !isEnabled()) return null
        // Neither new nodes nor earlier guesses count as known heights.
        val unknown = HashSet<Node>(batch).apply { addAll(assigned.keys) }
        val plan = HeightTools.planHeights(ds, batch + again, unknown)
        if (plan.changes.isEmpty()) return null
        val commands = plan.changes.map { (n, z) ->
            val v = HeightTools.formatEle(z)
            assigned[n] = v
            ChangePropertyCommand(n, HeightTools.ELE_KEY, v)
        }
        LaneletUtils.getUndo().add(SequenceCommand(if (batch.isEmpty()) REFRESH_TITLE else SEQUENCE_TITLE, commands))
        val threshold = LaneletSettings.getHeightJumpWarnM()
        val warning = HeightTools.describeJumps(HeightTools.heightJumps(plan.changes.map { it.first }, threshold), threshold)
            ?.let { "Heights given to new nodes: $it." }
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
        override fun wayNodesChanged(event: WayNodesChangedEvent) {
            try {
                onWayChanged(event.changedWay)
            } catch (_: Exception) {
            }
        }
        override fun relationMembersChanged(event: RelationMembersChangedEvent) = Unit
        override fun otherDatasetChange(event: AbstractDatasetChangedEvent) = Unit
        override fun dataChanged(event: DataChangedEvent) = Unit
    }
}
