package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class BackendSetupTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        Sidecar.resetOverrides()
    }

    @Test
    fun platformErrorOnNonLinux() {
        assertTrue(BackendSetup.platformError("Windows 11", "amd64")!!.contains("Linux x64"))
        assertTrue(BackendSetup.platformError("Linux", "aarch64")!!.contains("Linux x64"))
        assertNull(BackendSetup.platformError("Linux", "amd64"))
        assertNull(BackendSetup.platformError("Linux", "x86_64"))
    }

    @Test
    fun venvAndPipCommandsAreExplicit(@TempDir dir: Path) {
        val venv = dir.resolve("venv").toFile()
        val backends = dir.resolve("backends").toFile()
        val plan = BackendSetup.plan("/usr/bin/python3.11", venv, backends)
        assertEquals(
            listOf("/usr/bin/python3.11", "-m", "venv", venv.absolutePath),
            plan.venvCommand(),
        )
        assertEquals("python", plan.venvPython.name)
        assertTrue(plan.pipInstallCommand().contains("lanelet2"))
        assertTrue(plan.pipInstallCommand().any { it.startsWith("numpy") })
        assertEquals("numpy<2", plan.pipInstallCommand().last())
        assertFalse(plan.pipInstallCommand().contains("requests"))
    }

    @Test
    fun persistWritesPythonDirAndNoneEnvScript(@TempDir dir: Path) {
        val venv = dir.resolve("venv").toFile()
        val backends = dir.resolve("backends").toFile()
        backends.mkdirs()
        File(File(venv, "bin"), "python").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
        }
        val plan = BackendSetup.plan("python3", venv, backends)
        BackendSetup.persist(plan)
        assertEquals(plan.venvPython.absolutePath, LaneletSettings.getBackendsPython())
        assertEquals(backends.absolutePath, LaneletSettings.getBackendsDir())
        assertEquals("none", LaneletSettings.get(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, ""))
        assertEquals("none", Config.getPref().get("lanelet2.backends.env_script", null))
    }

    @Test
    fun persistExistingInterpreterUsesAbsoluteBackendsDir(@TempDir dir: Path) {
        val backends = dir.resolve("backends").toFile()
        backends.mkdirs()
        BackendSetup.persistExistingInterpreter("/opt/ll2/bin/python", backends)
        assertEquals("/opt/ll2/bin/python", LaneletSettings.getBackendsPython())
        assertEquals("none", LaneletSettings.get(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, ""))
    }

    @Test
    fun installStreamsLogAndStopsOnVenvFailure(@TempDir dir: Path) {
        val plan = BackendSetup.plan("python3", dir.resolve("venv").toFile(), dir.resolve("b").toFile())
        val log = mutableListOf<String>()
        val commands = mutableListOf<List<String>>()
        val err = BackendSetup.install(plan, { log.add(it) }) { cmd, onLine ->
            commands.add(cmd)
            onLine("simulated venv failure")
            1
        }
        assertTrue(err!!.contains("venv failed"))
        assertEquals(1, commands.size)
        assertTrue(log.any { it.contains("simulated venv failure") })
        assertEquals("", LaneletSettings.getBackendsPython())
    }

    @Test
    fun installPersistsAfterSuccessfulPip(@TempDir dir: Path) {
        val venv = dir.resolve("venv").toFile()
        val venvPy = File(File(venv, "bin"), "python")
        val backends = dir.resolve("backends").toFile()
        backends.mkdirs()
        val plan = BackendSetup.plan("python3", venv, backends)
        val err = BackendSetup.install(plan, { }) { cmd, _ ->
            if (cmd.contains("-m") && cmd.contains("venv")) {
                venvPy.parentFile.mkdirs()
                venvPy.writeText("#!/bin/sh\n")
                venvPy.setExecutable(true)
            }
            0
        }
        assertNull(err)
        assertEquals(venvPy.absolutePath, LaneletSettings.getBackendsPython())
        assertEquals(backends.absolutePath, LaneletSettings.getBackendsDir())
        assertEquals("none", LaneletSettings.get(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, ""))
    }

    @Test
    fun checkBootstrapAllowsMissingLanelet2IfVersionOk() {
        val check = BackendSetup.checkBootstrapPython("python3") { _ ->
            HealthReport(
                SidecarProblem.LANELET2_IMPORT_FAILED,
                detail = "ModuleNotFoundError",
                python = "python3",
                pythonVersion = "3.10",
            )
        }
        assertTrue(check.ok)
        assertEquals("3.10", check.version)
    }

    @Test
    fun checkInterpreterRejectsWrongVersionEvenIfLanelet2Imports() {
        val check = BackendSetup.checkInterpreter("python3") { _ ->
            HealthReport(
                SidecarProblem.WRONG_PYTHON_VERSION,
                python = "python3",
                pythonVersion = "3.13",
            )
        }
        assertFalse(check.ok)
        assertEquals(SidecarProblem.WRONG_PYTHON_VERSION, check.problem)
        assertTrue(check.message.contains(SidecarHealth.VERSION_RANGE))
    }
}
