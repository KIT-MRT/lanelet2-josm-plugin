package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Invokes a shipped backend script the way `backends/backend_runner.py` does:
 * `python <script.py> <args>`, optionally wrapped in `bash -c "source env && ..."`.
 *
 * Failures are [BackendResult]s. This class does not throw for a non-zero
 * exit or a missing interpreter — those are structured results so actions can
 * warn and return without taking down the rest of the plugin.
 */
class BackendRunner(
    private val python: () -> String = { resolvePython() },
    private val backendsDir: () -> File = { Sidecar.backendsDir() },
    private val envScript: () -> String? = { resolveEnvScript() },
    private val execute: (command: List<String>, cwd: File?) -> BackendResult = Companion::runProcess,
) {
    /**
     * Run `<name>.py` with [args]. Returns stdout even on failure so callers
     * that parse `MERGE_OUTPUT=` / `SPLIT_TARGET=` still see it.
     */
    fun run(name: String, args: List<String>, cwd: File? = null): BackendResult {
        val dir = backendsDir()
        val script = BackendStore.resolveScript(name, dir)
        if (script == null) {
            return BackendResult.fail(
                "Backend script not found: $name.py (looked in ${dir.absolutePath}). " +
                    "Set backends.dir / LL2_BACKENDS_DIR, or run Set up Lanelet2 backends.",
            )
        }
        val py = python()
        val missing = missingPythonMessage(py)
        if (missing != null) {
            return BackendResult.fail(missing)
        }
        val env = envScript()
        val command = if (env != null && File(env).isFile) {
            val cmdStr = buildString {
                append("source ")
                append(shellQuote(env))
                append(" && ")
                append(shellQuote(py))
                append(' ')
                append(shellQuote(script.absolutePath))
                for (a in args) {
                    append(' ')
                    append(shellQuote(a))
                }
            }
            listOf("bash", "-c", cmdStr)
        } else {
            listOf(py, script.absolutePath) + args
        }
        return try {
            execute(command, cwd)
        } catch (e: Exception) {
            BackendResult.fail(e.message ?: e.toString())
        }
    }

    companion object {
        fun resolvePython(): String {
            val fromSettings = LaneletSettings.get(LaneletSettings.KEY_BACKENDS_PYTHON, "").trim()
            if (fromSettings.isNotEmpty()) return fromSettings
            val fromEnv = System.getenv("LL2_BACKENDS_PYTHON")?.trim().orEmpty()
            if (fromEnv.isNotEmpty()) return fromEnv
            return "python3"
        }

        fun resolveBackendsDir(): File {
            val fromSettings = LaneletSettings.get(LaneletSettings.KEY_BACKENDS_DIR, "").trim()
            if (fromSettings.isNotEmpty()) {
                return File(expandUser(fromSettings)).absoluteFile
            }
            val fromEnv = System.getenv("LL2_BACKENDS_DIR")?.trim().orEmpty()
            if (fromEnv.isNotEmpty()) {
                return File(expandUser(fromEnv)).absoluteFile
            }
            return try {
                BackendStore.ensureExtracted()
            } catch (e: Exception) {
                Logging.warn("lanelet2: backend extraction failed: {0}", e.message)
                BackendStore.defaultExtractDir()
            }
        }

        /**
         * Settings value `none` forces no sourcing (OSS default after the
         * setup wizard). Empty / unset falls through to env, then no script.
         */
        fun resolveEnvScript(): String? {
            val fromSettings = LaneletSettings.get(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, "")
            val trimmed = fromSettings.trim()
            if (trimmed.equals("none", ignoreCase = true)) return null
            if (trimmed.isNotEmpty()) return expandUser(trimmed)
            val fromEnv = System.getenv("LL2_BACKENDS_ENV_SCRIPT")?.trim().orEmpty()
            if (fromEnv.equals("none", ignoreCase = true)) return null
            if (fromEnv.isNotEmpty()) return expandUser(fromEnv)
            return null
        }

        /**
         * Error text when `backends.python` is a concrete path that does not
         * exist. Bare names (`python3`) are left alone: they are resolved after
         * an env script is sourced and cannot be checked here.
         *
         * Port of `backend_runner._missing_python_message`.
         */
        fun missingPythonMessage(py: String): String? {
            if (py.isEmpty()) return null
            if (File.separator !in py && !py.startsWith("~")) return null
            val expanded = File(expandUser(py))
            if (expanded.isFile) return null
            return "Python executable not found: $py\n\n" +
                "This is backends.python in Lanelet2 Settings → Set up Lanelet2 backends. " +
                "Set it to a real python3 that can import lanelet2, " +
                "or run the setup wizard to create a private virtualenv."
        }

        fun shellQuote(arg: String): String =
            "'" + arg.replace("'", "'\"'\"'") + "'"

        fun expandUser(path: String): String {
            if (path == "~") return System.getProperty("user.home")
            if (path.startsWith("~/") || path.startsWith("~" + File.separator)) {
                return System.getProperty("user.home") + path.substring(1)
            }
            return path
        }

        fun runProcess(command: List<String>, cwd: File?): BackendResult {
            val builder = ProcessBuilder(command)
            if (cwd != null) builder.directory(cwd)
            builder.redirectErrorStream(false)
            val proc = try {
                builder.start()
            } catch (e: IOException) {
                return BackendResult.fail(e.message ?: "failed to start ${command.firstOrNull()}")
            }
            val stdoutBytes = StringBuilder()
            val stderrBytes = StringBuilder()
            val outThread = Thread {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    stdoutBytes.append(reader.readText())
                }
            }
            val errThread = Thread {
                proc.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    stderrBytes.append(reader.readText())
                }
            }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val code = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                return BackendResult.fail(e.message ?: "interrupted")
            }
            try {
                outThread.join(TimeUnit.SECONDS.toMillis(5))
                errThread.join(TimeUnit.SECONDS.toMillis(5))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val stdout = stdoutBytes.toString()
            val stderr = stderrBytes.toString()
            if (code != 0) {
                val err = stderr.trim().ifEmpty { "backend failed with code $code" }
                return BackendResult.fail(err, stdout, stderr, code)
            }
            return BackendResult.ok(stdout, stderr, code)
        }
    }
}
