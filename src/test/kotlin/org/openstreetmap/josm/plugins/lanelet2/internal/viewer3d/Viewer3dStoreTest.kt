package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class Viewer3dStoreTest {
    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun shippedVersionIsDeterministic() {
        val a = Viewer3dStore.shippedVersion()
        val b = Viewer3dStore.shippedVersion()
        assertEquals(64, a.length)
        assertEquals(a, b)
    }

    @Test
    fun extractWritesViewerServerAndIcons(@TempDir dir: Path) {
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val viewer = File(root, "viewer3d")
        val icons = File(root, "style_images")
        assertTrue(File(viewer, "server.py").isFile)
        assertTrue(File(viewer, "static/app.js").isFile)
        assertTrue(File(viewer, Viewer3dResources.VERSION_FILE).isFile)
        assertTrue(icons.isDirectory)
        assertTrue(icons.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun secondEnsureDoesNotRewriteWhenVersionMatches(@TempDir dir: Path) {
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.ensureExtracted(root)
        val marker = File(root, "viewer3d/server.py")
        marker.writeText("# touched\n")
        Viewer3dStore.ensureExtracted(root)
        assertEquals("# touched\n", marker.readText())
    }

    @Test
    fun reextractsWhenVersionFileChanges(@TempDir dir: Path) {
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.ensureExtracted(root)
        File(root, "viewer3d/${Viewer3dResources.VERSION_FILE}").writeText("stale")
        File(root, "viewer3d/server.py").writeText("# stale\n")
        Viewer3dStore.ensureExtracted(root)
        assertTrue(!File(root, "viewer3d/server.py").readText().startsWith("# stale"))
    }
}
