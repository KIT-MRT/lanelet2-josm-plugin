package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.command.AddCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionDialog
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.infra.RegulatoryElements
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList
import java.util.LinkedHashMap

/**
 * Build a new dataset of polylines visualizing regulatory-element membership.
 *
 * One SequenceCommand of [AddCommand]s targeting the **debug** dataset (not the
 * edit layer). Port of `debug_regulatory_element_connections.py`.
 *
 * [run] prompts for a subtype via [UserPrompts.pick], then either opens the
 * collection dialog (setting on) or uses the current selection.
 *
 * No MapCSS is registered: the original draws tagged ways/nodes and relies on
 * whatever styles are already on. [org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings.STYLE_CATALOG]
 * DYNAMIC entries (`ll2_file_boundaries`, `ll2_notes`) are unrelated.
 *
 * **Latent Jython bug (replicated):** the reverse index matches `type == "lanelet"`
 * exactly (not case-folded), unlike [LaneletSelection.extractLanelets].
 *
 * Python 2 `/` on ints does not affect geometry here: centroids use
 * `/ float(n_pts)`; the only `//` is progress-bar throttling.
 */
object DebugRegulatoryConnections {
    const val TITLE = "Debug Regulatory Element Connections"
    const val SEQUENCE_NAME = "Debug regulatory element connections"

    const val HELP_TEXT = """Debug Regulatory Element Connections (Lanelet2)

Creates a new layer with polylines visualizing how regulatory elements connect to:
- ref_line (stop lines)
- refers (traffic lights, signs, etc.)
- lanelets that reference the regulatory element

Central nodes represent each relation. Ways show the direction of relationships.
Useful for debugging relation structure and membership."""

    val WAY_TAG_MAP: Map<String, String> = mapOf(
        "ref_line" to "relation_to_ref_line",
        "refers" to "relation_to_refers",
        "cancel_line" to "relation_to_cancel_line",
        "cancels" to "relation_to_cancels",
        "yield" to "relation_to_yield",
        "right_of_way" to "relation_to_right_of_way",
    )

    private val CENTRAL_SKIP_KEYS = setOf(
        "type", "id", "subtype", "ref_line", "refers",
        "cancel_line", "cancels", "yield", "right_of_way", "lanelets", "source_id",
    )

    data class Build(val dataSet: DataSet, val commands: List<Command>)

    /** Return (lat, lon) centroid of way nodes, or null if no coords. */
    fun wayCentroid(way: Way): Pair<Double, Double>? {
        val pts = ArrayList<Pair<Double, Double>>()
        for (n in way.nodes) {
            val c = n.coor ?: continue
            pts.add(Pair(c.lat(), c.lon()))
        }
        if (pts.isEmpty()) return null
        val nPts = pts.size.toDouble()
        val lat = pts.sumOf { it.first } / nPts
        val lon = pts.sumOf { it.second } / nPts
        return Pair(lat, lon)
    }

    /** Return (lat, lon) centroid of lanelet (mean of left+right way centroids). */
    fun laneletCentroid(lanelet: Relation): Pair<Double, Double>? {
        val (leftW, rightW) = Lanelet.extractLeftRightWays(lanelet)
        val pts = ArrayList<Pair<Double, Double>>()
        if (leftW != null) wayCentroid(leftW)?.let { pts.add(it) }
        if (rightW != null) wayCentroid(rightW)?.let { pts.add(it) }
        if (pts.isEmpty()) return null
        val nPts = pts.size.toDouble()
        val lat = pts.sumOf { it.first } / nPts
        val lon = pts.sumOf { it.second } / nPts
        return Pair(lat, lon)
    }

    fun primitiveCentroid(prim: OsmPrimitive?): Pair<Double, Double>? {
        if (prim == null) return null
        if (prim is Way) return wayCentroid(prim)
        if (prim is Relation) return laneletCentroid(prim)
        return null
    }

