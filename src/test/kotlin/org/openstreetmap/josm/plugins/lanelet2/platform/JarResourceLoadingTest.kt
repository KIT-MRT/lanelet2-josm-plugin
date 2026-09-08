package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.tools.ResourceProvider
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Proves the bundled MapCSS styles, tagging presets and icons are reachable the
 * way they will be at runtime: out of the packaged plugin jar, served by a
 * plugin classloader, which is what `PluginHandler` sets up when loading us.
 *
 * Everything here is asserted against the built jar rather than the test
 * classpath. Those resources are also on this module's classpath, so a plain
 * [ResourceProvider] lookup would pass even if packaging were broken; the
 * loader used below has a null parent and therefore sees only the jar.
 */
class JarResourceLoadingTest {

    private val pluginJar = File(
        requireNotNull(System.getProperty("lanelet2.jar")) { "system property lanelet2.jar not set by the build" }
    ).also { assertTrue(it.isFile, "expected the packaged plugin jar at $it") }

    private companion object {
        const val STYLE_DIR = "lanelet2"
        val MAPCSS_FILES = listOf(
            "lines.mapcss", "lanelets.mapcss", "routing.mapcss", "debug_routing_graph.mapcss"
        )
        const val PRESETS_FILE = "ll2_editor_presets.xml"

        /** Matches the relative `style_images/foo.png` references used by both file kinds. */
        val STYLE_IMAGE_REF = Regex("""style_images/[A-Za-z0-9_.\-]+""")
    }

    /** Null parent: resolves only out of the jar, never the surrounding test classpath. */
    private fun jarOnlyLoader() = URLClassLoader(arrayOf(pluginJar.toURI().toURL()), null)

    @Test
    fun `styles and presets are served out of the packaged jar`() {
        jarOnlyLoader().use { loader ->
            (MAPCSS_FILES + PRESETS_FILE).forEach { name ->
                assertNotNull(
                    loader.getResource("$STYLE_DIR/$name"),
                    "resource://$STYLE_DIR/$name must resolve from the plugin jar"
                )
            }
        }
    }

    @Test
    fun `action icons ship under the images prefix ImageProvider searches`() {
        jarOnlyLoader().use { loader ->
            listOf("create_lanelet", "split_way", "routing_graph").forEach { icon ->
                assertNotNull(
                    loader.getResource("images/lanelet2/$icon.svg"),
                    "icon images/lanelet2/$icon.svg must ship in the plugin jar"
                )
            }
        }
    }

    @Test
    fun `ResourceProvider serves plugin resources once the loader is registered`() {
        jarOnlyLoader().use { loader ->
            ResourceProvider.addAdditionalClassLoader(loader)
            try {
                val url = ResourceProvider.getResource("$STYLE_DIR/$PRESETS_FILE")
                assertNotNull(url, "presets must resolve through ResourceProvider")
                assertTrue(
                    url!!.toString().contains(pluginJar.name),
                    "must be served from ${pluginJar.name}, not the test classpath, but was $url"
                )
            } finally {
                ResourceProvider.removeAdditionalClassLoader(loader)
            }
        }
    }

    /**
     * The Jython repo ships MapCSS referencing ~96 traffic-sign icons that were
     * never added, so they silently do not render. Rather than block the port on
     * artwork, the gap is pinned to a baseline: this fails if it grows, and the
     * baseline file shrinks as icons are supplied.
     */
    @Test
    fun `style_images references resolve, except the known inherited gap`() {
        val knownMissing = javaClass.getResourceAsStream("/known-missing-style-images.txt")
            .let { requireNotNull(it) { "baseline file missing" } }
            .bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSortedSet()

        JarFile(pluginJar).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()

            val missing = (MAPCSS_FILES + PRESETS_FILE)
                .flatMap { name ->
                    val entry = requireNotNull(jar.getJarEntry("$STYLE_DIR/$name")) { "$name missing from jar" }
                    val text = jar.getInputStream(entry).bufferedReader().readText()
                    STYLE_IMAGE_REF.findAll(text).map { it.value }.toList()
                }
                .filterNot { "$STYLE_DIR/$it" in entries }
                .map { it.removePrefix("style_images/") }
                .toSortedSet()

            val newlyBroken = missing - knownMissing
            assertTrue(
                newlyBroken.isEmpty(),
                "MapCSS/presets reference style images that are not in the jar: $newlyBroken"
            )

            val fixed = knownMissing - missing
            assertTrue(
                fixed.isEmpty(),
                "these icons now resolve, remove them from known-missing-style-images.txt: $fixed"
            )
        }
    }

    @Test
    fun `style_images is shared by styles and presets rather than duplicated`() {
        JarFile(pluginJar).use { jar ->
            val imageDirs = jar.entries().asSequence()
                .map { it.name }
                .filter { it.contains("style_images/") && !it.endsWith("/") }
                .map { it.substringBefore("style_images/") + "style_images/" }
                .toSet()
            assertTrue(
                imageDirs == setOf("$STYLE_DIR/style_images/"),
                "expected exactly one shared style_images dir, found $imageDirs"
            )
        }
    }

    @Test
    fun `kotlin stdlib is packed because JOSM does not provide it`() {
        JarFile(pluginJar).use { jar ->
            assertTrue(
                jar.entries().asSequence().any { it.name.startsWith("kotlin/") },
                "the plugin must pack the Kotlin stdlib, JOSM does not provide it"
            )
        }
    }

    @Test
    fun `manifest declares the plugin entry point and requires no sibling plugin`() {
        JarFile(pluginJar).use { jar ->
            val attributes = jar.manifest.mainAttributes
            assertEquals(
                "org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin",
                attributes.getValue("Plugin-Class")
            )
            assertNull(
                attributes.getValue("Plugin-Requires"),
                "this ships as a single plugin; a Plugin-Requires entry would make JOSM refuse to load it"
            )
        }
    }
}
