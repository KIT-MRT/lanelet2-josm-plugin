package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.io.File

/**
 * Headless-safe setup logic for the Lanelet2 Python sidecar: find a supported
 * interpreter, create a private venv, pip-install `lanelet2` and `numpy<2`,
 * persist the result through [LaneletSettings].
 *
 * The upstream `lanelet2` wheel is Linux x64 and Python 3.8–3.11 only.
 * Validation reports that clearly rather than failing inside pip.
 */
object BackendSetup {
    val PIP_PACKAGES: List<String> = listOf("lanelet2", "numpy<2")

    val CANDIDATE_NAMES: List<String> = listOf(
        "python3.11", "python3.10", "python3.9", "python3.8", "python3",
    )

    data class InterpreterCheck(
        val ok: Boolean,
        val problem: SidecarProblem? = null,
        val message: String,
        val version: String? = null,
        val python: String = "",
    )

    data class InstallPlan(
        val bootstrapPython: String,
        val venvDir: File,
        val venvPython: File,
        val backendsDir: File,
        val packages: List<String> = PIP_PACKAGES,
    ) {
        fun venvCommand(): List<String> =
            listOf(bootstrapPython, "-m", "venv", venvDir.absolutePath)

        fun pipUpgradeCommand(): List<String> =
            listOf(venvPython.absolutePath, "-m", "pip", "install", "--upgrade", "pip")

        fun pipInstallCommand(): List<String> =
            listOf(venvPython.absolutePath, "-m", "pip", "install") + packages
    }

    fun platformError(
        osName: String = System.getProperty("os.name", ""),
        osArch: String = System.getProperty("os.arch", ""),
    ): String? {
        if (SidecarHealth.isSupportedPlatform(osName, osArch)) return null
        return "The upstream lanelet2 wheel is Linux x64 only (Python 3.8–3.11). " +
            "This machine reports os=$osName arch=$osArch."
    }

    fun checkInterpreter(
        python: String,
        probe: (String) -> HealthReport = { SidecarHealth.probe(it) },
    ): InterpreterCheck {
        val report = probe(python).copy(python = python)
        return when (report.problem) {
            SidecarProblem.HEALTHY -> InterpreterCheck(
                ok = true,
                message = "Python ${report.pythonVersion} can import lanelet2.",
                version = report.pythonVersion,
                python = python,
            )
            SidecarProblem.WRONG_PYTHON_VERSION -> InterpreterCheck(
                ok = false,
                problem = report.problem,
                message = "Python ${report.pythonVersion ?: "unknown"} is not 3.8–3.11. " +
                    "The upstream lanelet2 wheel does not support this version.",
                version = report.pythonVersion,
                python = python,
            )
            SidecarProblem.INTERPRETER_MISSING -> InterpreterCheck(
                ok = false,
                problem = report.problem,
                message = "Python interpreter not found: $python",
                python = python,
            )
            SidecarProblem.LANELET2_IMPORT_FAILED -> InterpreterCheck(
                ok = false,
                problem = report.problem,
                message = "Python ${report.pythonVersion ?: python} cannot import lanelet2. " +
                    "Create a private virtualenv (recommended) or pip-install lanelet2 into this interpreter.",
                version = report.pythonVersion,
                python = python,
            )
            SidecarProblem.BACKENDS_MISSING -> InterpreterCheck(
                ok = false,
                problem = report.problem,
                message = report.detail,
                python = python,
            )
        }
    }

    /**
     * An interpreter that is present and the right version, even if lanelet2
     * is not yet installed — that is the bootstrap python for `python -m venv`.
     */
    fun checkBootstrapPython(
        python: String,
        probe: (String) -> HealthReport = { SidecarHealth.probe(it) },
    ): InterpreterCheck {
        val report = probe(python).copy(python = python)
        return when (report.problem) {
            SidecarProblem.HEALTHY,
            SidecarProblem.LANELET2_IMPORT_FAILED,
            -> InterpreterCheck(
                ok = true,
                problem = report.problem,
                message = "Python ${report.pythonVersion} is 3.8–3.11 and can create a virtualenv.",
                version = report.pythonVersion,
                python = python,
            )
            else -> checkInterpreter(python) { report }
        }
    }

    fun plan(
        bootstrapPython: String,
        venvDir: File = BackendStore.defaultVenvDir(),
        backendsDir: File = BackendStore.defaultExtractDir(),
    ): InstallPlan = InstallPlan(
        bootstrapPython = bootstrapPython,
        venvDir = venvDir,
        venvPython = File(File(venvDir, "bin"), "python"),
        backendsDir = backendsDir,
    )

    fun persist(plan: InstallPlan) {
        LaneletSettings.persistBackendInterpreter(
            plan.venvPython.absolutePath,
            plan.backendsDir.absolutePath,
        )
    }

    fun persistExistingInterpreter(python: String, backendsDir: File = BackendStore.defaultExtractDir()) {
        LaneletSettings.persistBackendInterpreter(python, backendsDir.absolutePath)
    }

    /**
     * Run venv + pip, streaming each line to [log]. Returns a failure message
     * or null on success. Does not throw.
     */
    fun install(
        plan: InstallPlan,
        log: (String) -> Unit,
        run: (command: List<String>, onLine: (String) -> Unit) -> Int,
    ): String? {
        val plat = platformError()
        if (plat != null) {
            log(plat)
            return plat
        }
        log("Creating virtualenv at ${plan.venvDir.absolutePath}")
        log("\$ ${plan.venvCommand().joinToString(" ")}")
        val venvCode = run(plan.venvCommand(), log)
        if (venvCode != 0) {
            val msg = "python -m venv failed with exit $venvCode"
            log(msg)
            return msg
        }
        val venvPy = plan.venvPython
        if (!venvPy.isFile) {
            val msg = "venv python not found at ${venvPy.absolutePath}"
            log(msg)
            return msg
        }
        log("Upgrading pip")
        log("\$ ${plan.pipUpgradeCommand().joinToString(" ")}")
        val pipUp = run(plan.pipUpgradeCommand(), log)
        if (pipUp != 0) {
            log("pip upgrade failed with exit $pipUp (continuing)")
        }
        log("Installing ${plan.packages.joinToString(" ")}")
        log("\$ ${plan.pipInstallCommand().joinToString(" ")}")
        val pipCode = run(plan.pipInstallCommand(), log)
        if (pipCode != 0) {
            val msg = "pip install failed with exit $pipCode"
            log(msg)
            return msg
        }
        persist(plan)
        log("Saved backends.python=${plan.venvPython.absolutePath}")
        log("Saved backends.dir=${plan.backendsDir.absolutePath}")
        return null
    }

    fun streamProcess(command: List<String>, onLine: (String) -> Unit): Int {
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(true)
        val proc = try {
            builder.start()
        } catch (e: Exception) {
            onLine("failed to start: ${e.message}")
            return -1
        }
        proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
        return try {
            proc.waitFor()
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
            onLine("interrupted: ${e.message}")
            -1
        }
    }
}
