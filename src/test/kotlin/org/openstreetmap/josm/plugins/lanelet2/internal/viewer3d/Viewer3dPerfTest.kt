package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.BBox
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.progress.NullProgressMonitor
import org.openstreetmap.josm.io.OsmReader
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File

/**
 * Timings of the JOSM half of the 3D bridge on a real map. Skipped unless a
 * map is given; needs a big heap:
 *
 *     LL2_PERF_MAP=.../karlsruhe_city/lanelet2_map.osm \
 *       ./gradlew test --tests '*Viewer3dPerfTest*' -PtestHeap=6g -i
 */
class Viewer3dPerfTest {
    private fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val out = block()
        println("[viewer3d-perf] %-44s %8.1f ms".format(label, (System.nanoTime() - t0) / 1e6))
        return out
    }

    @Test
    fun streamingCostsOnARealMap() {
        val path = System.getProperty("lanelet2.perfMap") ?: System.getenv("LL2_PERF_MAP")
        assumeTrue(path != null && File(path).isFile, "set LL2_PERF_MAP to an .osm file")
        Config.setPreferencesInstance(MemoryPreferences())
        val ds: DataSet = timed("parse .osm into a DataSet") {
            File(path!!).inputStream().buffered().use { OsmReader.parseDataSet(it, NullProgressMonitor.INSTANCE) }
        }
        println("[viewer3d-perf] ${ds.ways.size} ways, ${ds.nodes.size} nodes, ${ds.relations.size} relations")

        val engine = Viewer3dDiffEngine() // culling off: the whole map streams
        val center: Pair<Double, Double>? = null
        repeat(2) { round ->
            val ways = timed("[$round] snapshot every way (ds.ways.map)") { ds.ways.map { it.toSnapshot() } }
            engine.resetForLayerChange()
            val snapshot = timed("[$round] computeFull") { engine.computeFull(ways, center)!! }
            val json = timed("[$round] encode snapshot JSON") { Viewer3dJson.encode(snapshot) }
            println("[viewer3d-perf] snapshot JSON ${"%.1f".format(json.length / 1e6)} MB")
        }

        // One edit: a node moves; its ways are dirty. The old path snapshotted
        // every way to build the lookup map; the hook now asks for the dirty ones.
        val node: Node = ds.ways.first { it.nodesCount > 2 }.getNode(1)
        repeat(2) { round ->
            for (r in node.referrers) if (r is Way) engine.markWayDirty(r.uniqueId)
            timed("[$round] one edit, old: snapshot all + incremental") {
                val byId = ds.ways.map { it.toSnapshot() }.associateBy { it.uniqueId }
                engine.computeIncremental(byId, center)
            }
            for (r in node.referrers) if (r is Way) engine.markWayDirty(r.uniqueId)
            timed("[$round] one edit, new: dirty ways only") {
                engine.computeIncremental({ id -> ds.wayById(id) }, center)
            }
        }

        // Culling: a 300 m square around the anchor from the spatial index.
        val a = engine.anchor!!
        val cullBounds = Viewer3dFeatures.cullBoundsEnu(a.lat to a.lon, a, 300.0)!!
        val box = Viewer3dFeatures.latLonBoxOf(cullBounds, a)
        repeat(2) { round ->
            val found = timed("[$round] cull candidates via searchWays") {
                ds.searchWays(BBox(box[0], box[1], box[2], box[3])).map { it.toSnapshot() }
            }
            println("[viewer3d-perf] cull candidates: ${found.size}")
        }
    }
}
