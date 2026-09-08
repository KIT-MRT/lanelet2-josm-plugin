package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/**
 * Guards the preset icon search path.
 *
 * Omitting this registration is not a loud failure: presets still load and the
 * plugin looks fine, but every relative `style_images/...` icon silently fails
 * to resolve, which is exactly the regression that shipped once already.
 */
class TaggingPresetsInstallerTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        // AbstractProperty captures Config.getPref() into a final field in its
        // constructor, and ICON_SOURCES is a static, so swapping the Config
        // instance above does not isolate it: it keeps reading and writing the
        // preferences captured when TaggingPresets was class-loaded. Reset the
        // value through the property itself to get a known baseline per test.
        TaggingPresets.ICON_SOURCES.put(emptyList())
    }

    @Test
    fun iconSourceIsRegisteredForTheBundledJarLocation() {
        assertTrue(TaggingPresetsInstaller.ensureIconSource(), "first call must register the icon source")
        assertTrue(
            TaggingPresetsInstaller.ICON_SOURCE in TaggingPresets.ICON_SOURCES.get(),
            "icon source must be present after registration",
        )
    }

    @Test
    fun iconSourceRegistrationIsIdempotent() {
        assertTrue(TaggingPresetsInstaller.ensureIconSource())
        assertFalse(TaggingPresetsInstaller.ensureIconSource(), "second call must be a no-op")
        assertEquals(
            1,
            TaggingPresets.ICON_SOURCES.get().count { it == TaggingPresetsInstaller.ICON_SOURCE },
            "the icon source must not accumulate duplicates across launches",
        )
    }

    @Test
    fun iconSourceRegistrationPreservesUserEntries() {
        val userDir = "/home/someone/my-preset-icons"
        TaggingPresets.ICON_SOURCES.put(listOf(userDir))

        assertTrue(TaggingPresetsInstaller.ensureIconSource())

        val sources = TaggingPresets.ICON_SOURCES.get()
        assertTrue(userDir in sources, "must not drop icon directories the user configured")
        assertTrue(TaggingPresetsInstaller.ICON_SOURCE in sources)
    }

    /**
     * `ImageProvider.getImageUrl` strips the `resource://` prefix and concatenates
     * the icon name directly, so the trailing slash is what makes
     * `style_images/x.png` resolve to `lanelet2/style_images/x.png`.
     */
    @Test
    fun iconSourceIsAResourceUrlEndingInASlash() {
        assertTrue(TaggingPresetsInstaller.ICON_SOURCE.startsWith("resource://"))
        assertTrue(TaggingPresetsInstaller.ICON_SOURCE.endsWith("/"))
        assertEquals("resource://lanelet2/", TaggingPresetsInstaller.ICON_SOURCE)
    }
}
