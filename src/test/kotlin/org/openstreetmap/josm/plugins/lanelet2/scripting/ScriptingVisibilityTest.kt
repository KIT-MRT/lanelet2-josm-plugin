package org.openstreetmap.josm.plugins.lanelet2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.PluginClassLoader
import org.openstreetmap.josm.plugins.PluginHandler
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.net.URLClassLoader

/**
 * Headless stand-in for the Scripting plugin's JSR-223 engine classloader.
 *
 * `JSR223ScriptEngineProvider.buildClassLoader()` (scripting.jar v0.4.3) does
 * `new URLClassLoader(engineJarUrls, JSR223ScriptEngineProvider.class.getClassLoader())`
 * when engine jars are configured, otherwise it assigns the provider's own
 * loader (the scripting [PluginClassLoader]). Either way the parent of the
 * engine loader is that PluginClassLoader, so [PluginClassLoader.addDependency]
 * is what makes our classes visible to a Jython `import`.
 *
 * The GraalPy plugin is simpler: its polyglot context uses the GraalPy
 * [PluginClassLoader] as `hostClassLoader` directly (no child
 * `URLClassLoader`). The same [ScriptingVisibility.exposeTo] call is what
 * makes `from …lanelet2.api import Lanelet2Extensions` work there.
 */
class ScriptingVisibilityTest {

    private val facadeName =
        "org.openstreetmap.josm.plugins.lanelet2.api.Lanelet2Extensions"

    @BeforeEach
    fun prefs() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    private val pluginJar = File(
        requireNotNull(System.getProperty("lanelet2.jar")) {
            "system property lanelet2.jar not set by the build"
        },
    ).also { assertTrue(it.isFile, "expected the packaged plugin jar at $it") }

    @Test
    fun missingScriptingPluginIsALoggedNoOp() {
        assertEquals(
            ExposeResult.SCRIPTING_ABSENT,
            ScriptingVisibility.exposeTo(null, javaClass.classLoader),
        )
        // PluginHandler has no scripting plugin in this headless JVM.
        assertEquals(
            null,
            PluginHandler.getPluginClassLoader(ScriptingVisibility.SCRIPTING_PLUGIN_NAME),
        )
        assertEquals(
            null,
            PluginHandler.getPluginClassLoader(ScriptingVisibility.GRAALPY_PLUGIN_NAME),
        )
        ScriptingVisibility.exposeOurClassesToScriptingPlugin()
    }

    @Test
    fun nonPluginOurLoaderDoesNotThrow() {
        val scripting = PluginClassLoader(emptyArray(), null, null)
        assertEquals(
            ExposeResult.NOT_A_PLUGIN_LOADER,
            ScriptingVisibility.exposeTo(scripting, javaClass.classLoader),
        )
    }

    @Test
    fun facadeIsReachableThroughTheEngineLoaderAfterInjection() {
        val ourLoader = PluginClassLoader(
            arrayOf(pluginJar.toURI().toURL()),
            javaClass.classLoader,
            null,
        )
        // Null parent: the scripting loader cannot see the test classpath
        // (and therefore cannot see our classes) until we inject.
        val scriptingLoader = PluginClassLoader(emptyArray(), null, null)
        val engineLoader = URLClassLoader(emptyArray(), scriptingLoader)
        assertThrows(ClassNotFoundException::class.java) {
            engineLoader.loadClass(facadeName)
        }
        assertEquals(
            ExposeResult.INJECTED,
            ScriptingVisibility.exposeTo(scriptingLoader, ourLoader),
        )
        assertEquals(
            ExposeResult.ALREADY_PRESENT,
            ScriptingVisibility.exposeTo(scriptingLoader, ourLoader),
        )
        val loaded = engineLoader.loadClass(facadeName)
        assertNotNull(loaded)
        assertEquals(facadeName, loaded.name)
        val settings = loaded.getMethod("settings").invoke(null)
        val get = settings.javaClass.getMethod("get", String::class.java, String::class.java)
        assertEquals("road", get.invoke(settings, "lanelet.default_subtype", "road"))
        val anchors = loaded.getMethod("anchors").invoke(null) as List<*>
        assertTrue(anchors.contains("lanelet_edit.check_lanelet_borders"))
    }
}
