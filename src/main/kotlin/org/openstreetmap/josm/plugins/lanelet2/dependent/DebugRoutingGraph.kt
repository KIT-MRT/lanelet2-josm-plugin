package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.actions.SaveAction
import org.openstreetmap.josm.data.osm.BBox
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.CheckLaneletBorders
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.hooks.RoutingRefreshHook
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendScripts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendStore
import org.openstreetmap.josm.plugins.lanelet2.sidecar.Sidecar
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.math.cos

/**
 * Generate Debug Routing Graph. Port of `ll2_debug_routing_graph.py`.
 *
 * Always invoked as `--once --participant <p> <input> <output>`. `--once`
 * skips `lanelet2_validate` even though the backend's `--validate` defaults
 * true; only the file-watch loop uses that flag. We never pass `--validate`.
 *
 * Unreachable leftover in the Jython `_tlog` (a `_get_tooling_root` body
 * sitting after `return now`) is not ported.
 */
object DebugRoutingGraph {
    const val TITLE = "Debug Routing Graph"
    const val SMALL_TITLE = "Debug Routing Graph (small)"

    /** Expand the current map view by this many metres on each side. */
    const val SMALL_VIEW_BUFFER_M = 100.0
    private const val METERS_PER_DEG_LAT = 111320.0

    fun routingOutputPath(
        inputPath: String,
        participant: String,
        scratchDir: File = BackendStore.defaultScratchDir(),
    ): String = File(scratchDir, "routing_${participant}_${File(inputPath).name}").path

    fun smallExtractPath(
        inputPath: String,
        scratchDir: File = BackendStore.defaultScratchDir(),
    ): String {
        val base = File(inputPath)
        val stem = base.nameWithoutExtension
        val ext = if (base.extension.isEmpty()) ".osm" else ".${base.extension}"
        return File(scratchDir, stem + "_small" + ext).path
    }

    fun routingLayerPrefix(participant: String, small: Boolean): String =
        if (small) "Routing Graph ($participant)_small:" else "Routing Graph ($participant):"

    fun routingLayerName(participant: String, outputPath: String, small: Boolean): String =
        "${routingLayerPrefix(participant, small)} ${File(outputPath).name}"

    /**
     * Plugin path: `--once --participant <p> <abs-input> <abs-output>`.
     * `--once` is what skips `lanelet2_validate`.
     */
    fun routingArgs(inputPath: String, outputPath: String, participant: String): List<String> =
        listOf("--once", "--participant", participant, inputPath, outputPath)

    fun runBackend(
        inputPath: String,
        outputPath: String,
        participant: String,
        runner: BackendRunner = Sidecar.runner(),
    ): BackendResult = runner.run(
        BackendScripts.DEBUG_ROUTING_GRAPH,
        routingArgs(inputPath, outputPath, participant),
    )

