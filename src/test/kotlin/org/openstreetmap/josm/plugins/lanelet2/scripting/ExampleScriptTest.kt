package org.openstreetmap.josm.plugins.lanelet2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ExampleScriptTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun registerAllPutsCopyActionOnUtilsMenu() {
        val registry = ActionRegistry()
        ExampleScript.registerAll(registry)
        val utilsIds = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        assertTrue(ExampleScript.SLOT_ID in utilsIds)
        assertTrue(ExampleScript.SLOT_ID in ActionRegistry.BASE_UTILS_ORDER)
    }

    @Test
    fun copyToWritesTheShippedExample(@TempDir dir: Path) {
        val dest = dir.resolve("out.py").toFile()
        val written = ExampleScript.copyTo(dest)
        assertEquals(dest, written)
        val text = written.readText()
        assertTrue(text.isNotBlank())
        assertTrue("Lanelet2Extensions" in text)
        assertTrue("hello_lanelet2" in text)
        val intoDir = ExampleScript.copyTo(dir.toFile())
        assertEquals(ExampleScript.FILENAME, intoDir.name)
        assertEquals(text, intoDir.readText())
    }

    @Test
    fun shippedExampleHasNoPython3OnlySyntax() {
        val text = ExampleScript.readText()
        assertPython2Safe(text)
    }

    /**
     * Stronger than [assertPython2Safe], which can only blacklist known tokens:
     * this compiles the example with a real Python 2 grammar. `py_compile` only
     * parses, so the script's Java/JOSM imports are irrelevant here. Skipped
     * where no Python 2 exists, so CI without it still passes.
     */
    @Test
    fun shippedExampleCompilesUnderRealPython2(@TempDir dir: Path) {
        val python2 = locatePython2()
        assumeTrue(python2 != null, "no Python 2 interpreter available")
        val script = dir.resolve(ExampleScript.FILENAME).toFile()
        script.writeText(ExampleScript.readText())
        val proc = ProcessBuilder(python2!!, "-m", "py_compile", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "python2 compile timed out")
        assertEquals(0, proc.exitValue(), "example script is not valid Python 2:\n$output")
    }

    private fun locatePython2(): String? {
        val path = System.getenv("PATH")?.split(File.pathSeparatorChar) ?: return null
        for (name in listOf("python2", "python2.7")) {
            for (entry in path) {
                val candidate = File(entry, name)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }
}

internal fun assertPython2Safe(text: String) {
    assertTrue(text.isNotBlank(), "example script is empty")
    val forbidden = listOf(
        "f\"", "f'", "rf\"", "fr\"", "F\"", "F'",
        "async ", "await ",
        "yield from",
        ":=",
        "nonlocal ",
    )
    for (token in forbidden) {
        assertFalse(token in text, "Python 3-only syntax '$token' in example script")
    }
    assertFalse(
        Regex("""def\s+\w+\s*\([^)]*:""").containsMatchIn(text),
        "type-annotated def is Python 3-only",
    )
    assertFalse(
        Regex("""raise\s+.+\s+from\s+""").containsMatchIn(text),
        "raise ... from is Python 3-only",
    )
}
