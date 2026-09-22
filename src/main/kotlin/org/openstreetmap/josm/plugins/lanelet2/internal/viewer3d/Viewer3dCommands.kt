package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.plugins.lanelet2.infra.HeightTools
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.event.ActionEvent

/**
 * The JOSM operations the viewer can trigger besides data commands. An
 * interface so tests can run headless; [JosmEditorActions] is the real one.
 */
interface EditorActions {
    fun select(ds: DataSet, prims: Collection<OsmPrimitive>)

    /** Run JOSM's own Delete action on [prims]; null when it ran, else why not. */
    fun delete(ds: DataSet, prims: Collection<OsmPrimitive>): String?

    fun undo(): Boolean

    fun redo(): Boolean
}

object JosmEditorActions : EditorActions {
    override fun select(ds: DataSet, prims: Collection<OsmPrimitive>) {
        ds.setSelected(prims)
    }

    override fun delete(ds: DataSet, prims: Collection<OsmPrimitive>): String? {
        ds.setSelected(prims)
        // MainMenu.delete is the action behind JOSM's Delete key: it checks the
        // layer is visible and modifiable, then runs DeleteCommand with JOSM's
        // usual confirmations (relation membership, outside the download area).
        val action = MainApplication.getMenu()?.delete ?: return "JOSM's Delete action is not available"
        if (!action.isEnabled) return "JOSM cannot delete this selection (locked or read-only layer?)"
        action.actionPerformed(ActionEvent(ds, ActionEvent.ACTION_PERFORMED, "lanelet2-viewer3d-delete"))
        return null
    }

    override fun undo(): Boolean {
        val h = UndoRedoHandler.getInstance()
        if (!h.hasUndoCommands()) return false
        h.undo()
        return true
    }

    override fun redo(): Boolean {
        val h = UndoRedoHandler.getInstance()
        if (!h.hasRedoCommands()) return false
        h.redo()
        return true
    }
}

/**
 * Where viewer edits land: the edit layer's data, whether the layer is
 * visible, and how a finished edit is committed (one SequenceCommand).
 */
class EditTarget(
    val ds: DataSet,
    val visible: Boolean,
    val commit: (List<Command>) -> Unit,
) {
    companion object {
        fun of(layer: OsmDataLayer?): EditTarget? {
            val ds = layer?.data ?: return null
            return EditTarget(ds, layer.isVisible) { cmds ->
                applySequence(Viewer3dConstants.SEQUENCE_TITLE, cmds, layer)
            }
        }
    }
}

/** Parse OSM ids and build undoable commands from inbound bridge ops. */
object Viewer3dCommands {
    private val idTypes = mapOf(
        "node" to OsmPrimitiveType.NODE,
        "way" to OsmPrimitiveType.WAY,
        "relation" to OsmPrimitiveType.RELATION,
    )

    fun parseId(token: String?): Pair<OsmPrimitiveType, Long>? {
        if (token.isNullOrEmpty() || !token.contains('/')) return null
        val parts = token.split('/', limit = 2)
        val ptype = idTypes[parts[0]] ?: return null
        val pid = parts[1].toLongOrNull() ?: return null
        return ptype to pid
    }

    fun lookup(ds: DataSet, token: String?) =
        parseId(token)?.let { (ptype, pid) ->
            try {
                ds.getPrimitiveById(pid, ptype)
            } catch (_: Exception) {
                null
            }
        }

    /** Commands for the data ops, plus the ids that no longer exist in [ds]. */
    data class DataPlan(val commands: List<Command>, val missing: List<String>)

    /**
     * Build JOSM [Command]s for data edits. `set_view` and the editor actions
     * are not data edits. Unknown ops are skipped silently.
     */
    fun planDataCommands(ops: List<InboundOp>, ds: DataSet, anchor: Anchor): DataPlan {
        val commands = ArrayList<Command>()
        val missing = ArrayList<String>()
        for (op in ops) {
            when (op) {
                is InboundOp.MoveNode -> {
                    val (ptype, _) = parseId(op.id) ?: continue
                    if (ptype != OsmPrimitiveType.NODE) continue
                    val node = lookup(ds, op.id) as? Node
                    if (node == null || node.isDeleted) {
                        missing.add(op.id)
                        continue
                    }
                    val newNode = Node(node)
                    if (op.x != null && op.y != null) {
                        val (lat, lon) = Viewer3dEnu.enuToLatLon(op.x, op.y, anchor.lat, anchor.lon)
                        newNode.coor = LatLon(lat, lon)
                    }
                    if (op.z != null) newNode.put("ele", Viewer3dEnu.formatEleG(op.z))
                    commands.add(ChangeCommand(node, newNode))
                }
                is InboundOp.SetTag -> {
                    val prim = lookup(ds, op.id)
                    if (prim == null || prim.isDeleted) {
                        missing.add(op.id)
                        continue
                    }
                    commands.add(ChangePropertyCommand(prim, op.key, op.value))
                }
                else -> Unit
            }
        }
        return DataPlan(commands, missing)
    }

    fun buildDataCommands(ops: List<InboundOp>, ds: DataSet, anchor: Anchor): List<Command> =
        planDataCommands(ops, ds, anchor).commands

