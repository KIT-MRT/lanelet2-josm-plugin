package org.openstreetmap.josm.plugins.lanelet2.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.sidecar.RecordingPrompts
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FilterBrokenTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        OsmFixtures.ensurePrefs()
    }

    @Test
    fun originUsesEightDecimalsAndDotSeparator() {
        assertEquals("49.00315121,8.42461619", FilterBroken.formatOrigin(49.00315120868, 8.42461619058))
        assertEquals("-1.50000000,0.00000000", FilterBroken.formatOrigin(-1.5, 0.0))
    }

    @Test
    fun originIsFirstValidNodeNotBboxCentre() {
        val incomplete = Node(5L)
        val deleted = Node(LatLon(10.0, 10.0)).also { it.isDeleted = true }
        val invalid = Node(LatLon(91.0, 0.0))
        val firstValid = Node(LatLon(49.1, 8.4))
        val secondValid = Node(LatLon(50.0, 9.0))
        val origin = FilterBroken.projectionOriginFromNodes(
            listOf(incomplete, deleted, invalid, firstValid, secondValid),
        )
        assertEquals(FilterBroken.formatOrigin(49.1, 8.4), origin)
        assertNull(FilterBroken.projectionOriginFromNodes(listOf(incomplete, deleted, invalid)))
        assertNull(FilterBroken.projectionOriginFromDataSet(null))
    }

    @Test
    fun backupPathMatchesGitCommitScheme() {
        val file = File("/tmp/maps/city.osm")
        assertEquals(".city.osmbackup", JosmStateBackup.pathFor(file).name)
        assertEquals("/tmp/maps/.city.osmbackup", JosmStateBackup.pathFor(file).path)
    }

    @Test
    fun confirmMessageMentionsBackupAndNoUndo() {
        val msg = FilterBroken.confirmMessage(File("/tmp/map.osm"))
        assertTrue(msg.contains(".map.osmbackup"), msg)
        assertTrue(msg.contains("not an undo step"), msg)
        assertTrue(msg.contains("map.osm"), msg)
    }

    @Test
    fun lexicographicLastWinsOverNewerVersion(@TempDir dir: Path) {
        val root = dir.toFile()
        deployBinary(root, "1.2.10")
        deployBinary(root, "1.2.2")
        val found = FilterBroken.findLoadFilteredOsm(root)!!
        assertTrue(found.absolutePath.contains("${File.separator}1.2.2${File.separator}"), found.path)
        assertFalse(found.absolutePath.contains("${File.separator}1.2.10${File.separator}"), found.path)
    }

    @Test
    fun binGlobIsPreferredOverLibEvenIfLibSortsLater(@TempDir dir: Path) {
        val root = dir.toFile()
        deployBinary(root, "1.2.2")
        val lib = File(
            root,
            "lanelet2_deploy/full_deploy/host/lanelet2/1.2.2/Release/x86_64/lib/lanelet2_validation/load_filtered_osm",
        )
        lib.parentFile.mkdirs()
        lib.writeText("lib")
        val found = FilterBroken.findLoadFilteredOsm(root)!!
        assertTrue(found.absolutePath.contains("${File.separator}bin${File.separator}"), found.path)
    }

    @Test
    fun walkFallbackSkipsIncludeShareSitePackages(@TempDir dir: Path) {
        val root = dir.toFile()
        val deploy = File(root, "lanelet2_deploy")
        File(deploy, "include/load_filtered_osm").apply { parentFile.mkdirs(); writeText("skip") }
        File(deploy, "share/load_filtered_osm").apply { parentFile.mkdirs(); writeText("skip") }
        File(deploy, "site-packages/load_filtered_osm").apply { parentFile.mkdirs(); writeText("skip") }
        assertNull(FilterBroken.findLoadFilteredOsm(root))
        File(deploy, "odd/place/load_filtered_osm").apply { parentFile.mkdirs(); writeText("hit") }
        assertEquals("hit", FilterBroken.findLoadFilteredOsm(root)!!.readText())
    }

    @Test
    fun bashCommandOmitsOriginWhenNull(@TempDir dir: Path) {
        val env = File(dir.toFile(), "ll2_env.sh")
        val bin = File(dir.toFile(), "load_filtered_osm")
        val input = File(dir.toFile(), "in.osm")
        val with = FilterBroken.buildBashCommand(env, bin, input, input, "49.00000000,8.40000000")
        val without = FilterBroken.buildBashCommand(env, bin, input, input, null)
        assertTrue(with.contains("--origin"), with)
        assertFalse(without.contains("--origin"), without)
        assertTrue(without.startsWith("source "), without)
        assertTrue(without.contains(" && "), without)
    }

    @Test
    fun runWarnsWhenNoEditLayer() {
        val ui = RecordingPrompts()
        FilterBroken.run(ui)
        assertTrue(ui.warnings.any { it.first.contains("No editable layer") })
    }

    @Test
    fun toolingRootPrefersEnvOverWalk(@TempDir dir: Path) {
        val envRoot = dir.resolve("from-env").toFile().apply { mkdirs() }
        File(envRoot, "lanelet2_deploy").mkdirs()
        val start = dir.resolve("not-a-root").toFile().apply { mkdirs() }
        val resolved = FilterBroken.resolveToolingRoot(
            envRoot = envRoot.absolutePath,
            startDir = start,
            userHome = dir.resolve("home").toFile().absolutePath,
        )
        assertEquals(envRoot.absoluteFile, resolved)
        assertTrue(FilterBroken.isToolingRoot(envRoot))
    }

    @Test
    fun missingEnvScriptIsAStructuredFailure(@TempDir dir: Path) {
        val root = dir.toFile()
        File(root, "lanelet2_deploy").mkdirs()
        val result = FilterBroken.runLoadFilteredOsm(
            root,
            File(root, "in.osm"),
            File(root, "out.osm"),
            null,
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("ll2_env.sh not found"), result.error)
    }

    /**
     * Real Conan `load_filtered_osm`. Skipped when the deploy is absent so CI
     * without it still passes. On this machine both `ll2_env.sh` and the binary
     * exist; the result must be byte-identical to the Jython `bash -c` invocation.
     */
    @Test
    fun endToEndFilterMatchesJythonShellOut(@TempDir dir: Path) {
        val root = File("/ll2_tooling_root")
        assumeTrue(FilterBroken.ll2EnvSh(root).isFile, "ll2_env.sh not found")
        val binary = FilterBroken.findLoadFilteredOsm(root)
        assumeTrue(binary != null && binary.canExecute(), "load_filtered_osm not deployed")
        val src = File("/ll2_tooling_root/JOSM_LL2_Plugin/testdata/golden/inputs/merge_dir/part_a.osm")
        assumeTrue(src.isFile, "part_a.osm fixture missing")

        val work = dir.toFile()
        val input = File(work, "in.osm")
        Files.copy(src.toPath(), input.toPath())
        val origin = FilterBroken.formatOrigin(49.00315120868, 8.42461619058)
        val viaPort = File(work, "via_port.osm")
        val result = FilterBroken.runLoadFilteredOsm(root, input, viaPort, origin)
        assertTrue(result.success, result.error ?: result.stderr)
        assertTrue(viaPort.isFile)

        val viaJython = File(work, "via_jython.osm")
        val jythonCmd =
            "source '${FilterBroken.ll2EnvSh(root).absolutePath}' && " +
                "'${binary!!.absolutePath}' '${input.absolutePath}' '${viaJython.absolutePath}' " +
                "--origin '$origin'"
        assertEquals(
            jythonCmd,
            FilterBroken.buildBashCommand(
                FilterBroken.ll2EnvSh(root),
                binary,
                input,
                viaJython,
                origin,
            ),
        )
        val proc = ProcessBuilder("bash", "-c", jythonCmd)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "load_filtered_osm timed out")
        assertEquals(0, proc.exitValue(), output)
        assertEquals(viaJython.readBytes().toList(), viaPort.readBytes().toList())

        val inplace = File(work, "inplace.osm")
        Files.copy(src.toPath(), inplace.toPath())
        val backup = JosmStateBackup.copy(inplace)
        assertEquals(src.readBytes().toList(), backup.readBytes().toList())
        val inplaceResult = FilterBroken.runLoadFilteredOsm(root, inplace, inplace, origin)
        assertTrue(inplaceResult.success, inplaceResult.error ?: inplaceResult.stderr)
        assertEquals(src.readBytes().toList(), backup.readBytes().toList(), "backup must stay the pre-filter bytes")
        assertEquals(viaPort.readBytes().toList(), inplace.readBytes().toList())
    }

    private fun deployBinary(toolingRoot: File, version: String) {
        val bin = File(
            toolingRoot,
            "lanelet2_deploy/full_deploy/host/lanelet2/$version/Release/x86_64/bin/load_filtered_osm",
        )
        bin.parentFile.mkdirs()
        bin.writeText(version)
    }
}
