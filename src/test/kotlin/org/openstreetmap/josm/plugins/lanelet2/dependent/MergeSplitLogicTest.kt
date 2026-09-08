package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.sidecar.HealthReport
import org.openstreetmap.josm.plugins.lanelet2.sidecar.RecordingPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.Sidecar
import org.openstreetmap.josm.plugins.lanelet2.sidecar.SidecarProblem
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class MergeSplitLogicTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        Sidecar.resetOverrides()
    }

    @Test
    fun defaultBasenameUsesDirectoryName() {
        assertEquals("merge_dir.osm", MergeOsmFiles.defaultOutputBasename(File("/tmp/merge_dir"), emptyList(), null))
        assertEquals("base.osm", MergeOsmFiles.defaultOutputBasename(null, emptyList(), File("/tmp/out/base.osm")))
        assertEquals("merged.osm", MergeOsmFiles.defaultOutputBasename(null, emptyList(), null))
    }

    @Test
    fun excludeBaseDropsSameFileViaDifferentPaths(@TempDir dir: Path) {
        val a = dir.resolve("a.osm").toFile().apply { writeText("<osm/>") }
        val b = dir.resolve("b.osm").toFile().apply { writeText("<osm/>") }
        val kept = MergeOsmFiles.excludeBase(listOf(a, b, File(a.path)), a)
        assertEquals(listOf(b), kept)
    }

    @Test
    fun mergeArgsPassAbsolutePathsAndFilesFlag() {
        val args = MergeOsmFiles.mergeArgs(
            "/abs/out.osm",
            inputDir = null,
            files = listOf("/abs/a.osm", "/abs/b.osm"),
            base = null,
        )
        assertEquals(listOf("--output", "/abs/out.osm", "--files", "/abs/a.osm", "/abs/b.osm"), args)
        val dirArgs = MergeOsmFiles.mergeArgs("/abs/out.osm", "/abs/in", null, "/abs/base.osm")
        assertEquals(
            listOf("--output", "/abs/out.osm", "--base", "/abs/base.osm", "--input-dir", "/abs/in"),
            dirArgs,
        )
    }

    @Test
    fun hasFileOriginTagScansTextAndFailsOpen(@TempDir dir: Path) {
        val tagged = dir.resolve("t.osm").toFile()
        tagged.writeText("""<tag k="file_origin" v="/abs/a.osm"/>""")
        assertTrue(MergeOsmFiles.hasFileOriginTag(tagged))
        val plain = dir.resolve("p.osm").toFile()
        plain.writeText("<osm/>\n")
        assertFalse(MergeOsmFiles.hasFileOriginTag(plain))
        val missing = dir.resolve("missing.osm").toFile()
        assertTrue(MergeOsmFiles.hasFileOriginTag(missing), "unreadable files fail-open like the Jython")
    }

    @Test
    fun splitArgsIncludeDryRunFlag() {
        assertEquals(
            listOf("--merged-file", "/m.osm", "--output", "/out", "--output-mode", "staging"),
            SplitMergedOsmFile.splitArgs("/m.osm", "/out", "staging", dryRun = false),
        )
        assertTrue(SplitMergedOsmFile.splitArgs("/m.osm", "/out", "in-place", dryRun = true).contains("--dry-run"))
    }

    @Test
    fun parseDryRunPathsFromStdoutAndWouldWrite() {
        val log = """
            SPLIT_TARGET=/work/split_out/part_a.osm
            INFO Would write /work/a.osm (from origin)
            SPLIT_TARGET=/work/split_out/part_a.osm
            SPLIT_TARGET=/work/split_out/part_b.osm
        """.trimIndent()
        assertEquals(
            listOf("/work/split_out/part_a.osm", "/work/a.osm", "/work/split_out/part_b.osm"),
            SplitMergedOsmFile.parseDryRunPaths(log),
        )
    }

    @Test
    fun mergeAndSplitWarnAndDoNothingWhenSidecarUnhealthy() {
        Sidecar.healthOverride = HealthReport(
            SidecarProblem.INTERPRETER_MISSING,
            detail = "no python",
            python = "python3",
        )
        val ui = RecordingPrompts()
        MergeOsmFiles.run(ui)
        SplitMergedOsmFile.run(ui)
        MakePositiveIds.run(ui)
        DebugRoutingGraph.run(small = false, ui = ui)
        DebugRoutingGraph.run(small = true, ui = ui)
        assertEquals(5, ui.warnings.size)
        assertTrue(ui.warnings.all { it.first.contains("Set up Lanelet2 backends") })
    }

    @Test
    fun debounceSecondsUseIntegerDivisionLikePython2() {
        LaneletSettings.setRoutingAutoDebounceMs(4500)
        assertEquals(4, LaneletSettings.getRoutingAutoDebounceMs() / 1000)
        LaneletSettings.setRoutingAutoDebounceMs(4000)
        assertEquals(4, LaneletSettings.getRoutingAutoDebounceMs() / 1000)
    }
}
