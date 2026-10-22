package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File

class Viewer3dServerProcessTest {
    @Test
    fun startFailsGracefullyWhenPythonMissing() {
        val proc = Viewer3dServerProcess(
            python = { null },
            extract = {
                Viewer3dStore.ExtractPaths(
                    File("/tmp/no-viewer"),
                    File("/tmp/no-icons"),
                    File("/tmp/no-viewer/server.py"),
                )
            },
        )
        val (ok, msg) = proc.start("127.0.0.1", 48766, 48765)
        assertFalse(ok)
        assertTrue(msg.contains("python3"))
    }

    @Test
    fun allowedTileHostsAreReadLikeTheServerReadsThem() {
        assertEquals(listOf("srv1.mrt.kit.edu", "tiles.example.org:8443", "wms.example.org"),
            Viewer3dSettings.parseHosts(" SRV1.mrt.kit.edu, tiles.example.org:8443;wms.example.org srv1.mrt.kit.edu"))
        assertEquals(listOf("srv1.mrt.kit.edu"),
            Viewer3dSettings.parseHosts("https://srv1.mrt.kit.edu/geoserver/karlsruhe/ows?SERVICE=WMS"),
            "a pasted URL keeps its host")
        assertEquals(listOf("host:8080"), Viewer3dSettings.parseHosts("http://host:8080/wms"))
        assertEquals(emptyList<String>(), Viewer3dSettings.parseHosts("user@evil, -bad-, bad_host, host:99999x , \"\""))
        assertEquals(emptyList<String>(), Viewer3dSettings.parseHosts(null))
    }

    @Test
    fun allowedTileHostsAreStoredNormalised() {
        Config.setPreferencesInstance(MemoryPreferences())
        assertEquals(emptyList<String>(), Viewer3dSettings.getTileProxyHosts(), "nothing is proxied by default")
        Viewer3dSettings.setTileProxyHosts("SRV1.mrt.kit.edu  tiles.example.org:8443")
        assertEquals(listOf("srv1.mrt.kit.edu", "tiles.example.org:8443"), Viewer3dSettings.getTileProxyHosts())
    }

    @Test
    fun healthProbeReturnsFalseForClosedPort() {
        assertFalse(Viewer3dServerProcess.probeHealth("127.0.0.1", 47999))
    }

    @Test
    fun uiProbeReturnsFalseForClosedPort() {
        assertFalse(Viewer3dServerProcess.probeUi("127.0.0.1", 47999))
    }

    @Test
    fun startRefusesAnExtractThatHasNoIndexHtml() {
        val proc = Viewer3dServerProcess(
            python = { "/usr/bin/python3" },
            extract = {
                Viewer3dStore.ExtractPaths(
                    File("/tmp/no-viewer-static"),
                    File("/tmp/no-icons"),
                    File("/tmp/no-viewer-static/server.py").also {
                        it.parentFile.mkdirs()
                        it.writeText("# dummy\n")
                    },
                )
            },
        )
        val (ok, msg) = proc.start("127.0.0.1", 48766, 48765)
        assertFalse(ok)
        assertTrue(msg.contains("index.html"), msg)
    }
}
