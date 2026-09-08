package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SidecarHealthTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        Sidecar.resetOverrides()
        BackendSetupWizard.showHandler = null
    }

    @Test
    fun supportedVersionsAre38To311() {
        assertFalse(SidecarHealth.isSupportedVersion(2, 7))
        assertFalse(SidecarHealth.isSupportedVersion(3, 7))
        assertTrue(SidecarHealth.isSupportedVersion(3, 8))
        assertTrue(SidecarHealth.isSupportedVersion(3, 11))
        assertFalse(SidecarHealth.isSupportedVersion(3, 12))
        assertFalse(SidecarHealth.isSupportedVersion(4, 0))
    }

    @Test
    fun classifyHealthy() {
        val r = SidecarHealth.classify("PY=3.10\nLANELET2=ok\n", started = true)
        assertTrue(r.healthy)
        assertEquals("3.10", r.pythonVersion)
        assertEquals(SidecarProblem.HEALTHY, r.problem)
    }

    @Test
    fun classifyInterpreterMissingWhenNotStarted() {
        val r = SidecarHealth.classify("", started = false, startError = "No such file")
        assertEquals(SidecarProblem.INTERPRETER_MISSING, r.problem)
        assertTrue(r.userMessage().contains("Set up Lanelet2 backends"))
    }

    @Test
    fun classifyWrongPythonVersionBeforeImportFailure() {
        val r = SidecarHealth.classify("PY=3.12\nLANELET2=fail:ModuleNotFoundError\n", started = true)
        assertEquals(SidecarProblem.WRONG_PYTHON_VERSION, r.problem)
        assertEquals("3.12", r.pythonVersion)
        assertTrue(r.userMessage().contains("3.8 to 3.11"))
    }

    @Test
    fun classifyLanelet2ImportFailsOnSupportedVersion() {
        val r = SidecarHealth.classify("PY=3.10\nLANELET2=fail:ModuleNotFoundError\n", started = true)
        assertEquals(SidecarProblem.LANELET2_IMPORT_FAILED, r.problem)
        assertTrue(r.userMessage().contains("import lanelet2"))
    }

    @Test
    fun classifyJythonAsWrongVersion() {
        val r = SidecarHealth.classify("PY=2.7\nLANELET2=fail:ImportError\n", started = true)
        assertEquals(SidecarProblem.WRONG_PYTHON_VERSION, r.problem)
    }

    @Test
    fun platformCheckAcceptsLinuxAmd64() {
        assertTrue(SidecarHealth.isSupportedPlatform("Linux", "amd64"))
        assertTrue(SidecarHealth.isSupportedPlatform("Linux", "x86_64"))
        assertFalse(SidecarHealth.isSupportedPlatform("Windows 11", "amd64"))
        assertFalse(SidecarHealth.isSupportedPlatform("Linux", "aarch64"))
        assertFalse(SidecarHealth.isSupportedPlatform("Mac OS X", "x86_64"))
    }

    @Test
    fun probeMissingConcretePath() {
        val r = SidecarHealth.probe("/no/such/lanelet2/python")
        assertEquals(SidecarProblem.INTERPRETER_MISSING, r.problem)
        assertFalse(r.healthy)
    }

    @Test
    fun ensureUsableWarnsAndDoesNotThrowWhenUnhealthy() {
        Sidecar.healthOverride = HealthReport(
            SidecarProblem.LANELET2_IMPORT_FAILED,
            detail = "ModuleNotFoundError",
            python = "python3",
            pythonVersion = "3.10",
        )
        val ui = RecordingPrompts()
        val ok = Sidecar.ensureUsable("Make Positive IDs", ui)
        assertFalse(ok)
        assertEquals(1, ui.warnings.size)
        assertTrue(ui.warnings[0].first.contains("import lanelet2"))
        assertTrue(ui.warnings[0].first.contains("Set up Lanelet2 backends"))
        assertEquals("Make Positive IDs", ui.warnings[0].second)
        assertTrue(ui.confirms.isNotEmpty(), "should offer to open the wizard")
    }

    @Test
    fun ensureUsableReturnsTrueWhenHealthyWithoutDialogs() {
        Sidecar.healthOverride = HealthReport(SidecarProblem.HEALTHY, pythonVersion = "3.10")
        val ui = RecordingPrompts()
        assertTrue(Sidecar.ensureUsable("Make Positive IDs", ui))
        assertTrue(ui.warnings.isEmpty())
    }

    @Test
    fun userMessagesAreDistinctPerProblem() {
        val msgs = SidecarProblem.entries
            .filter { it != SidecarProblem.HEALTHY }
            .map { HealthReport(it, detail = "d", python = "p", pythonVersion = "3.12").userMessage() }
        assertEquals(msgs.size, msgs.toSet().size, "each problem must have a distinct message")
    }
}

class RecordingPrompts(
    var confirmResult: Boolean = false,
) : UserPrompts {
    val warnings = mutableListOf<Pair<String, String>>()
    val infos = mutableListOf<Pair<String, String>>()
    val errors = mutableListOf<Pair<String, String>>()
    val confirms = mutableListOf<Pair<String, String>>()
    val options = mutableListOf<String>()
    val picks = mutableListOf<String>()
    val asks = mutableListOf<String>()
    var nextOption: String? = null
    var nextPick: String? = null
    var nextAsk: String? = null

    override fun warn(message: String, title: String) {
        warnings.add(message to title)
    }

    override fun info(message: String, title: String) {
        infos.add(message to title)
    }

    override fun infoAutoClose(message: String, title: String, delayMs: Int) {
        infos.add(message to title)
    }

    override fun confirm(message: String, title: String): Boolean {
        confirms.add(message to title)
        return confirmResult
    }

    override fun pick(title: String, message: String, options: List<String>): String? {
        this.options.addAll(options)
        return nextPick
    }

    override fun ask(title: String, message: String): String? = nextAsk

    override fun error(message: String, title: String) {
        errors.add(message to title)
    }

    override fun option(title: String, message: String, options: List<String>): String? {
        this.options.addAll(options)
        return nextOption
    }
}
