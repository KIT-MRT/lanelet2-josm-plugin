package org.openstreetmap.josm.plugins.lanelet2.mrt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.PluginClassLoader
import java.io.File
import java.util.jar.JarFile

/**
 * Headless proof of the cross-plugin wiring this project depends on.
 *
 * At runtime JOSM reads `Plugin-Requires` and turns it into
 * [PluginClassLoader.addDependency], which is what makes plugin-core's classes
 * (and the Kotlin stdlib packed into its jar) reachable from plugin-mrt. The
 * same mechanism is later used to expose the scripting facade to the Scripting
 * plugin, so it is worth a regression test rather than a one-off manual check.
 */
class PluginRequiresChainTest {

    private val coreJar = jarFromSystemProperty("lanelet2.coreJar")
    private val mrtJar = jarFromSystemProperty("lanelet2.mrtJar")

    private fun jarFromSystemProperty(key: String): File {
        val path = requireNotNull(System.getProperty(key)) { "system property $key not set by the build" }
        return File(path).also { assertTrue(it.isFile, "expected jar at $path") }
    }

    private fun loaderFor(jar: File, dependencies: List<PluginClassLoader>?) =
        PluginClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader, dependencies)

    @Test
    fun `mrt resolves core plugin classes through its dependency`() {
        val core = loaderFor(coreJar, null)
        val mrt = loaderFor(mrtJar, listOf(core))

        assertNotNull(mrt.loadClass("org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin"))
        assertNotNull(mrt.loadClass("org.openstreetmap.josm.plugins.lanelet2.mrt.Lanelet2MrtPlugin"))
    }

    @Test
    fun `without the dependency the core plugin class is not reachable`() {
        // plugin-core is a compileOnly dependency, so it is absent from the test
        // runtime classpath and this negative case is meaningful.
        val mrt = loaderFor(mrtJar, null)

        assertThrows(ClassNotFoundException::class.java) {
            mrt.loadClass("org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin")
        }
    }

    @Test
    fun `kotlin stdlib is packed into core only and shared over the chain`() {
        JarFile(coreJar).use { jar ->
            assertTrue(
                jar.entries().asSequence().any { it.name.startsWith("kotlin/") },
                "plugin-core must pack the Kotlin stdlib, JOSM does not provide it"
            )
        }
        JarFile(mrtJar).use { jar ->
            assertTrue(
                jar.entries().asSequence().none { it.name.startsWith("kotlin/") },
                "plugin-mrt must not duplicate the Kotlin stdlib, it inherits core's copy"
            )
        }
    }

    @Test
    fun `mrt manifest declares the core plugin as required`() {
        JarFile(mrtJar).use { jar ->
            val attributes = jar.manifest.mainAttributes
            assertEquals("lanelet2", attributes.getValue("Plugin-Requires"))
            assertEquals(
                "org.openstreetmap.josm.plugins.lanelet2.mrt.Lanelet2MrtPlugin",
                attributes.getValue("Plugin-Class")
            )
        }
    }
}
