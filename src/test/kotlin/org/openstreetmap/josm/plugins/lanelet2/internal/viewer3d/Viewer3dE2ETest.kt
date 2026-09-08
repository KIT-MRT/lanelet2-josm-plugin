package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class Viewer3dE2ETest {
    @Test
    fun shippedServerAcceptsSnapshotAndReflectsState(@TempDir dir: Path) {
        val python3 = Viewer3dServerProcess.locatePython3()
        assumeTrue(python3 != null, "no python3 available")
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val serverScript = File(root, "viewer3d/server.py")
        val iconsDir = File(root, "style_images")
        val httpPort = 48765
        val ingestPort = 48766
        var proc: Process? = null
        try {
            proc = ProcessBuilder(
                python3,
                serverScript.absolutePath,
                "--host", "127.0.0.1",
                "--http-port", httpPort.toString(),
                "--ingest-port", ingestPort.toString(),
                "--icons-dir", iconsDir.absolutePath,
                "--no-browser",
            )
                .directory(serverScript.parentFile)
                .redirectErrorStream(true)
                .start()
            assumeTrue(waitForHealth(httpPort, 5000), "server did not become healthy")
            val snapshot = Viewer3dJson.encode(
                OutboundMessage.Snapshot(
                    Anchor(49.0, 8.4),
                    listOf(
                        ViewerFeature(
                            id = "way/42",
                            kind = "line",
                            tags = emptyMap(),
                            points = listOf(listOf(0.0, 0.0, 0.0), listOf(10.0, 0.0, 0.0)),
                            nodes = listOf("node/1", "node/2"),
                        ),
                    ),
                ),
            ) + "\n"
            sendIngest("127.0.0.1", ingestPort, snapshot)
            val state = httpGet("http://127.0.0.1:$httpPort/state")
            assertTrue(state.contains("way/42"), state)
            assertTrue(state.contains("\"anchor\""))
        } finally {
            proc?.destroyForcibly()
            proc?.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private fun waitForHealth(httpPort: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Viewer3dServerProcess.probeHealth("127.0.0.1", httpPort)) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun sendIngest(host: String, port: Int, line: String) {
        Socket(Proxy.NO_PROXY).use { sock ->
            sock.connect(InetSocketAddress(host, port), 3000)
            OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8).use { out ->
                out.write(line)
                out.flush()
            }
            Thread.sleep(200)
        }
    }

    private fun httpGet(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        assertEquals(200, conn.responseCode)
        return conn.inputStream.bufferedReader().readText()
    }
}
