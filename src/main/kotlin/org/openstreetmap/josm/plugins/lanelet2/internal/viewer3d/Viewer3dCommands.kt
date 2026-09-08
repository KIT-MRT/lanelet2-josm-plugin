package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.gui.layer.OsmDataLayer

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

    /**
     * Build JOSM [Command]s for data edits. `set_view` is excluded — it is not
     * an undoable edit. Unknown ops are skipped silently.
     */
    fun buildDataCommands(
        ops: List<InboundOp>,
        ds: DataSet,
        anchor: Anchor,
    ): List<Command> {
        val commands = ArrayList<Command>()
        for (op in ops) {
            when (op) {
                is InboundOp.SetView -> Unit
                is InboundOp.MoveNode -> {
                    val (ptype, _) = parseId(op.id) ?: continue
                    if (ptype != OsmPrimitiveType.NODE) continue
                    val node = lookup(ds, op.id) as? Node ?: continue
                    if (node.isDeleted) continue
                    val (lat, lon) = Viewer3dEnu.enuToLatLon(op.x, op.y, anchor.lat, anchor.lon)
                    val newNode = Node(node)
                    newNode.coor = LatLon(lat, lon)
                    if (op.z != null) {
                        Viewer3dEnu.formatEleG(op.z).let { newNode.put("ele", it) }
                    }
                    commands.add(ChangeCommand(node, newNode))
                }
                is InboundOp.SetTag -> {
                    val prim = lookup(ds, op.id) ?: continue
                    if (prim.isDeleted) continue
                    commands.add(ChangePropertyCommand(prim, op.key, op.value))
                }
            }
        }
        return commands
    }

    /**
     * Apply inbound ops: `move_node`/`set_tag` in one [org.openstreetmap.josm.command.SequenceCommand]
     * so Ctrl+Z works; `set_view` is a camera move, not a command.
     */
    fun applyInbound(
        command: InboundCommand,
        layer: OsmDataLayer?,
        anchor: Anchor?,
        driveView: Boolean,
        setView: (InboundOp.SetView, Anchor) -> Unit,
    ) {
        if (anchor != null) {
            for (op in command.ops) {
                if (op is InboundOp.SetView) setView(op, anchor)
            }
        }
        if (layer == null || !layer.isVisible) return
        val ds = layer.data ?: return
        val a = anchor ?: return
        val commands = buildDataCommands(command.ops, ds, a)
        if (commands.isEmpty()) return
        applySequence(Viewer3dConstants.SEQUENCE_TITLE, commands, layer)
    }
}
