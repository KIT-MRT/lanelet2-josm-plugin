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

class BackendRunnerTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        Sidecar.resetOverrides()
    }

    @Test
    fun missingPythonMessageIgnoresBareNames() {
        assertNull(BackendRunner.missingPythonMessage("python3"))
        assertNull(BackendRunner.missingPythonMessage("python"))
        assertNull(BackendRunner.missingPythonMessage(""))
    }

    @Test
    fun missingPythonMessageFlagsConcreteMissingPath() {
        val msg = BackendRunner.missingPythonMessage("/no/such/python3")
        assertTrue(msg != null && msg.contains("/no/such/python3"))
    }

    @Test
    fun missingPythonMessageAcceptsExistingFile(@TempDir dir: Path) {
        val py = dir.resolve("python").toFile()
        py.writeText("#!/bin/sh\n")
        py.setExecutable(true)
        assertNull(BackendRunner.missingPythonMessage(py.absolutePath))
    }

    @Test
    fun shellQuoteEscapesEmbeddedSingleQuotes() {
        assertEquals("'foo'", BackendRunner.shellQuote("foo"))
        assertEquals("'a'\"'\"'b'", BackendRunner.shellQuote("a'b"))
    }

    @Test
    fun runReportsMissingScriptWithoutThrowing(@TempDir dir: Path) {
        val runner = BackendRunner(
            python = { "python3" },
            backendsDir = { dir.toFile() },
            envScript = { null },
        )
        val result = runner.run("positive_ids", listOf("-i", "/tmp/x.osm"))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("positive_ids.py"))
    }

    @Test
    fun runCapturesStdoutAndNonZeroAsFailure(@TempDir dir: Path) {
        val script = File(dir.toFile(), "echo_fail.py")
        script.writeText(
            """
            import sys
            sys.stdout.write("OUT\n")
            sys.stderr.write("ERR\n")
            sys.exit(3)
            """.trimIndent(),
        )
        val runner = BackendRunner(
            python = { "python3" },
            backendsDir = { dir.toFile() },
            envScript = { null },
        )
        val result = runner.run("echo_fail", listOf("arg1"))
        assertFalse(result.success)
        assertEquals(3, result.exitCode)
        assertTrue(result.stdout.contains("OUT"))
        assertTrue(result.stderr.contains("ERR"))
        assertTrue(result.error!!.contains("ERR"))
    }

    @Test
    fun runSuccessReturnsStdout(@TempDir dir: Path) {
        val script = File(dir.toFile(), "echo_ok.py")
        script.writeText("import sys\nprint('MERGE_OUTPUT=' + sys.argv[1])\n")
        val runner = BackendRunner(
            python = { "python3" },
            backendsDir = { dir.toFile() },
            envScript = { null },
        )
        val abs = File(dir.toFile(), "out.osm").absolutePath
        val result = runner.run("echo_ok", listOf(abs))
        assertTrue(result.success, result.error ?: "")
        assertTrue(result.stdout.contains("MERGE_OUTPUT=$abs"))
    }

    @Test
    fun settingsPythonBeatsDefault() {
        LaneletSettings.put(LaneletSettings.KEY_BACKENDS_PYTHON, "/opt/ll2/bin/python")
        assertEquals("/opt/ll2/bin/python", BackendRunner.resolvePython())
    }

    @Test
    fun envScriptNoneDisablesSourcing() {
        LaneletSettings.put(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, "none")
        assertNull(BackendRunner.resolveEnvScript())
        LaneletSettings.put(LaneletSettings.KEY_BACKENDS_ENV_SCRIPT, "NONE")
        assertNull(BackendRunner.resolveEnvScript())
    }

    @Test
    fun missingConcretePythonIsAResultNotAnException(@TempDir dir: Path) {
        File(dir.toFile(), "positive_ids.py").writeText("print('hi')\n")
        val runner = BackendRunner(
            python = { "/definitely/missing/python3" },
            backendsDir = { dir.toFile() },
            envScript = { null },
        )
        val result = runner.run("positive_ids", emptyList())
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
    }
}
