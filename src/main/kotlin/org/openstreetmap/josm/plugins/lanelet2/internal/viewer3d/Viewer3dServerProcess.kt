package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.tools.Logging
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the extracted `server.py` process; probes `GET /healthz` for liveness.
 *
 * A running server is adopted only when its `/healthz` build id matches
 * [expectedBuild] (the content hash of the viewer in this jar). A server from
 * another plugin build keeps its own routes and may serve another extract, so
 * adopting it would run stale browser code against this plugin.
 */
class Viewer3dServerProcess(
    private val python: () -> String? = { locatePython3() },
    private val extract: () -> Viewer3dStore.ExtractPaths = { Viewer3dStore.ensureExtracted() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val expectedBuild: () -> String? = {
        try {
            Viewer3dStore.shippedVersion()
        } catch (e: Exception) {
            Logging.warn(e)
            null
        }
    },
) {
    @Volatile
    private var process: Process? = null

    fun isRunning(host: String, httpPort: Int): Boolean = probeHealth(host, httpPort)

    fun isServingUi(host: String, httpPort: Int): Boolean = probeUi(host, httpPort)

    /** True when a server answers and reports the build this jar ships. */
    fun isCurrentBuild(host: String, httpPort: Int): Boolean {
        val want = expectedBuild() ?: return true
        return probeBuild(host, httpPort) == want
    }

    fun statusText(host: String, httpPort: Int): String =
        if (isServingUi(host, httpPort)) {
            if (isCurrentBuild(host, httpPort)) {
                "Running at http://$host:$httpPort/"
            } else {
                "Running at http://$host:$httpPort/ from another plugin build; Start restarts it"
            }
        } else if (isRunning(host, httpPort)) {
            "Responding on http://$host:$httpPort/ but the page is missing (stale extract)"
        } else {
            "Not running"
        }

    fun start(
        host: String,
        ingestPort: Int,
        httpPort: Int,
        profile: Boolean = false,
        openBrowser: Boolean = false,
    ): Pair<Boolean, String> {
        val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
        if (isServingUi(h, httpPort)) {
            if (isCurrentBuild(h, httpPort)) return true to "Viewer server is already running."
            // Serves a page, but from another plugin build (or one older than
            // the build handshake): recycle rather than adopt stale code.
            stop(h, httpPort)
        } else if (isRunning(h, httpPort)) {
            // A leftover answers /healthz (so the GUI says "running") but
            // cannot serve index.html — usually because extract deleted
            // viewer3d/ from under an older process. Adopt is wrong; recycle.
            stop(h, httpPort)
        }
        val py = python()
        if (py == null) {
            return false to "python3 is not available on PATH.\nInstall Python 3.7+ to use the live 3D viewer."
        }
        val paths = try {
            extract()
        } catch (e: Exception) {
            Logging.error(e)
            return false to "Failed to extract 3D viewer files:\n${e.message}"
        }
        if (!paths.serverScript.isFile) {
            return false to "server.py not found at:\n${paths.serverScript.absolutePath}"
        }
        val indexHtml = File(paths.viewerDir, "static/index.html")
        if (!indexHtml.isFile) {
            return false to "Extracted viewer is missing static/index.html at:\n${indexHtml.absolutePath}"
        }
        val cmd = mutableListOf(
            py,
            paths.serverScript.absolutePath,
            "--host", h,
            "--http-port", httpPort.toString(),
            "--ingest-port", ingestPort.toString(),
            "--icons-dir", paths.iconsDir.absolutePath,
        )
        if (profile) cmd.add("--profile")
        if (!openBrowser) cmd.add("--no-browser")
        return try {
            val proc = ProcessBuilder(cmd)
                .directory(paths.viewerDir)
                .redirectErrorStream(true)
                .start()
            process = proc
            repeat(20) {
                sleeper(100)
                if (isServingUi(h, httpPort)) return true to "Viewer server started."
                if (isRunning(h, httpPort) && !proc.isAlive) {
                    process = null
                    return false to "Viewer server answered /healthz but died before serving the page."
                }
                if (!proc.isAlive) {
                    val out = proc.inputStream.bufferedReader().readText()
                    process = null
                    return false to (
                        "Viewer server exited immediately.\n" +
                            "Check that python3 is available and ports $httpPort (HTTP) / $ingestPort (ingest) are free.\n" +
                            out.take(500)
                        )
                }
            }
            if (isRunning(h, httpPort) && !isServingUi(h, httpPort)) {
                stop(h, httpPort)
                return false to "Viewer server started but is not serving index.html."
            }
            true to "Viewer server is starting..."
        } catch (e: Exception) {
            process = null
            false to "Failed to start viewer server:\n${e.message}"
        }
    }

    fun stop(host: String, httpPort: Int): Pair<Boolean, String> {
        val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
        val proc = process
        if (proc != null && proc.isAlive) {
            proc.destroy()
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
            process = null
        }
        if (!isRunning(h, httpPort)) {
            return true to if (proc != null) "Viewer server stopped." else "Viewer server was not running."
        }
        // Still answering: a server this JOSM did not spawn owns the port, so
        // there is no handle to kill. Ask it to exit instead, otherwise the
        // leftover keeps the port and Stop can never clear it.
        if (requestRemoteShutdown(h, httpPort) && !isRunning(h, httpPort)) {
            return true to "Stopped a viewer server that this JOSM did not start."
        }
        if (killViewerListeners(httpPort) && !isRunning(h, httpPort)) {
            return true to "Killed a leftover viewer server on port $httpPort."
        }
        return false to "Failed to stop the viewer server on port $httpPort.\n" +
            "Kill the leftover python process (plugins/lanelet2/viewer3d/server.py) and click Start again."
    }

    fun openBrowserTab(host: String, httpPort: Int): Pair<Boolean, String> {
        val url = "http://${host.ifBlank { Viewer3dSettings.DEFAULT_HOST }}:$httpPort/"
        return try {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
                true to url
            } else {
                false to url
            }
        } catch (_: Exception) {
            false to url
        }
    }

    fun shutdown() {
        stop(Viewer3dSettings.getHost(), Viewer3dSettings.getHttpPort())
    }

    companion object {
        fun locatePython3(): String? {
            val path = System.getenv("PATH")?.split(File.pathSeparatorChar) ?: return null
            for (name in listOf("python3", "python")) {
                for (entry in path) {
                    val candidate = File(entry, name)
                    if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
                }
            }
            return null
        }

        /** `POST /shutdown`; the server answers, then exits. */
        fun requestRemoteShutdown(host: String, httpPort: Int, timeoutMs: Int = 1000): Boolean {
            val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
            for (candidate in listOf(h, "127.0.0.1", "localhost").distinct()) {
                try {
                    val conn = URI("http://$candidate:$httpPort/shutdown").toURL()
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.outputStream.use { it.write(ByteArray(0)) }
                    if (conn.responseCode == 200) {
                        // The process exits a moment after replying.
                        Thread.sleep(400)
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            return false
        }

        fun probeUi(host: String, httpPort: Int, timeoutMs: Int = 400): Boolean {
            val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
            for (candidate in listOf(h, "127.0.0.1", "localhost").distinct()) {
                try {
                    val conn = URI("http://$candidate:$httpPort/").toURL()
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "GET"
                    if (conn.responseCode != 200) continue
                    val type = conn.contentType.orEmpty()
                    val body = conn.inputStream.bufferedReader().readText()
                    if (type.contains("html") || body.contains("<html") || body.contains("app.js")) {
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            return false
        }

        /**
         * Last resort for a leftover whose /shutdown is missing or ignored.
         * Only signals processes whose command line is our extracted server.py.
         */
        fun killViewerListeners(httpPort: Int): Boolean {
            var killed = false
            for (pid in pidsListeningOn(httpPort)) {
                val cmd = processCommandLine(pid) ?: continue
                if ("viewer3d/server.py" !in cmd && !cmd.contains("lanelet2/viewer3d")) continue
                try {
                    val handle = ProcessHandle.of(pid).orElse(null) ?: continue
                    handle.destroy()
                    val deadline = System.currentTimeMillis() + 2000
                    while (handle.isAlive && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50)
                    }
                    if (handle.isAlive) handle.destroyForcibly()
                    killed = true
                } catch (_: Exception) {
                }
            }
            return killed
        }

        internal fun pidsListeningOn(port: Int): List<Long> {
            val inodes = linkedSetOf<String>()
            for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val file = File(table)
                if (!file.isFile) continue
                for (line in file.readLines().drop(1)) {
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 10) continue
                    val local = cols[1]
                    val colon = local.lastIndexOf(':')
                    if (colon < 0) continue
                    val localPort = local.substring(colon + 1).toIntOrNull(16) ?: continue
                    if (localPort != port) continue
                    // st 0A = LISTEN
                    if (cols[3] != "0A") continue
                    inodes.add(cols[9])
                }
            }
            if (inodes.isEmpty()) return emptyList()
            val pids = ArrayList<Long>()
            val proc = File("/proc")
            for (entry in proc.listFiles().orEmpty()) {
                val pid = entry.name.toLongOrNull() ?: continue
                val fdDir = File(entry, "fd")
                if (!fdDir.isDirectory) continue
                try {
                    for (fd in fdDir.listFiles().orEmpty()) {
                        val target = try {
                            java.nio.file.Files.readSymbolicLink(fd.toPath()).toString()
                        } catch (_: Exception) {
                            continue
                        }
                        if (inodes.any { target == "socket:[$it]" }) {
                            pids.add(pid)
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return pids.distinct()
        }

        private fun processCommandLine(pid: Long): String? = try {
            File("/proc/$pid/cmdline").readBytes()
                .toString(StandardCharsets.UTF_8)
                .replace('\u0000', ' ')
        } catch (_: Exception) {
            null
        }

        private val BUILD_FIELD = Regex("\"build\"\\s*:\\s*\"([^\"]+)\"")

        /** Build id from `/healthz`, or null when absent (older server) or unreachable. */
        fun probeBuild(host: String, httpPort: Int, timeoutMs: Int = 400): String? {
            val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
            for (candidate in listOf(h, "127.0.0.1", "localhost").distinct()) {
                try {
                    val conn = URI("http://$candidate:$httpPort/healthz").toURL()
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "GET"
                    if (conn.responseCode != 200) continue
                    val body = conn.inputStream.bufferedReader().readText()
                    return BUILD_FIELD.find(body)?.groupValues?.get(1)
                } catch (_: Exception) {
                }
            }
            return null
        }

        fun probeHealth(host: String, httpPort: Int, timeoutMs: Int = 400): Boolean {
            val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
            for (candidate in listOf(h, "127.0.0.1", "localhost").distinct()) {
                try {
                    val url = URI("http://$candidate:$httpPort/healthz").toURL()
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "GET"
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        if (body.contains("\"ok\"") && body.contains("true")) return true
                    }
                } catch (_: Exception) {
                }
            }
            return false
        }
    }
}

/**
 * TCP client to the ingest port: sender thread serializes JSON off-EDT; reader
 * forwards inbound commands. The sender owns reconnection; the reader does not.
 */
class Viewer3dSocketClient(
    private val host: () -> String,
    private val port: () -> Int,
    private val onCommand: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val queueMax: Int = Viewer3dConstants.QUEUE_MAX,
) {
    private val queue = ArrayBlockingQueue<OutboundMessage>(queueMax)
    private val sockRef = AtomicReference<LineSocket?>(null)
    @Volatile private var stop = false
    @Volatile private var senderThread: Thread? = null
    @Volatile private var readerThread: Thread? = null
    private val wake = Object()

    val connected: Boolean get() = sockRef.get() != null

    fun start() {
        stop = false
        if (senderThread?.isAlive != true) {
            senderThread = Thread(::senderLoop, "viewer3d-sender").apply {
                isDaemon = true
                start()
            }
        }
        if (readerThread?.isAlive != true) {
            readerThread = Thread(::readerLoop, "viewer3d-reader").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        stop = true
        drainQueue()
        queue.offer(POISON)
        synchronized(wake) { wake.notifyAll() }
        closeSocket()
        senderThread = null
        readerThread = null
    }

    fun enqueue(message: OutboundMessage) {
        if (queue.offer(message)) return
        // Overflow: drop the backlog and ask the owner for a fresh snapshot,
        // since surviving patches would apply to a baseline we just discarded.
        drainQueue()
        onConnected()
        queue.offer(message)
    }

    /**
     * Drops queued messages so a caller can rebuild from a full snapshot.
     *
     * Must not invoke [onConnected]: the owner's handler calls straight back
     * into here, so doing so spins the EDT forever and drains every message
     * before the sender can write it.
     */
    fun requestResync() {
        drainQueue()
    }

    private fun drainQueue() {
        while (queue.poll() != null) {
            // drop
        }
    }

    private fun senderLoop() {
        while (!stop) {
            if (sockRef.get() == null) {
                try {
                    val s = connectTcp(host(), port())
                    sockRef.set(s)
                    onConnected()
                } catch (_: Exception) {
                    sockRef.set(null)
                    synchronized(wake) {
                        wake.wait(2000)
                    }
                    continue
                }
            }
            val msg = try {
                queue.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (msg == null) continue
            if (msg === POISON) break
            try {
                val line = Viewer3dJson.encode(msg) + "\n"
                sockRef.get()?.send(line)
            } catch (_: Exception) {
                closeSocket()
            }
        }
    }

    private fun readerLoop() {
        while (!stop) {
            val s = sockRef.get()
            if (s == null) {
                Thread.sleep(300)
                continue
            }
            try {
                val line = s.readLine()
                if (line == null) {
                    Thread.sleep(300)
                    continue
                }
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) onCommand(trimmed)
            } catch (_: Exception) {
                Thread.sleep(300)
            }
        }
    }

    private fun closeSocket() {
        val s = sockRef.getAndSet(null)
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }

    private fun connectTcp(host: String, port: Int, timeoutMs: Int = 3000): LineSocket {
        val sock = Socket(Proxy.NO_PROXY)
        sock.connect(InetSocketAddress(host.ifBlank { Viewer3dSettings.DEFAULT_HOST }, port), timeoutMs)
        sock.soTimeout = 0
        return LineSocket(sock)
    }

    private class LineSocket(private val sock: Socket) {
        private val out = sock.getOutputStream()
        private val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))

        fun send(data: String) {
            out.write(data.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }

        fun readLine(): String? = reader.readLine()

        fun close() = sock.close()
    }

    companion object {
        private val POISON = OutboundMessage.Patch(emptyList())
    }
}