    /**
     * Reverse index: reg-elem uniqueId -> lanelets that reference it.
     * Type compared to `"lanelet"` exactly, matching the Jython.
     */
    fun buildRegElemToLaneletsIndex(data: DataSet): Map<Long, MutableList<Relation>> {
        val index = LinkedHashMap<Long, MutableList<Relation>>()
        for (prim in data.relations) {
            if (prim == null || prim.get("type") != "lanelet") continue
            for (m in prim.members) {
                if (m.role != "regulatory_element") continue
                val mem = m.member ?: continue
                val regId = try {
                    mem.uniqueId
                } catch (_: Exception) {
                    continue
                }
                index.getOrPut(regId) { ArrayList() }.add(prim)
            }
        }
        return index
    }

    fun copyTagsToNode(node: Node, source: OsmPrimitive?, skipKeys: Set<String> = emptySet()) {
        if (source == null) return
        try {
            val keys = source.keys ?: return
            for ((k, v) in keys) {
                if (k.isNullOrEmpty() || k in skipKeys) continue
                if (!v.isNullOrEmpty()) node.put(k, v)
            }
        } catch (_: Exception) {
        }
    }

    fun buildDebugLayer(
        data: DataSet,
        regElems: List<Relation>,
        progress: ((String, Int, Int, String) -> Unit)? = null,
    ): Build {
        fun prog(phase: String, cur: Int, tot: Int, msg: String) {
            progress?.invoke(phase, cur, tot, msg)
        }

        val ds = DataSet()
        val commands = ArrayList<Command>()
        val centralNodes = LinkedHashMap<Long, Node>()
        val targetNodeCache = LinkedHashMap<Pair<Long, String>, Node>()

        prog("index", 0, 1, "Building lanelet index...")
        val regToLanelets = buildRegElemToLaneletsIndex(data)
        prog("index", 1, 1, "Index built.")

        val nReg = regElems.size
        val laneletsCache = LinkedHashMap<Long, List<Relation>>()

        for ((idx, regElem) in regElems.withIndex()) {
            prog("reg_elems", idx, nReg, "Processing relation ${idx + 1}/$nReg")
            val regId = try {
                regElem.uniqueId
            } catch (_: Exception) {
                continue
            }
            val members = RegulatoryElements.extractMembersByRole(regElem)
            val referringLanelets = regToLanelets[regId] ?: emptyList()
            laneletsCache[regId] = referringLanelets

            val allPts = ArrayList<Pair<Double, Double>>()
            for (items in members.values) {
                for (prim in items) {
                    primitiveCentroid(prim)?.let { allPts.add(it) }
                }
            }
            for (ll in referringLanelets) {
                laneletCentroid(ll)?.let { allPts.add(it) }
            }
            if (allPts.isEmpty()) continue

            val nPts = allPts.size.toDouble()
            val cenLat = allPts.sumOf { it.first } / nPts
            val cenLon = allPts.sumOf { it.second } / nPts

            val refLineIds = ArrayList<String>()
            val refersIds = ArrayList<String>()
            val cancelLineIds = ArrayList<String>()
            val cancelsIds = ArrayList<String>()
            val yieldIds = ArrayList<String>()
            val rightOfWayIds = ArrayList<String>()
            val laneletIds = ArrayList<String>()
            for ((role, items) in members) {
                for (prim in items) {
                    try {
                        val pid = prim.uniqueId.toString()
                        when (role) {
                            "ref_line" -> refLineIds.add(pid)
                            "refers" -> refersIds.add(pid)
                            "cancel_line" -> cancelLineIds.add(pid)
                            "cancels" -> cancelsIds.add(pid)
                            "yield" -> yieldIds.add(pid)
                            "right_of_way" -> rightOfWayIds.add(pid)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            for (ll in referringLanelets) {
                try {
                    laneletIds.add(ll.uniqueId.toString())
                } catch (_: Exception) {
                }
            }

            val cenNode = Node(LatLon(cenLat, cenLon))
            cenNode.put("type", "regulatory_element_debug")
            cenNode.put("source_id", regId.toString())
            cenNode.put("id", regId.toString())
            cenNode.put("subtype", regElem.get("subtype") ?: "")
            if (refLineIds.isNotEmpty()) cenNode.put("ref_line", refLineIds.joinToString(","))
            if (refersIds.isNotEmpty()) cenNode.put("refers", refersIds.joinToString(","))
            if (cancelLineIds.isNotEmpty()) cenNode.put("cancel_line", cancelLineIds.joinToString(","))
            if (cancelsIds.isNotEmpty()) cenNode.put("cancels", cancelsIds.joinToString(","))
            if (yieldIds.isNotEmpty()) cenNode.put("yield", yieldIds.joinToString(","))
            if (rightOfWayIds.isNotEmpty()) cenNode.put("right_of_way", rightOfWayIds.joinToString(","))
            if (laneletIds.isNotEmpty()) cenNode.put("lanelets", laneletIds.joinToString(","))
            copyTagsToNode(cenNode, regElem, CENTRAL_SKIP_KEYS)
            commands.add(AddCommand(ds, cenNode))
            centralNodes[regId] = cenNode

            for ((role, items) in members) {
                val tagKey = WAY_TAG_MAP[role] ?: "relation_to_$role"
                for (prim in items) {
                    val c = primitiveCentroid(prim) ?: continue
                    val pid = try {
                        prim.uniqueId
                    } catch (_: Exception) {
                        continue
                    }
                    val cacheKey = Pair(pid, role)
                    if (cacheKey !in targetNodeCache) {
                        val tgtNode = Node(LatLon(c.first, c.second))
                        tgtNode.put("source_id", pid.toString())
                        copyTagsToNode(tgtNode, prim, setOf("source_id"))
                        commands.add(AddCommand(ds, tgtNode))
                        targetNodeCache[cacheKey] = tgtNode
                    }
                    val tgtNode = targetNodeCache.getValue(cacheKey)
                    val connWay = Way()
                    connWay.setNodes(listOf(cenNode, tgtNode))
                    connWay.put(tagKey, pid.toString())
                    commands.add(AddCommand(ds, connWay))
                }
            }

            for (ll in referringLanelets) {
                val c = laneletCentroid(ll) ?: continue
                val lid = try {
                    ll.uniqueId
                } catch (_: Exception) {
                    continue
                }
                val cacheKey = Pair(lid, "regulatory_element")
                if (cacheKey !in targetNodeCache) {
                    val tgtNode = Node(LatLon(c.first, c.second))
                    tgtNode.put("source_id", lid.toString())
                    copyTagsToNode(tgtNode, ll, setOf("source_id"))
                    commands.add(AddCommand(ds, tgtNode))
                    targetNodeCache[cacheKey] = tgtNode
                }
                val tgtNode = targetNodeCache.getValue(cacheKey)
                val connWay = Way()
                connWay.setNodes(listOf(cenNode, tgtNode))
                connWay.put("relation_to_lanelet", lid.toString())
                connWay.put("relation_is_member_of", lid.toString())
                commands.add(AddCommand(ds, connWay))
            }
        }

        val regIds = centralNodes.keys.toList()
        val nPairs = if (regIds.size > 1) (regIds.size * (regIds.size - 1)) / 2 else 0
        var pairIdx = 0
        val throttle = maxOf(1, nPairs / 20)

        fun laneletIdsOf(list: List<Relation>): Set<Long> {
            val out = LinkedHashSet<Long>()
            for (ll in list) {
                try {
                    out.add(ll.uniqueId)
                } catch (_: Exception) {
                }
            }
            return out
        }

        for (i in regIds.indices) {
            for (j in (i + 1) until regIds.size) {
                val ridA = regIds[i]
                val ridB = regIds[j]
                if (nPairs > 0 && pairIdx % throttle == 0) {
                    prog("cross", pairIdx, nPairs, "Cross-connections $pairIdx/$nPairs")
                }
                pairIdx += 1
                val shared = laneletIdsOf(laneletsCache[ridA] ?: emptyList())
                    .intersect(laneletIdsOf(laneletsCache[ridB] ?: emptyList()))
                if (shared.isEmpty()) continue
                val cenA = centralNodes[ridA] ?: continue
                val cenB = centralNodes[ridB] ?: continue
                val crossWay = Way()
                crossWay.setNodes(listOf(cenA, cenB))
                crossWay.put("connects_regulatory_elements", "$ridA,$ridB")
                crossWay.put("shared_lanelet", shared.joinToString(","))
                commands.add(AddCommand(ds, crossWay))
            }
        }

        return Build(ds, commands)
    }

    fun apply(
        data: DataSet,
        regElems: List<Relation>,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Build {
        val build = buildDebugLayer(data, regElems)
        if (build.commands.isNotEmpty()) {
            applySequence(SEQUENCE_NAME, build.commands, layer = null, undo = undo)
        }
        return build
    }

    fun resolveSubtype(selected: String?, custom: String): String? = when {
        selected == null || selected == "(any)" -> null
        selected == "Custom" -> custom.trim().ifEmpty { null }
        else -> selected
    }

    fun run(ui: UserPrompts = Dialogs, subtype: String? = null, promptSubtype: Boolean = true) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val data = layer.data
        val relSubtype = if (promptSubtype) {
            val picked = ui.pick("Filter by Subtype", "Regulatory element subtype:", RegulatoryElements.SUBTYPES)
                ?: return
            val custom = if (picked == "Custom") ui.ask("Filter by Subtype", "Custom subtype:") ?: "" else ""
            resolveSubtype(picked, custom)
        } else {
            subtype
        }
        if (CollectionLogic.shouldOpenCollectionDialog()) {
            CollectionDialog.clearSelection(data)
            val subtypeStr = relSubtype ?: "(any)"
            CollectionDialog.showRelationCollection(
                data = data,
                onDone = { collected -> finishDebug(data, collected, relSubtype, ui) },
                relType = "regulatory_element",
                relSubtype = relSubtype,
                title = "$TITLE: $subtypeStr",
                message = "Select ways (e.g. traffic lights, stop lines) or regulatory_element relations. Add / Select / Done.",
                minCount = 1,
                helpTitle = TITLE,
                helpText = HELP_TEXT,
                helpLinks = listOf(
                    "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
                    "LaneletAndAreaTagging" to "LaneletAndAreaTagging.md",
                ),
                ui = ui,
            )
            return
        }
        val collected = LaneletSelection.extractRelationsOrFromWays(
            data, data.selected, "regulatory_element", relSubtype,
        )
        finishDebug(data, collected, relSubtype, ui)
    }

    fun finishDebug(
        data: DataSet,
        collected: List<Relation>,
        relSubtype: String?,
        ui: UserPrompts,
    ) {
        if (collected.isEmpty()) {
            ui.warn("Select at least 1 regulatory element (or a way that belongs to one).", TITLE)
            return
        }
        val build = apply(data, collected)
        if (build.commands.isEmpty()) {
            ui.warn(
                "No connections could be built (relations may have no members with coordinates).",
                TITLE,
            )
            return
        }
        tryAddDebugLayer(build.dataSet, relSubtype)
        ui.info(
            "Created debug layer with ${build.commands.size} primitives for ${collected.size} regulatory element(s).",
            TITLE,
        )
    }

    private fun tryAddDebugLayer(ds: DataSet, relSubtype: String?) {
        val layerName = if (relSubtype != null) {
            "Regulatory Element Debug: $relSubtype"
        } else {
            "Regulatory Element Debug"
        }
        try {
            val lm = MainApplication.getLayerManager()
            val editLayer = lm.editLayer
            val debugLayer = OsmDataLayer(ds, layerName, null)
            lm.addLayer(debugLayer, false)
            if (editLayer != null) {
                lm.setActiveLayer(editLayer)
                try {
                    val layers = lm.layers
                    var newEditIdx = -1
                    for (i in layers.indices) {
                        if (layers[i] === editLayer) {
                            newEditIdx = i
                            break
                        }
                    }
                    if (newEditIdx >= 0) {
                        val targetIdx = newEditIdx + 1
                        if (targetIdx <= layers.size) {
                            try {
                                lm.moveLayer(debugLayer, targetIdx)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            try {
                debugLayer.invalidate()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }
}
