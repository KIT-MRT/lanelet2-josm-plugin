package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class BackendStoreTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun venvDirFollowsXdgDataHome() {
        val dir = BackendStore.defaultVenvDir(xdgDataHome = "/xdg/data", userHome = "/home/u")
        assertEquals(File("/xdg/data/josm-lanelet2/venv"), dir)
    }

    @Test
    fun venvDirFallsBackToLocalShareUnderHome() {
        val dir = BackendStore.defaultVenvDir(xdgDataHome = null, userHome = "/home/u")
        assertEquals(File("/home/u/.local/share/josm-lanelet2/venv"), dir)
    }

    @Test
    fun venvDirIgnoresRelativeXdgDataHomePerSpec() {
        val dir = BackendStore.defaultVenvDir(xdgDataHome = "relative/dir", userHome = "/home/u")
        assertEquals(File("/home/u/.local/share/josm-lanelet2/venv"), dir)
        val blank = BackendStore.defaultVenvDir(xdgDataHome = "  ", userHome = "/home/u")
        assertEquals(File("/home/u/.local/share/josm-lanelet2/venv"), blank)
    }

    /**
     * Regression: the venv used to default under JOSM's user data directory,
     * which `runJosm` redirects into `build/.josm`. Gradle then failed to
     * traverse `venv/bin/python` and `clean` deleted the pip install.
     */
    @Test
    fun venvDirIsOutsideJosmUserDataAndBuildDir() {
        val venv = BackendStore.defaultVenvDir().absolutePath
        val extract = BackendStore.defaultExtractDir().absolutePath
        assertFalse(venv.startsWith(extract), "venv must not live under the scripts dir")
        assertFalse(
            venv.contains("${File.separator}build${File.separator}"),
            "venv must not live inside a Gradle build directory: $venv",
        )
        assertFalse(venv.contains(".josm"), "venv must not live in JOSM user data: $venv")
    }

    @Test
    fun shippedVersionIsDeterministic() {
        val a = BackendStore.shippedVersion()
        val b = BackendStore.shippedVersion()
        assertEquals(64, a.length, "SHA-256 hex")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun extractWritesVersionAndScripts(@TempDir dir: Path) {
        val dest = dir.resolve("backends").toFile()
        BackendStore.ensureExtracted(dest)
        assertTrue(File(dest, BackendScripts.VERSION_FILE).isFile)
        assertEquals(BackendStore.shippedVersion(), File(dest, BackendScripts.VERSION_FILE).readText().trim())
        for (name in BackendScripts.SHIPPED_FILES) {
            assertTrue(File(dest, name).isFile, name)
        }
        assertTrue(File(dest, "positive_ids.py").readText().contains("make_positive"))
        assertTrue(File(dest, "merge_osm_files.py").readText().contains("file_origin"))
    }

    @Test
    fun secondEnsureDoesNotRewriteWhenVersionMatches(@TempDir dir: Path) {
        val dest = dir.resolve("backends").toFile()
        BackendStore.ensureExtracted(dest)
        val marker = File(dest, "positive_ids.py")
        val original = marker.readBytes()
        marker.writeText("# touched\n")
        BackendStore.ensureExtracted(dest)
        assertEquals("# touched\n", marker.readText(), "must not re-extract on every call")
        marker.writeBytes(original)
    }

    @Test
    fun reextractsWhenVersionFileChanges(@TempDir dir: Path) {
        val dest = dir.resolve("backends").toFile()
        BackendStore.ensureExtracted(dest)
        File(dest, BackendScripts.VERSION_FILE).writeText("stale-hash")
        File(dest, "positive_ids.py").writeText("# stale\n")
        BackendStore.ensureExtracted(dest)
        assertNotEquals("# stale\n", File(dest, "positive_ids.py").readText())
        assertEquals(BackendStore.shippedVersion(), File(dest, BackendScripts.VERSION_FILE).readText().trim())
    }

    @Test
    fun reextractsWhenAShippedFileIsMissing(@TempDir dir: Path) {
        val dest = dir.resolve("backends").toFile()
        BackendStore.ensureExtracted(dest)
        assertTrue(File(dest, "merge_anchor_utils.py").delete())
        BackendStore.ensureExtracted(dest)
        assertTrue(File(dest, "merge_anchor_utils.py").isFile)
    }

    @Test
    fun resolveScriptFindsPyByBareName(@TempDir dir: Path) {
        val dest = dir.resolve("backends").toFile()
        BackendStore.ensureExtracted(dest)
        val script = BackendStore.resolveScript("positive_ids", dest)
        assertTrue(script != null && script.isFile)
        assertEquals(null, BackendStore.resolveScript("no_such_backend", dest))
    }
}
