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

/** Owns the extracted `server.py` process; probes `GET /healthz` for liveness. */
class Viewer3dServerProcess(
    private val python: () -> String? = { locatePython3() },
    private val extract: () -> Viewer3dStore.ExtractPaths = { Viewer3dStore.ensureExtracted() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile
    private var process: Process? = null

    fun isRunning(host: String, httpPort: Int): Boolean = probeHealth(host, httpPort)

    fun statusText(host: String, httpPort: Int): String =
        if (isRunning(host, httpPort)) "Running at http://$host:$httpPort/" else "Not running"

    fun start(
        host: String,
        ingestPort: Int,
        httpPort: Int,
        profile: Boolean = false,
        openBrowser: Boolean = false,
    ): Pair<Boolean, String> {
        val h = host.ifBlank { Viewer3dSettings.DEFAULT_HOST }
        if (isRunning(h, httpPort)) {
            return true to "Viewer server is already running."
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
                if (isRunning(h, httpPort)) return true to "Viewer server started."
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
        return false to "Failed to stop the viewer server on port $httpPort."
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
        drainQueue()
        onConnected()
        queue.offer(message)
    }

    fun requestResync() {
        drainQueue()
        onConnected()
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
