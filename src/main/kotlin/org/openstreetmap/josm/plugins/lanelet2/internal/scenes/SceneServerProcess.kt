package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** One scene (a folder holding `poses.txt`) or every scene of a city under a folder. */
enum class SceneMode { SCENE, CITY }

/** What to start: the folder, how, and where the viewer is. */
data class LaunchPlan(
    val root: File,
    val mode: SceneMode,
    /** JOSM streams the map: the scene server waits for it, adopts its anchor and sends none. */
    val useJosmMap: Boolean,
    val viewerHost: String,
    val viewerPort: Int,
    val ingestPort: Int,
    val httpPort: Int,
    val waitForMapS: Int = SceneLaunch.WAIT_FOR_MAP_S,
)

/** The `python -m ll2_scene` command for a [LaunchPlan]; pure, so it is tested as such. */
object SceneLaunch {
    /** A city map takes JOSM a while to stream the first time. */
    const val WAIT_FOR_MAP_S = 180

    fun detectMode(root: File): SceneMode =
        if (File(root, "poses.txt").isFile) SceneMode.SCENE else SceneMode.CITY

    fun arguments(plan: LaunchPlan): List<String> = buildList {
        add("-m")
        add(SceneServerStore.PACKAGE)
        add(if (plan.mode == SceneMode.SCENE) "--scene" else "--city")
        // As given, not resolved: a symlinked dataset keeps the scene ids it is cached by.
        add(plan.root.absolutePath)
        addAll(listOf("--viewer-host", plan.viewerHost, "--viewer-port", plan.viewerPort.toString(),
            "--ingest-port", plan.ingestPort.toString(), "--http-port", plan.httpPort.toString()))
        if (plan.useJosmMap) addAll(listOf("--no-map", "--wait-for-map", plan.waitForMapS.toString()))
    }

    /** [python] with [args], through the backends' env script when one is set (as [BackendRunner] does). */
    fun wrap(python: String, args: List<String>, envScript: String?): List<String> {
        if (envScript == null || !File(envScript).isFile) return listOf(python) + args
        val line = (listOf(python) + args).joinToString(" ") { BackendRunner.shellQuote(it) }
        return listOf("bash", "-c", "source ${BackendRunner.shellQuote(envScript)} && $line")
    }
}

/**
 * Owns the scene server process JOSM started. Its output goes to [logFile]
 * (it logs indexing and every opened scene; an unread pipe would stall it).
 * Stopped with its worker processes on [stop] and when JOSM exits.
 */
class SceneServerProcess(
    val logFile: File = File(SceneServerStore.defaultExtractDir().parentFile, "scene_server.log"),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    var plan: LaunchPlan? = null
        private set

    private var hookInstalled = false

    fun isAlive(): Boolean = process?.isAlive == true

    fun start(plan: LaunchPlan, python: String, extractDir: File, envScript: String?): Pair<Boolean, String> {
        stop()
        val cmd = SceneLaunch.wrap(python, SceneLaunch.arguments(plan), envScript)
        return try {
            logFile.parentFile?.mkdirs()
            val builder = ProcessBuilder(cmd)
                .directory(extractDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
            val env = builder.environment()
            env["PYTHONPATH"] = listOfNotNull(extractDir.absolutePath, env["PYTHONPATH"]?.takeIf { it.isNotBlank() })
                .joinToString(File.pathSeparator)
            env["PYTHONUNBUFFERED"] = "1"
            val proc = builder.start()
            process = proc
            this.plan = plan
            installShutdownHook()
            Logging.info("lanelet2: scene server started: {0}", cmd.joinToString(" "))
            // An import error or a busy port shows within a second or two.
            repeat(15) {
                sleeper(100)
                if (!proc.isAlive) {
                    process = null
                    return false to "The scene server exited at once:\n" + tail(12).joinToString("\n")
                }
            }
            true to "Scene server started."
        } catch (e: Exception) {
            process = null
            false to "Could not start the scene server:\n${e.message}"
        }
    }

    fun stop(): Pair<Boolean, String> {
        val proc = process ?: return true to "The scene server was not running."
        process = null
        // Its aggregate workers are child processes: they go too.
        val children = proc.descendants().toList()
        proc.destroy()
        children.forEach { it.destroy() }
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        } catch (_: InterruptedException) {
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        return true to "Scene server stopped."
    }

    /** Scene servers this JOSM did not start that hold [port] (one left over, or run by hand). */
    fun foreignServers(port: Int): List<ProcessHandle> {
        val mine = process?.toHandle()?.let { h -> setOf(h.pid()) + h.descendants().map { it.pid() }.toList() }.orEmpty()
        return ProcessHandle.allProcesses().filter { h ->
            val line = h.info().commandLine().orElse("")
            h.pid() !in mine && line.contains(SceneServerStore.PACKAGE) && line.contains("--http-port $port")
        }.toList()
    }

    /** The last [lines] lines of the log. */
    fun tail(lines: Int = 200): List<String> {
        if (!logFile.isFile) return emptyList()
        return try {
            RandomAccessFile(logFile, "r").use { f ->
                val n = minOf(f.length(), 64L * 1024)
                f.seek(f.length() - n)
                val bytes = ByteArray(n.toInt())
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().dropLastWhile { it.isBlank() }.takeLast(lines)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun statusText(): String {
        val p = plan
        return when {
            isAlive() && p != null -> "Running: ${if (p.mode == SceneMode.CITY) "city" else "scene"} ${p.root.name}" +
                " (http://${p.viewerHost}:${p.viewerPort}/)"
            p != null -> "Stopped."
            else -> "Not running."
        }
    }

    @Synchronized
    private fun installShutdownHook() {
        if (hookInstalled) return
        hookInstalled = true
        Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "lanelet2-scene-server-stop"))
    }
}
