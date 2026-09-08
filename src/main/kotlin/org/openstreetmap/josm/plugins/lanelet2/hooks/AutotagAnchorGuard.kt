package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.util.ArrayList
import javax.swing.SwingUtilities

/**
 * Merge-anchor command-queue guard from `autotag_hook.py`.
 *
 * Re-entrancy: listens on `commandAdded` only (never undo/redo). Own revert
 * sets [reverting] so the undo cannot re-trigger the guard.
 */
internal object AutotagAnchorGuard {
    private var listener: CommandQueuePreciseListener? = null
    private val positions = LinkedHashMap<Long, Pair<Double, Double>>()
    private var positionsDs: DataSet? = null
    private var reverting = false
    private var warnPending = false

    fun install() {
        if (listener == null) {
            val l = AnchorGuardListener()
            listener = l
            try {
                LaneletUtils.getUndo().addCommandQueuePreciseListener(l)
            } catch (_: Exception) {
            }
        }
        try {
            val layer = LaneletUtils.getEditLayer()
            if (layer != null) {
                positionsDs = null
                ensureCache(layer.data)
            }
        } catch (_: Exception) {
        }
    }

    fun uninstall() {
        val l = listener
        if (l != null) {
            try {
                LaneletUtils.getUndo().removeCommandQueuePreciseListener(l)
            } catch (_: Exception) {
            }
            listener = null
        }
        positions.clear()
        positionsDs = null
        reverting = false
        warnPending = false
    }

    fun listenerInstalled(): Boolean = listener != null

    fun isReverting(): Boolean = reverting

    /** Test helper: seed the snapshot without a live layer. */
    fun seedCacheForTest(ds: DataSet, snapshot: Map<Long, Pair<Double, Double>>) {
        positions.clear()
        positions.putAll(snapshot)
        positionsDs = ds
    }

    fun cachedIds(): Set<Long> = positions.keys.toSet()

    internal fun ensureCache(dataset: DataSet?) {
        if (dataset == null) return
        if (positionsDs === dataset) return
        positions.clear()
        try {
            for (node in dataset.nodes) {
                if (!node.isDeleted && node.hasKey(AutotagLogic.MERGE_ANCHOR_TAG)) {
                    positions[node.uniqueId] = node.lat() to node.lon()
                }
            }
        } catch (_: Exception) {
        }
        positionsDs = dataset
    }

    private fun registerNewAnchors(command: Command) {
        val modified = ArrayList<OsmPrimitive>()
        val deleted = ArrayList<OsmPrimitive>()
        val added = ArrayList<OsmPrimitive>()
        try {
            command.fillModifiedData(modified, deleted, added)
        } catch (_: Exception) {
        }
        for (collection in listOf(added, modified)) {
            for (prim in collection) {
                try {
                    if (AutotagLogic.isAnchorNode(prim) && !prim.isDeleted && prim is Node) {
                        positions[prim.uniqueId] = prim.lat() to prim.lon()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    internal fun onCommandAdded() {
        if (!LaneletSettings.getProtectMergeAnchors() || reverting) return
        val command = try {
            LaneletUtils.getUndo().lastCommand
        } catch (_: Exception) {
            null
        } ?: return
        val dataset = try {
            command.affectedDataSet
        } catch (_: Exception) {
            null
        }
        ensureCache(dataset)
        val violation = AutotagLogic.checkAnchorCommand(positions) { nid ->
            if (dataset == null) return@checkAnchorCommand null
            dataset.getPrimitiveById(nid, OsmPrimitiveType.NODE) as? Node
        }
        if (violation != null) {
            val kind = violation.kind
            SwingUtilities.invokeLater { handleViolation(kind, command) }
        } else {
            registerNewAnchors(command)
        }
    }

    private fun handleViolation(kind: String, command: Command) {
        revert(command)
        if (warnPending) return
        warnPending = true
        try {
            val detail = AutotagLogic.warnDetail(kind)
            Dialogs.warn(
                "A merge anchor node was $detail, so the edit was reverted.\n\n" +
                    "Merge anchors must remain unchanged so partial maps can be merged\n" +
                    "into a city-level map. Disable 'Protect merge_anchor nodes' in the\n" +
                    "Autotag dialog if you really need to edit one.",
                "Merge anchor protected",
            )
        } finally {
            warnPending = false
        }
    }

    private fun revert(command: Command) {
        val undo = LaneletUtils.getUndo()
        reverting = true
        try {
            if (undo.lastCommand === command && undo.hasUndoCommands()) {
                undo.undo()
            }
        } catch (_: Exception) {
        } finally {
            reverting = false
        }
        try {
            positionsDs = null
            ensureCache(command.affectedDataSet)
        } catch (_: Exception) {
        }
    }

    private class AnchorGuardListener : CommandQueuePreciseListener {
        override fun commandAdded(event: UndoRedoHandler.CommandAddedEvent) {
            try {
                onCommandAdded()
            } catch (_: Exception) {
            }
        }

        override fun cleaned(event: UndoRedoHandler.CommandQueueCleanedEvent) = Unit
        override fun commandUndone(event: UndoRedoHandler.CommandUndoneEvent) = Unit
        override fun commandRedone(event: UndoRedoHandler.CommandRedoneEvent) = Unit
    }
}