    private fun resolve(ds: DataSet, ids: List<String>): Pair<List<OsmPrimitive>, List<String>> {
        val found = ArrayList<OsmPrimitive>()
        val missing = ArrayList<String>()
        for (id in ids) {
            val p = lookup(ds, id)
            if (p == null || p.isDeleted) missing.add(id) else found.add(p)
        }
        return found to missing
    }

    private fun describeMissing(missing: List<String>): String {
        val shown = missing.take(3).joinToString(", ")
        val more = if (missing.size > 3) " and ${missing.size - 3} more" else ""
        return "${missing.size} object(s) no longer exist in JOSM: $shown$more"
    }

    /**
     * Apply one inbound command. `set_view` moves the map (not undoable).
     * Data ops (`move_node`, `set_tag`) go into **one** SequenceCommand, so one
     * gesture is one Ctrl+Z; if any of their targets is gone, none is applied.
     * `select` / `delete_selection` / `undo` / `redo` go through [actions].
     * `interpolate_height` sets `ele` along a way. A change that leaves
     * neighbouring nodes more than [jumpWarnM] apart in height is applied but
     * the reply carries a warning.
     *
     * Returns the reply for a command that carries an id (null otherwise).
     */
    fun applyInbound(
        command: InboundCommand,
        target: EditTarget?,
        anchor: Anchor?,
        driveView: Boolean,
        setView: (InboundOp.SetView, Anchor) -> Unit,
        actions: EditorActions = JosmEditorActions,
        jumpWarnM: Double = LaneletSettings.getHeightJumpWarnM(),
    ): OutboundMessage.CommandResult? {
        if (anchor != null) {
            for (op in command.ops) {
                if (op is InboundOp.SetView) setView(op, anchor)
            }
        }
        val edits = command.ops.filter { it !is InboundOp.SetView }
        if (edits.isEmpty()) return null
        fun reply(ok: Boolean, message: String, warning: String? = null) =
            command.id?.let { OutboundMessage.CommandResult(it, ok, message, warning) }
        fun jumpWarning(nodes: Collection<Node>) =
            HeightTools.describeJumps(HeightTools.heightJumps(nodes, jumpWarnM), jumpWarnM)

        // Undo / redo act on JOSM's global stack, like its own Ctrl+Z.
        if (edits.all { it is InboundOp.Undo || it is InboundOp.Redo }) {
            var done = 0
            for (op in edits) if (if (op is InboundOp.Undo) actions.undo() else actions.redo()) done++
            val what = if (edits.first() is InboundOp.Undo) "undo" else "redo"
            return if (done > 0) reply(true, "JOSM $what") else reply(false, "Nothing to $what in JOSM")
        }

        if (target == null) return reply(false, "No editable data layer in JOSM")
        val ds = target.ds

        val select = edits.filterIsInstance<InboundOp.Select>().lastOrNull()
        if (select != null && edits.size == 1) {
            actions.select(ds, resolve(ds, select.ids).first)
            return reply(true, "Selected ${select.ids.size} in JOSM")
        }

        if (!target.visible) {
            return reply(false, "The edit layer is hidden in JOSM; show it to edit from the 3D viewer")
        }

        val delete = edits.filterIsInstance<InboundOp.DeleteSelection>().lastOrNull()
        if (delete != null) {
            val (prims, missing) = resolve(ds, delete.ids)
            if (prims.isEmpty()) return reply(false, if (missing.isEmpty()) "Nothing selected" else describeMissing(missing))
            val refused = actions.delete(ds, prims)
            return if (refused == null) reply(true, "Delete sent to JOSM") else reply(false, refused)
        }

        val interpolate = edits.filterIsInstance<InboundOp.InterpolateHeight>().lastOrNull()
        if (interpolate != null) {
            val way = lookup(ds, interpolate.way) as? Way
            if (way == null || way.isDeleted) return reply(false, describeMissing(listOf(interpolate.way)))
            val (anchors, missing) = resolve(ds, interpolate.anchors)
            if (missing.isNotEmpty()) return reply(false, describeMissing(missing))
            val hp = HeightTools.planInterpolation(way, anchors.filterIsInstance<Node>())
            hp.error?.let { return reply(false, "Cannot interpolate: $it") }
            if (hp.changes.isEmpty()) return reply(true, "Heights along ${interpolate.way} are already linear")
            target.commit(hp.changes.map { (n, z) -> ChangePropertyCommand(n, HeightTools.ELE_KEY, HeightTools.formatEle(z)) })
            return reply(
                true,
                "Interpolated ${hp.changes.size} height(s) along ${interpolate.way}",
                jumpWarning(hp.changes.map { it.first }),
            )
        }

        val a = anchor ?: return reply(false, "The viewer is not synced with JOSM yet")
        val plan = planDataCommands(edits, ds, a)
        if (plan.missing.isNotEmpty()) return reply(false, describeMissing(plan.missing))
        if (plan.commands.isEmpty()) return reply(false, "Nothing to apply")
        target.commit(plan.commands)
        val lifted = edits.filterIsInstance<InboundOp.MoveNode>().filter { it.z != null }
            .mapNotNull { lookup(ds, it.id) as? Node }
        return reply(true, "Applied ${plan.commands.size} change(s)", jumpWarning(lifted))
    }
}