    fun padViewBBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): BBox {
        val centerLat = (minLat + maxLat) / 2.0
        val padLat = SMALL_VIEW_BUFFER_M / METERS_PER_DEG_LAT
        var cosLat = cos(Math.toRadians(centerLat))
        if (cosLat < 0.01) cosLat = 0.01
        val padLon = SMALL_VIEW_BUFFER_M / (METERS_PER_DEG_LAT * cosLat)
        return BBox(minLon - padLon, minLat - padLat, maxLon + padLon, maxLat + padLat)
    }

    fun isUsable(p: OsmPrimitive?): Boolean {
        if (p == null) return false
        return try {
            !p.isDeleted && !p.isIncomplete
        } catch (_: Exception) {
            false
        }
    }

    fun collectViewportPrimitives(data: DataSet, bbox: BBox): Map<Long, OsmPrimitive> {
        val included = LinkedHashMap<Long, OsmPrimitive>()
        fun add(p: OsmPrimitive?): Boolean {
            if (!isUsable(p)) return false
            val uid = p!!.uniqueId
            if (uid in included) return false
            included[uid] = p
            return true
        }
        try {
            for (n in data.searchNodes(bbox)) add(n)
        } catch (_: Exception) {
        }
        try {
            for (w in data.searchWays(bbox)) add(w)
        } catch (_: Exception) {
        }
        try {
            for (r in data.searchRelations(bbox)) add(r)
        } catch (_: Exception) {
        }
        val seed = included.values.toList()
        for (p in seed) {
            if (p is Relation) continue
            val refs = try {
                p.referrers
            } catch (_: Exception) {
                continue
            } ?: continue
            for (ref in refs) {
                if (ref is Relation) add(ref)
            }
        }
        var changed = true
        while (changed) {
            changed = false
            val current = included.values.toList()
            for (p in current) {
                when (p) {
                    is Relation -> {
                        val members = try {
                            p.members
                        } catch (_: Exception) {
                            continue
                        } ?: continue
                        for (m in members) {
                            val mem = try {
                                m.member
                            } catch (_: Exception) {
                                continue
                            }
                            if (add(mem)) changed = true
                        }
                    }
                    is Way -> {
                        val nodes = try {
                            p.nodes
                        } catch (_: Exception) {
                            continue
                        } ?: continue
                        for (n in nodes) {
                            if (add(n)) changed = true
                        }
                    }
                }
            }
        }
        return included
    }

    fun serializePrimitives(included: Map<Long, OsmPrimitive>): String {
        val nodes = ArrayList<Node>()
        val ways = ArrayList<Way>()
        val relations = ArrayList<Relation>()
        for (p in included.values) {
            when (p) {
                is Node -> nodes.add(p)
                is Way -> ways.add(p)
                is Relation -> relations.add(p)
            }
        }
        nodes.sortBy { it.uniqueId }
        ways.sortBy { it.uniqueId }
        relations.sortBy { it.uniqueId }
        val willWrite = HashSet<Long>()
        for (n in nodes) if (n.coor != null) willWrite.add(n.uniqueId)
        for (w in ways) willWrite.add(w.uniqueId)
        for (r in relations) willWrite.add(r.uniqueId)

        val lines = ArrayList<String>()
        lines.add("<?xml version='1.0' encoding='UTF-8'?>")
        lines.add("<osm version='0.6' generator='lanelet2_small_routing'>")
        for (n in nodes) {
            val c = n.coor ?: continue
            val uid = n.uniqueId
            val head = "  <node id='$uid' visible='true' version='1' lat='${fmtCoord(c.lat())}' lon='${fmtCoord(c.lon())}'"
            val keys = sortedKeys(n)
            if (keys.isEmpty()) {
                lines.add("$head />")
                continue
            }
            lines.add("$head>")
            for (k in keys) {
                lines.add("    <tag k='${escXml(k)}' v='${escXml(n.get(k))}' />")
            }
            lines.add("  </node>")
        }
        for (w in ways) {
            lines.add("  <way id='${w.uniqueId}' visible='true' version='1'>")
            val wnodes = try {
                w.nodes
            } catch (_: Exception) {
                null
            }
            if (wnodes != null) {
                for (nd in wnodes) {
                    if (!isUsable(nd)) continue
                    val ndId = nd.uniqueId
                    if (ndId !in willWrite) continue
                    lines.add("    <nd ref='$ndId' />")
                }
            }
            for (k in sortedKeys(w)) {
                lines.add("    <tag k='${escXml(k)}' v='${escXml(w.get(k))}' />")
            }
            lines.add("  </way>")
        }
        for (r in relations) {
            lines.add("  <relation id='${r.uniqueId}' visible='true' version='1'>")
            val members = try {
                r.members
            } catch (_: Exception) {
                null
            }
            if (members != null) {
                for (m in members) {
                    try {
                        val mem = m.member ?: continue
                        if (!isUsable(mem)) continue
                        val mid = mem.uniqueId
                        if (mid !in willWrite) continue
                        val role = m.role ?: ""
                        lines.add(
                            "    <member type='${memberTypeName(mem)}' ref='$mid' role='${escXml(role)}' />",
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            for (k in sortedKeys(r)) {
                lines.add("    <tag k='${escXml(k)}' v='${escXml(r.get(k))}' />")
            }
            lines.add("  </relation>")
        }
        lines.add("</osm>")
        return lines.joinToString("\n") + "\n"
    }

    fun escXml(s: String?): String {
        var out = s ?: ""
        out = out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        out = out.replace("'", "&apos;").replace("\"", "&quot;")
        out = out.replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;")
        return out
    }

    fun fmtCoord(v: Double): String = String.format(Locale.US, "%.9f", v)

    fun atomicWrite(path: File, text: String) {
        path.parentFile?.mkdirs()
        val tmp = File(path.path + ".tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        if (path.exists()) path.delete()
        if (!tmp.renameTo(path)) {
            tmp.copyTo(path, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Auto-trigger path from `run_update_auto`: no border check, silent
     * precondition failures. Viewport subset unless
     * [LaneletSettings.getRoutingHookFullMap] is on.
     */
    fun runUpdateAuto(onFinished: (() -> Unit)? = null, ui: UserPrompts = Dialogs): Boolean {
        val ctx = validateLayer(showErrors = false, ui = ui)
        if (ctx == null) {
            try {
                onFinished?.invoke()
            } catch (_: Exception) {
            }
            return false
        }
        val small = !LaneletSettings.getRoutingHookFullMap()
        return runUpdateCore(
            ctx.layer,
            ctx.inputPath,
            ctx.participant,
            showErrors = false,
            small = small,
            ui = ui,
            onFinished = onFinished,
        )
    }

    fun run(small: Boolean = false, ui: UserPrompts = Dialogs) {
        if (!Sidecar.ensureUsable(if (small) SMALL_TITLE else TITLE, ui)) return
        if (!small) {
            CheckLaneletBorders.run(showOkFeedback = false, ui = ui)
        }
        val ctx = validateLayer(showErrors = true, ui = ui) ?: return
        runUpdateCore(ctx.layer, ctx.inputPath, ctx.participant, showErrors = true, small = small, ui = ui)
    }

    internal data class LayerCtx(val layer: OsmDataLayer, val inputPath: String, val participant: String)

    internal fun validateLayer(showErrors: Boolean, ui: UserPrompts): LayerCtx? {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            if (showErrors) ui.warn("No editable layer selected or visible.", TITLE)
            return null
        }
        val assoc = layer.associatedFile
        if (assoc == null || !assoc.exists()) {
            if (showErrors) {
                ui.warn(
                    "The active layer has no associated file. Save the map to a .osm file first, " +
                        "or load a Lanelet2 map from file.",
                    TITLE,
                )
            }
            return null
        }
        val participant = LaneletSettings.getRoutingDefaultParticipant()
        return LayerCtx(layer, assoc.absolutePath, participant)
    }

    internal fun runUpdateCore(
        layer: OsmDataLayer,
        inputPath: String,
        participant: String,
        showErrors: Boolean,
        small: Boolean,
        ui: UserPrompts,
        onFinished: (() -> Unit)? = null,
    ): Boolean {
        val scriptInput: String
        if (small) {
            val (extractPath, err) = writeSmallExtract(layer, inputPath)
            if (extractPath == null) {
                if (showErrors) ui.warn(err ?: "Failed to extract viewport subset.", SMALL_TITLE)
                onFinished?.invoke()
                return false
            }
            scriptInput = extractPath
        } else {
            saveIfDirty(layer)
            scriptInput = inputPath
        }
        val outputPath = routingOutputPath(scriptInput, participant)
        val layerName = routingLayerName(participant, outputPath, small)
        Thread {
            val result = runBackend(scriptInput, outputPath, participant)
            SwingUtilities.invokeLater {
                try {
                    attachResult(ui, layer, participant, small, outputPath, layerName, result, showErrors)
                } finally {
                    try {
                        onFinished?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
        }.apply { isDaemon = true; name = "lanelet2-routing-graph" }.start()
        return true
    }

    internal fun writeSmallExtract(layer: OsmDataLayer, sourceInputPath: String): Pair<String?, String?> {
        val bbox = bufferedViewBBox() ?: return Pair(null, "No map view bounds available.")
        val data = try {
            layer.data
        } catch (_: Exception) {
            null
        } ?: return Pair(null, "Layer has no dataset.")
        val included = collectViewportPrimitives(data, bbox)
        if (included.isEmpty()) return Pair(null, "No OSM primitives in the current view.")
        val extractPath = smallExtractPath(sourceInputPath)
        return try {
            atomicWrite(File(extractPath), serializePrimitives(included))
            Pair(extractPath, null)
        } catch (e: Exception) {
            Pair(null, "Failed to write viewport extract: ${e.message}")
        }
    }

    private fun attachResult(
        ui: UserPrompts,
        editLayer: OsmDataLayer,
        participant: String,
        small: Boolean,
        outputPath: String,
        layerName: String,
        result: BackendResult,
        showErrors: Boolean,
    ) {
        if (!result.success) {
            if (showErrors) {
                ui.error("Routing graph generation failed:\n${result.error ?: "Unknown error"}", TITLE)
            }
            return
        }
        if (!File(outputPath).isFile) {
            if (showErrors) ui.error("Output file not found: $outputPath", TITLE)
            return
        }
        try {
            if (small && RoutingRefreshHook.hasPendingRerun()) {
                return
            }
            val ds = OsmIo.parse(File(outputPath)) ?: return
            addRoutingLayer(ds, layerName, editLayer, participant, small)
        } catch (e: Exception) {
            if (showErrors) ui.error("Failed to load routing graph: ${e.message}", TITLE)
        }
    }

    internal fun addRoutingLayer(
        ds: DataSet,
        layerName: String,
        editLayer: OsmDataLayer?,
        participant: String,
        small: Boolean,
    ) {
        try {
            val lm = MainApplication.getLayerManager()
            if (small) {
                val existing = findExistingSmallLayer(participant)
                if (existing != null && replaceSmallLayerDataset(existing, ds, layerName)) return
            }
            val prefix = routingLayerPrefix(participant, small)
            val toRemove = lm.layers.filter { it is OsmDataLayer && it.name?.startsWith(prefix) == true }
            for (l in toRemove) {
                try {
                    lm.removeLayer(l)
                } catch (_: Exception) {
                }
            }
            val debugLayer = OsmDataLayer(ds, layerName, null)
            lm.addLayer(debugLayer, false)
            if (editLayer != null) {
                val active = try {
                    lm.activeLayer
                } catch (_: Exception) {
                    null
                }
                if (active !== editLayer) {
                    try {
                        lm.activeLayer = editLayer
                    } catch (_: Exception) {
                    }
                }
                try {
                    val layers = lm.layers
                    var editIdx = -1
                    var debugIdx = -1
                    for (i in layers.indices) {
                        if (layers[i] === editLayer) editIdx = i
                        if (layers[i] === debugLayer) debugIdx = i
                    }
                    if (editIdx >= 0 && debugIdx >= 0) {
                        val targetIdx = editIdx.coerceAtMost(layers.size - 1)
                        if (targetIdx >= 0 && debugIdx != targetIdx) {
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
            try {
                MainApplication.getMap()?.mapView?.repaint()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: failed to attach routing layer: {0}", e.message)
        }
    }

    private fun findExistingSmallLayer(participant: String): OsmDataLayer? {
        val prefix = routingLayerPrefix(participant, true)
        return try {
            MainApplication.getLayerManager().layers
                .filterIsInstance<OsmDataLayer>()
                .firstOrNull { it.name?.startsWith(prefix) == true }
        } catch (_: Exception) {
            null
        }
    }

    private fun replaceSmallLayerDataset(layer: OsmDataLayer, ds: DataSet, layerName: String): Boolean {
        val target = try {
            layer.data
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            target.clear()
            target.mergeFrom(ds)
            try {
                layer.name = layerName
            } catch (_: Exception) {
            }
            try {
                layer.invalidate()
            } catch (_: Exception) {
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun bufferedViewBBox(): BBox? {
        return try {
            val mv = MainApplication.getMap()?.mapView ?: return null
            val b = mv.realBounds ?: return null
            padViewBBox(b.minLat, b.maxLat, b.minLon, b.maxLon)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveIfDirty(layer: OsmDataLayer) {
        try {
            if (layer.requiresSaveToFile()) {
                SaveAction.getInstance().doSave(layer, true)
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: save before routing graph failed: {0}", e.message)
        }
    }

    private fun sortedKeys(prim: OsmPrimitive): List<String> = try {
        prim.keySet().map { it.toString() }.sorted()
    } catch (_: Exception) {
        emptyList()
    }

    private fun memberTypeName(mem: OsmPrimitive): String = when (mem) {
        is Node -> "node"
        is Way -> "way"
        is Relation -> "relation"
        else -> "node"
    }

}
