package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                            pts = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0),
                            nodeIds = longArrayOf(1, 2),
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

    /**
     * The test above writes raw lines to the server, so it stayed green while
     * the real client could not deliver anything. This one pushes through
     * [Viewer3dSocketClient] with the hook's own resync-on-connect handler.
     */
    @Test
    fun socketClientDeliversSnapshotToShippedServer(@TempDir dir: Path) {
        val python3 = Viewer3dServerProcess.locatePython3()
        assumeTrue(python3 != null, "no python3 available")
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val serverScript = File(root, "viewer3d/server.py")
        val httpPort = 48865
        val ingestPort = 48866
        var proc: Process? = null
        var client: Viewer3dSocketClient? = null
        try {
            proc = ProcessBuilder(
                python3,
                serverScript.absolutePath,
                "--host", "127.0.0.1",
                "--http-port", httpPort.toString(),
                "--ingest-port", ingestPort.toString(),
                "--icons-dir", File(root, "style_images").absolutePath,
                "--no-browser",
            )
                .directory(serverScript.parentFile)
                .redirectErrorStream(true)
                .start()
            assumeTrue(waitForHealth(httpPort, 5000), "server did not become healthy")

            val snapshot = OutboundMessage.Snapshot(
                Anchor(49.0, 8.4),
                listOf(
                    ViewerFeature(
                        id = "way/7",
                        kind = "line",
                        tags = emptyMap(),
                        pts = doubleArrayOf(0.0, 0.0, 0.0, 5.0, 0.0, 0.0),
                        nodeIds = longArrayOf(1, 2),
                    ),
                ),
            )
            lateinit var c: Viewer3dSocketClient
            c = Viewer3dSocketClient(
                host = { "127.0.0.1" },
                port = { ingestPort },
                onCommand = {},
                onConnected = {
                    // Exactly what Viewer3dHook.requestResync does on connect.
                    c.requestResync()
                    c.enqueue(snapshot)
                },
            )
            client = c
            c.start()

            val deadline = System.currentTimeMillis() + 10_000
            var state = ""
            while (System.currentTimeMillis() < deadline) {
                state = httpGet("http://127.0.0.1:$httpPort/state")
                if (state.contains("way/7")) break
                Thread.sleep(100)
            }
            assertTrue(state.contains("way/7"), "server never received the snapshot: $state")
        } finally {
            client?.stop()
            proc?.destroyForcibly()
            proc?.waitFor(2, TimeUnit.SECONDS)
        }
    }

    /**
     * A server left over from an earlier session keeps the port and answers
     * `/healthz`, so the plugin reports "running" and refuses to start a new
     * one — but it has no handle to kill a process it did not spawn, which left
     * Stop unable to do anything. `POST /shutdown` is the way out.
     */
    @Test
    fun aServerThisJosmDidNotSpawnCanStillBeStopped(@TempDir dir: Path) {
        val python3 = Viewer3dServerProcess.locatePython3()
        assumeTrue(python3 != null, "no python3 available")
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val serverScript = File(root, "viewer3d/server.py")
        val httpPort = 48965
        var proc: Process? = null
        try {
            proc = ProcessBuilder(
                python3,
                serverScript.absolutePath,
                "--host", "127.0.0.1",
                "--http-port", httpPort.toString(),
                "--ingest-port", "48966",
                "--icons-dir", File(root, "style_images").absolutePath,
                "--no-browser",
            )
                .directory(serverScript.parentFile)
                .redirectErrorStream(true)
                .start()
            assumeTrue(waitForHealth(httpPort, 5000), "server did not become healthy")

            // A fresh instance, as if the previous JOSM had exited: it owns no
            // Process for this server, exactly like the stranded case.
            val orphanView = Viewer3dServerProcess()
            assertTrue(orphanView.isRunning("127.0.0.1", httpPort))

            val (ok, msg) = orphanView.stop("127.0.0.1", httpPort)

            assertTrue(ok, msg)
            assertFalse(Viewer3dServerProcess.probeHealth("127.0.0.1", httpPort), "port still served")
            assertTrue(proc.waitFor(5, TimeUnit.SECONDS), "server process did not exit")
        } finally {
            proc?.destroyForcibly()
            proc?.waitFor(2, TimeUnit.SECONDS)
        }
    }

    /**
     * The leftover we actually hit: /healthz 200, `/` is 404 because extract
     * deleted `static/` from under the old process. Start must recycle it,
     * not report "already running".
     */
    @Test
    fun startRecyclesALeftoverThatCannotServeThePage(@TempDir dir: Path) {
        val python3 = Viewer3dServerProcess.locatePython3()
        assumeTrue(python3 != null, "no python3 available")
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val viewer = File(root, "viewer3d")
        val index = File(viewer, "static/index.html")
        assertTrue(index.isFile)
        val httpPort = 49065
        val ingestPort = 49066
        var stale: Process? = null
        var managed: Viewer3dServerProcess? = null
        try {
            stale = ProcessBuilder(
                python3,
                File(viewer, "server.py").absolutePath,
                "--host", "127.0.0.1",
                "--http-port", httpPort.toString(),
                "--ingest-port", ingestPort.toString(),
                "--icons-dir", File(root, "style_images").absolutePath,
                "--no-browser",
            )
                .directory(viewer)
                .redirectErrorStream(true)
                .start()
            assumeTrue(waitForHealth(httpPort, 5000), "stale server did not become healthy")
            index.delete()
            assertTrue(Viewer3dServerProcess.probeHealth("127.0.0.1", httpPort))
            assertFalse(Viewer3dServerProcess.probeUi("127.0.0.1", httpPort), "page should be gone")

            managed = Viewer3dServerProcess(
                python = { python3 },
                extract = { Viewer3dStore.ensureExtracted(root) },
            )
            val (ok, msg) = managed.start("127.0.0.1", ingestPort, httpPort, openBrowser = false)
            assertTrue(ok, msg)
            assertTrue(Viewer3dServerProcess.probeUi("127.0.0.1", httpPort), "recycled server still has no page")
            assertTrue(index.isFile, "re-extract should have restored index.html")
        } finally {
            managed?.stop("127.0.0.1", httpPort)
            stale?.destroyForcibly()
            stale?.waitFor(2, TimeUnit.SECONDS)
        }
    }

    /**
     * A leftover from another plugin build still serves `/`, so the old
     * "adopt if the page loads" rule kept it — with its own routes and files.
     * It must be recycled when its `/healthz` build differs from the jar.
     */
    @Test
    fun startRecyclesAServerFromAnotherBuild(@TempDir dir: Path) {
        val python3 = Viewer3dServerProcess.locatePython3()
        assumeTrue(python3 != null, "no python3 available")
        val root = dir.resolve("lanelet2").toFile()
        Viewer3dStore.extract(root)
        val viewer = File(root, "viewer3d")
        val versionFile = File(viewer, Viewer3dResources.VERSION_FILE)
        val shipped = versionFile.readText().trim()
        versionFile.writeText("0000other0build")
        val httpPort = 49075
        val ingestPort = 49076
        var stale: Process? = null
        var managed: Viewer3dServerProcess? = null
        try {
            stale = ProcessBuilder(
                python3,
                File(viewer, "server.py").absolutePath,
                "--host", "127.0.0.1",
                "--http-port", httpPort.toString(),
                "--ingest-port", ingestPort.toString(),
                "--icons-dir", File(root, "style_images").absolutePath,
                "--no-browser",
            )
                .directory(viewer)
                .redirectErrorStream(true)
                .start()
            assumeTrue(waitForHealth(httpPort, 5000), "stale server did not become healthy")
            assertTrue(Viewer3dServerProcess.probeUi("127.0.0.1", httpPort), "leftover should serve the page")
            assertEquals("0000other0build", Viewer3dServerProcess.probeBuild("127.0.0.1", httpPort))

            managed = Viewer3dServerProcess(
                python = { python3 },
                extract = { Viewer3dStore.ensureExtracted(root) },
                expectedBuild = { shipped },
            )
            assertFalse(managed.isCurrentBuild("127.0.0.1", httpPort))
            val (ok, msg) = managed.start("127.0.0.1", ingestPort, httpPort, openBrowser = false)
            assertTrue(ok, msg)
            assertEquals(shipped, Viewer3dServerProcess.probeBuild("127.0.0.1", httpPort))
            assertTrue(managed.isCurrentBuild("127.0.0.1", httpPort))

            val (again, againMsg) = managed.start("127.0.0.1", ingestPort, httpPort, openBrowser = false)
            assertTrue(again && againMsg.contains("already running"), againMsg)
        } finally {
            managed?.stop("127.0.0.1", httpPort)
            stale?.destroyForcibly()
            stale?.waitFor(2, TimeUnit.SECONDS)
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
