package org.openstreetmap.josm.plugins.lanelet2.scripting

import org.openstreetmap.josm.plugins.PluginClassLoader
import org.openstreetmap.josm.plugins.PluginHandler
import org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin
import org.openstreetmap.josm.tools.Logging

/**
 * Makes this plugin's classes visible to scripts running under the JOSM
 * Scripting plugin.
 *
 * JOSM gives each plugin its own [PluginClassLoader]; the joined loader is
 * resources-only (`PluginHandler.getJoinedPluginResourceCL`). The Scripting
 * plugin (v0.4.3) builds a JSR-223 engine loader as
 * `new URLClassLoader(engineJarUrls, JSR223ScriptEngineProvider.class.getClassLoader())`,
 * so the parent is the scripting plugin's [PluginClassLoader]. Injecting our
 * loader there via [PluginClassLoader.addDependency] is what lets
 * `from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions`
 * succeed.
 *
 * A missing or incompatible Scripting plugin is a logged no-op: this must
 * never prevent plugin startup.
 */
object ScriptingVisibility {
    const val SCRIPTING_PLUGIN_NAME = "scripting"

    /**
     * Look up the Scripting plugin's loader and inject ours. Safe to call
     * more than once (addDependency is idempotent).
     */
    fun exposeOurClassesToScriptingPlugin() {
        try {
            val scripting = PluginHandler.getPluginClassLoader(SCRIPTING_PLUGIN_NAME)
            exposeTo(scripting, Lanelet2Plugin::class.java.classLoader)
        } catch (t: Throwable) {
            Logging.warn(
                "lanelet2: could not expose classes to the scripting plugin: {0}",
                t.message,
            )
            Logging.trace(t)
        }
    }

    /**
     * Inject [ourLoader] into [scriptingLoader] so a child engine loader
     * can see this plugin's classes.
     *
     * [ourLoader] must be a [PluginClassLoader] — that is what JOSM gives
     * us at runtime. The test classpath is not, and is reported as
     * [ExposeResult.NOT_A_PLUGIN_LOADER] rather than thrown.
     */
    fun exposeTo(scriptingLoader: PluginClassLoader?, ourLoader: ClassLoader): ExposeResult {
        if (scriptingLoader == null) {
            Logging.info(
                "lanelet2: scripting plugin is not loaded; " +
                    "Jython scripts cannot import plugin classes",
            )
            return ExposeResult.SCRIPTING_ABSENT
        }
        val ours = ourLoader as? PluginClassLoader
        if (ours == null) {
            Logging.info(
                "lanelet2: our loader is not a PluginClassLoader; " +
                    "skip scripting injection",
            )
            return ExposeResult.NOT_A_PLUGIN_LOADER
        }
        return try {
            val added = scriptingLoader.addDependency(ours)
            if (added) {
                Logging.info("lanelet2: exposed plugin classes to the scripting plugin")
                ExposeResult.INJECTED
            } else {
                ExposeResult.ALREADY_PRESENT
            }
        } catch (t: Throwable) {
            Logging.warn(
                "lanelet2: failed to inject classloader into the scripting plugin: {0}",
                t.message,
            )
            Logging.trace(t)
            ExposeResult.FAILED
        }
    }
}

/** Outcome of [ScriptingVisibility.exposeTo]. Public for headless tests. */
enum class ExposeResult {
    INJECTED,
    ALREADY_PRESENT,
    SCRIPTING_ABSENT,
    NOT_A_PLUGIN_LOADER,
    FAILED,
}
