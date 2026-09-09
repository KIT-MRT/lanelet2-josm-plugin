package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class DebugRoutingGraphTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun onceArgsNeverIncludeValidate() {
        val args = DebugRoutingGraph.routingArgs("/abs/in.osm", "/abs/out.osm", "vehicle")
        assertEquals(listOf("--once", "--participant", "vehicle", "/abs/in.osm", "/abs/out.osm"), args)
        assertFalse("--validate" in args)
        assertTrue(args[3].startsWith("/"), "production passes absolute paths")
    }

    @Test
    fun outputPathLivesInScratchDirNotBesideTheMap() {
        val scratch = File("/tmp/josm-lanelet2/routing")
        val out = DebugRoutingGraph.routingOutputPath("/maps/mapping_example.osm", "bicycle", scratch)
        assertEquals(File(scratch, "routing_bicycle_mapping_example.osm").path, out)
        assertFalse(out.startsWith("/maps/"))
    }

    @Test
    fun smallExtractPathLivesInScratchDirNotBesideTheMap() {
        val scratch = File("/tmp/josm-lanelet2/routing")
        val p = DebugRoutingGraph.smallExtractPath("/maps/foo.osm", scratch)
        assertEquals(File(scratch, "foo_small.osm").path, p)
        assertFalse(p.startsWith("/maps/"))
    }

    @Test
    fun layerPrefixDistinguishesSmall() {
        assertEquals("Routing Graph (vehicle):", DebugRoutingGraph.routingLayerPrefix("vehicle", false))
        assertEquals("Routing Graph (vehicle)_small:", DebugRoutingGraph.routingLayerPrefix("vehicle", true))
    }

    @Test
    fun xmlEscapeOrderAmpersandFirst() {
        assertEquals("&amp;lt;", DebugRoutingGraph.escXml("&lt;"))
        assertEquals("&apos;&quot;", DebugRoutingGraph.escXml("'\""))
    }

    @Test
    fun fmtCoordUsesNineDecimals() {
        assertEquals("49.000000000", DebugRoutingGraph.fmtCoord(49.0))
    }

    @Test
    fun serializeIncludesCoordinatesAndSortedTags() {
        OsmFixtures.ensurePrefs()
        val n = OsmFixtures.node(8.0, 49.0)
        n.put("b", "2")
        n.put("a", "1")
        val xml = DebugRoutingGraph.serializePrimitives(mapOf(n.uniqueId to n))
        assertTrue(n.coor != null)
        assertTrue(xml.contains("generator='lanelet2_small_routing'"), xml)
        assertTrue(xml.contains("lat='49.000000000'"), xml)
        assertTrue(xml.contains("lon='8.000000000'"), xml)
        assertTrue(xml.indexOf("k='a'") < xml.indexOf("k='b'"), xml)
    }

    @Test
    fun padViewBBoxExpandsBy100m() {
        val bbox = DebugRoutingGraph.padViewBBox(49.0, 49.0, 8.0, 8.0)
        assertTrue(bbox.maxLat > 49.0)
        assertTrue(bbox.minLat < 49.0)
        assertTrue(bbox.maxLon > 8.0)
        assertTrue(bbox.minLon < 8.0)
    }

    @Test
    fun positiveIdsAlwaysPassesDashI(@TempDir dir: Path) {
        val f = dir.resolve("map.osm").toFile()
        f.writeText("<osm/>")
        assertEquals(listOf("-i", f.absolutePath), MakePositiveIds.inplaceArgs(f.absolutePath))
        assertTrue(MakePositiveIds.inplaceArgs(f.absolutePath)[1].startsWith("/"))
    }

    @Test
    fun backendRunnerReceivesOnceParticipantAndAbsolutePaths(@TempDir dir: Path) {
        val captured = mutableListOf<List<String>>()
        File(dir.toFile(), "server_create_debug_routing_graph_dataset.py").writeText("print('x')\n")
        val spy = BackendRunner(
            python = { "python3" },
            backendsDir = { dir.toFile() },
            envScript = { null },
            execute = { cmd, _ ->
                captured.add(cmd)
                BackendResult.ok()
            },
        )
        val result = spy.run(
            "server_create_debug_routing_graph_dataset",
            DebugRoutingGraph.routingArgs("/abs/in.osm", "/abs/out.osm", "vehicle"),
        )
        assertTrue(result.success)
        val cmd = captured.single()
        assertTrue("--once" in cmd)
        assertTrue("--participant" in cmd)
        assertFalse("--validate" in cmd)
        assertTrue("/abs/in.osm" in cmd)
    }
}
