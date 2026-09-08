package org.openstreetmap.josm.plugins.lanelet2.sidecar

import java.io.File

/**
 * Distinct sidecar health states. The four user-facing cases required by the
 * plugin contract are [HEALTHY], [INTERPRETER_MISSING], [WRONG_PYTHON_VERSION]
 * and [LANELET2_IMPORT_FAILED]. [BACKENDS_MISSING] covers extraction / script
 * path failures so those also warn instead of throwing.
 */
enum class SidecarProblem {
    HEALTHY,
    INTERPRETER_MISSING,
    WRONG_PYTHON_VERSION,
    LANELET2_IMPORT_FAILED,
    BACKENDS_MISSING,
}

data class HealthReport(
    val problem: SidecarProblem,
    val detail: String = "",
    val python: String = "",
    val pythonVersion: String? = null,
) {
    val healthy: Boolean get() = problem == SidecarProblem.HEALTHY

    fun userMessage(): String {
        val wizard = "Open Lanelet2 Settings and run “Set up Lanelet2 backends” " +
            "(or point Advanced at an existing Python ${SidecarHealth.VERSION_RANGE} " +
            "interpreter that can `import lanelet2`)."
        return when (problem) {
            SidecarProblem.HEALTHY -> "Lanelet2 backends are ready."
            SidecarProblem.INTERPRETER_MISSING ->
                "Python interpreter not found: ${python.ifEmpty { "(not configured)" }}\n\n$detail\n\n$wizard"
            SidecarProblem.WRONG_PYTHON_VERSION ->
                "Python ${pythonVersion ?: "unknown"} is not supported.\n\n" +
                    "The upstream lanelet2 wheel is Linux x64 only and supports " +
                    "Python ${SidecarHealth.VERSION_RANGE_WORDS}.\n\n" +
                    "$detail\n\n$wizard"
            SidecarProblem.LANELET2_IMPORT_FAILED ->
                "Python is present (${pythonVersion ?: python}) but `import lanelet2` failed.\n\n" +
                    "$detail\n\n$wizard"
            SidecarProblem.BACKENDS_MISSING ->
                "Lanelet2 backend scripts are not available.\n\n$detail\n\n$wizard"
        }
    }
}

object SidecarHealth {
    const val MIN_MINOR = 8
    const val MAX_MINOR = 12
    const val REQUIRED_MAJOR = 3
    const val VERSION_RANGE = "3.8–3.12"
    const val VERSION_RANGE_WORDS = "3.8 to 3.12"

    /**
     * Probe snippet printed as `PY=3.10` / `LANELET2=ok` (or `LANELET2=fail:...`).
     * Kept as a single `-c` so we do not need a helper file on disk.
     */
    const val PROBE_SOURCE =
        "import sys\n" +
            "print('PY=%d.%d' % (sys.version_info[0], sys.version_info[1]))\n" +
            "try:\n" +
            "    import lanelet2\n" +
            "    print('LANELET2=ok')\n" +
            "except Exception as e:\n" +
            "    print('LANELET2=fail:%s' % type(e).__name__)\n"

    fun isSupportedVersion(major: Int, minor: Int): Boolean =
        major == REQUIRED_MAJOR && minor in MIN_MINOR..MAX_MINOR

    fun classify(stdout: String, started: Boolean, startError: String? = null): HealthReport {
        if (!started) {
            return HealthReport(
                SidecarProblem.INTERPRETER_MISSING,
                detail = startError ?: "Could not start the interpreter.",
            )
        }
        val pyLine = stdout.lineSequence().firstOrNull { it.startsWith("PY=") }
        if (pyLine == null) {
            return HealthReport(
                SidecarProblem.INTERPRETER_MISSING,
                detail = "Interpreter did not report a Python version.\n$stdout".trim(),
            )
        }
        val version = pyLine.removePrefix("PY=").trim()
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()
        if (major == null || minor == null || !isSupportedVersion(major, minor)) {
            return HealthReport(
                SidecarProblem.WRONG_PYTHON_VERSION,
                detail = "Detected Python $version.",
                pythonVersion = version,
            )
        }
        val ll2 = stdout.lineSequence().firstOrNull { it.startsWith("LANELET2=") }
        if (ll2 == null || ll2.startsWith("LANELET2=fail")) {
            val why = ll2?.removePrefix("LANELET2=fail:") ?: "unknown"
            return HealthReport(
                SidecarProblem.LANELET2_IMPORT_FAILED,
                detail = "import lanelet2 failed ($why).",
                pythonVersion = version,
            )
        }
        return HealthReport(
            SidecarProblem.HEALTHY,
            pythonVersion = version,
        )
    }

    fun probe(
        python: String,
        execute: (command: List<String>, cwd: File?) -> BackendResult = BackendRunner::runProcess,
    ): HealthReport {
        val missing = BackendRunner.missingPythonMessage(python)
        if (missing != null) {
            return HealthReport(
                SidecarProblem.INTERPRETER_MISSING,
                detail = missing,
                python = python,
            )
        }
        val result = try {
            execute(listOf(python, "-c", PROBE_SOURCE), null)
        } catch (e: Exception) {
            return HealthReport(
                SidecarProblem.INTERPRETER_MISSING,
                detail = e.message ?: e.toString(),
                python = python,
            )
        }
        if (result.exitCode == null && !result.success) {
            return HealthReport(
                SidecarProblem.INTERPRETER_MISSING,
                detail = result.error ?: "failed to start $python",
                python = python,
            )
        }
        val combined = result.stdout + "\n" + result.stderr
        return classify(combined, started = true, startError = result.error).copy(python = python)
    }

    fun isSupportedPlatform(
        osName: String = System.getProperty("os.name", ""),
        osArch: String = System.getProperty("os.arch", ""),
    ): Boolean {
        val linux = osName.lowercase().contains("linux")
        val x64 = osArch.lowercase() in setOf("amd64", "x86_64")
        return linux && x64
    }
}
