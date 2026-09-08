package org.openstreetmap.josm.plugins.lanelet2.mrt

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.PluginClassLoader
import org.openstreetmap.josm.tools.ResourceProvider
import java.io.File
import java.util.jar.JarFile

/**
 * Proves the bundled MapCSS styles and tagging presets are reachable the way
 * they will be at runtime: as `resource://` URLs served out of the plugin jar
 * by a plugin classloader registered with [ResourceProvider], which is what
 * `PluginHandler` does when it loads a plugin.
 *
 * Living in plugin-mrt is deliberate: plugin-core's resources are not on this
 * module's classpath, so a resource that resolves here can only have come from
 * the jar under test.
 */
class JarResourceLoadingTest {

    private val coreJar = File(requireNotNull(System.getProperty("lanelet2.coreJar")))

    private companion object {
        const val STYLE_DIR = "lanelet2"
        val MAPCSS_FILES = listOf(
            "lines.mapcss", "lanelets.mapcss", "routing.mapcss", "debug_routing_graph.mapcss"
        )
        const val PRESETS_FILE = "ll2_editor_presets.xml"

        /** Matches the relative `style_images/foo.png` references used by both file kinds. */
        val STYLE_IMAGE_REF = Regex("""style_images/[A-Za-z0-9_.\-]+""")
    }

    private fun isolatedPluginClassLoader(): ClassLoader =
        PluginClassLoader(arrayOf(coreJar.toURI().toURL()), javaClass.classLoader, null)

    @Test
    fun `styles and presets resolve through ResourceProvider once the plugin loader is registered`() {
        val loader = isolatedPluginClassLoader()
        ResourceProvider.addAdditionalClassLoader(loader)

        (MAPCSS_FILES + PRESETS_FILE).forEach { name ->
            assertNotNull(
                ResourceProvider.getResource("$STYLE_DIR/$name"),
                "resource://$STYLE_DIR/$name must resolve from the plugin jar"
            )
        }
    }

    @Test
    fun `action icons resolve under the images prefix ImageProvider searches`() {
        val loader = isolatedPluginClassLoader()
        ResourceProvider.addAdditionalClassLoader(loader)

        listOf("create_lanelet", "split_way", "routing_graph").forEach { icon ->
            assertNotNull(
                ResourceProvider.getResource("images/lanelet2/$icon.svg"),
                "icon images/lanelet2/$icon.svg must ship in the plugin jar"
            )
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

        JarFile(coreJar).use { jar ->
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
        JarFile(coreJar).use { jar ->
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
}
