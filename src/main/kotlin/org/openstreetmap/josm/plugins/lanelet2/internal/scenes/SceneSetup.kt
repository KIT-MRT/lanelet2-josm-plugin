package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendSetup
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendStore
import org.openstreetmap.josm.plugins.lanelet2.sidecar.SidecarHealth
import java.io.File

/**
 * Python packages for KITScenes sensor data, installed into the backends'
 * private virtualenv (which already has `lanelet2` and `numpy<2`, the same
 * constraints the scene server has). One button, no checkout:
 *
 * - `kitscenes` is not on PyPI, so it comes from its GitHub archive at a
 *   pinned commit, with `--no-deps`: its own requirements pull in
 *   matplotlib and huggingface_hub, which only its visualisation and
 *   download modules use (~100 MB the scene server never imports).
 * - `pyarrow==17.0.0` and `scipy<1.15` are the last releases that work with
 *   NumPy 1.x; newer pyarrow refuses to import next to `numpy<2`.
 *
 * Python 3.8-3.12 only: NumPy 1.x has no 3.13 wheels. When no such
 * interpreter is on PATH but `uv` is, `uv` provides a 3.12.
 */
object SceneSetup {
    const val KITSCENES_COMMIT = "7765cdec5490894266070ab46e23724b58b3da42"
    const val KITSCENES_SPEC = "kitscenes @ https://github.com/KIT-MRT/kitscenes/archive/$KITSCENES_COMMIT.zip"
    val PIP_PACKAGES: List<String> = listOf("numpy<2", "pyarrow==17.0.0", "scipy<1.15", "pyproj", "pillow", "tqdm")

    /** What the scene server imports; `ok` on stdout when all of it is there. */
    const val IMPORT_CHECK = "import lanelet2, kitscenes.sensors, pyarrow, scipy, pyproj, PIL; print('ok')"

    /** Advanced: another pip spec for kitscenes (a local checkout, a wheel). */
    const val KEY_KITSCENES_SPEC = "scenes.kitscenes_spec"

    fun kitscenesSpec(): String = LaneletSettings.get(KEY_KITSCENES_SPEC, "").trim().ifEmpty { KITSCENES_SPEC }

    fun depsCommand(python: String): List<String> = listOf(python, "-m", "pip", "install") + PIP_PACKAGES

    fun kitscenesCommand(python: String, spec: String = kitscenesSpec()): List<String> =
        listOf(python, "-m", "pip", "install", "--no-deps", spec)

    data class Check(val ok: Boolean, val message: String)

    /** Can [python] run the scene server? */
    fun check(
        python: String,
        execute: (List<String>, File?) -> BackendResult = BackendRunner::runProcess,
    ): Check {
        BackendRunner.missingPythonMessage(python)?.let { return Check(false, it) }
        val result = try {
            execute(SceneLaunch.wrap(python, listOf("-c", IMPORT_CHECK), BackendRunner.resolveEnvScript()), null)
        } catch (e: Exception) {
            return Check(false, e.message ?: e.toString())
        }
        if (result.success && result.stdout.contains("ok")) return Check(true, "Ready: $python")
        val why = (result.stderr.ifBlank { result.error ?: "" }).trim().lines().lastOrNull { it.isNotBlank() }
        return Check(false, "Not installed in $python" + (why?.let { " ($it)" } ?: ""))
    }

    /** A Python 3.8-3.12 to create the virtualenv with, or null (after saying why). */
    fun findBootstrapPython(
        log: (String) -> Unit,
        check: (String) -> BackendSetup.InterpreterCheck = { BackendSetup.checkBootstrapPython(it) },
        uv: () -> String? = { locateUv() },
        run: (List<String>, (String) -> Unit) -> Int = BackendSetup::streamProcess,
        capture: (List<String>) -> String? = ::captureLine,
    ): String? {
        for (name in BackendSetup.CANDIDATE_NAMES) {
            val c = check(name)
            if (c.ok) return c.python.ifEmpty { name }
        }
        val uvPath = uv()
        if (uvPath == null) {
            log(
                "No Python ${SidecarHealth.VERSION_RANGE} on PATH (lanelet2 and NumPy 1.x need one). " +
                    "Install python3.12, or uv (https://docs.astral.sh/uv/), which can provide it.",
            )
            return null
        }
        val find = listOf(uvPath, "python", "find", "3.12")
        capture(find)?.let { return it }
        log("Getting Python 3.12 with uv")
        log("\$ $uvPath python install 3.12")
        if (run(listOf(uvPath, "python", "install", "3.12"), log) != 0) {
            log("uv could not install Python 3.12")
            return null
        }
        return capture(find)
    }

    data class InstallResult(val python: String?, val error: String?)

    /**
     * Everything the scene server needs, in the backends' virtualenv: create
     * it first when there is no working one, then add the packages above.
     */
    fun install(
        log: (String) -> Unit,
        run: (List<String>, (String) -> Unit) -> Int = BackendSetup::streamProcess,
        healthy: (String) -> Boolean = { SidecarHealth.probe(it).healthy },
        bootstrap: () -> String? = { findBootstrapPython(log, run = run) },
        backendsDir: () -> File = { BackendStore.ensureExtracted() },
        verify: (String) -> Check = { check(it) },
        venvDir: File = BackendStore.defaultVenvDir(),
    ): InstallResult {
        var python = BackendRunner.resolvePython()
        if (!healthy(python)) {
            val boot = bootstrap() ?: return InstallResult(null, "No usable Python ${SidecarHealth.VERSION_RANGE} found")
            log("Using $boot to create the Lanelet2 virtualenv")
            val plan = BackendSetup.plan(bootstrapPython = boot, venvDir = venvDir, backendsDir = backendsDir())
            BackendSetup.install(plan, log, run)?.let { return InstallResult(null, it) }
            python = plan.venvPython.absolutePath
        }
        for (cmd in listOf(depsCommand(python), kitscenesCommand(python))) {
            log("\$ ${cmd.joinToString(" ")}")
            val code = run(cmd, log)
            if (code != 0) return InstallResult(python, "pip install failed with exit $code")
        }
        val ok = verify(python)
        log(ok.message)
        return if (ok.ok) InstallResult(python, null) else InstallResult(python, ok.message)
    }

    fun locateUv(
        path: String? = System.getenv("PATH"),
        home: String? = System.getProperty("user.home"),
    ): String? {
        val dirs = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() } +
            listOfNotNull(home?.let { "$it/.local/bin" }, home?.let { "$it/.cargo/bin" })
        return dirs.map { File(it, "uv") }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun captureLine(command: List<String>): String? {
        val r = BackendRunner.runProcess(command, null)
        return if (r.success) r.stdout.trim().lines().lastOrNull { it.isNotBlank() } else null
    }
}
