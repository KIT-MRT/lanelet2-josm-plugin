package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
