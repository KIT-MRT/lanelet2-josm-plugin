package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/**
 * The map paint style counterpart to [TaggingPresetsInstallerTest].
 *
 * Presets got their icon search path registered; the styles did not, so every
 * traffic sign, arrow and traffic light icon silently failed to render while
 * the styles themselves loaded and looked healthy.
 */
class MapStylesIconSourceTest {

    private val key = "mappaint.icon.sources"

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    private fun sources(): List<String> = Config.getPref().getList(key, emptyList())

    @Test
    fun iconSourceIsRegisteredForTheBundledJarLocation() {
        assertTrue(MapStyles.ensureIconSource(), "first call must register the icon source")
        assertTrue(
            TaggingPresetsInstaller.ICON_SOURCE in sources(),
            "styles must search the plugin's icon root",
        )
    }

    @Test
    fun iconSourceRegistrationIsIdempotent() {
        assertTrue(MapStyles.ensureIconSource())
        assertFalse(MapStyles.ensureIconSource(), "second call must be a no-op")
        assertEquals(
            1,
            sources().count { it == TaggingPresetsInstaller.ICON_SOURCE },
            "the icon source must not accumulate duplicates across launches",
        )
    }

    @Test
    fun iconSourceRegistrationPreservesUserEntries() {
        val userDir = "/home/someone/my-style-icons"
        Config.getPref().putList(key, listOf(userDir))

        assertTrue(MapStyles.ensureIconSource())

        val sources = sources()
        assertTrue(userDir in sources, "must not drop icon directories the user configured")
        assertTrue(TaggingPresetsInstaller.ICON_SOURCE in sources)
    }

    /**
     * Ties the registered root to the shipped layout: JOSM strips `resource://`
     * and concatenates the style's relative icon name onto it, so this is the
     * path a `icon-image: "style_images/..."` rule actually ends up requesting.
     */
    @Test
    fun registeredRootResolvesTheIconsTheStylesRefer() {
        val styles = listOf("lanelets.mapcss", "lines.mapcss", "routing.mapcss", "debug_routing_graph.mapcss")
        val referenced = styles.flatMap { style ->
            val text = javaClass.classLoader.getResourceAsStream("lanelet2/$style")
                ?.bufferedReader()?.readText()
                ?: error("bundled style $style missing from the jar")
            ICON_RULE.findAll(text).map { it.groupValues[1] }
        }.filter { it.startsWith("style_images/") }.distinct()

        assertTrue(referenced.isNotEmpty(), "shipped styles reference no local icons at all")

        // Upstream refers to more signs than it ships; what matters is that the
        // ones we do ship are reachable through the path JOSM will search.
        val root = TaggingPresetsInstaller.ICON_SOURCE.removePrefix("resource://")
        val shipped = referenced.filter { javaClass.classLoader.getResource("lanelet2/$it") != null }
        assertTrue(shipped.size > 100, "expected the bundled sign set, found ${shipped.size}")
        for (icon in shipped) {
            assertNotNull(
                javaClass.classLoader.getResource(root + icon),
                "style icon $icon is not reachable under the registered root",
            )
        }
    }

    private companion object {
        val ICON_RULE = Regex("""icon-image:\s*"([^"]+)"""")
    }
}
