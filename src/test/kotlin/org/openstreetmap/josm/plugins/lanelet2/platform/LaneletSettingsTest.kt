package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class LaneletSettingsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun parseLegacyFileSkipsCommentsBlanksAndSplitsOnFirstEquals(@TempDir dir: Path) {
        val file = dir.resolve("legacy.settings").toFile()
        file.writeText(
            """
            # comment
            routing.auto_debounce_ms=1234

            backends.python=/usr/bin/python3
            foo=a=b
            =ignored
            lanelet.default_subtype=bicycle_lane
            """.trimIndent(),
        )
        val parsed = parseLegacySettingsFile(file)
        assertEquals("1234", parsed["routing.auto_debounce_ms"])
        assertEquals("/usr/bin/python3", parsed["backends.python"])
        assertEquals("a=b", parsed["foo"])
        assertEquals("bicycle_lane", parsed["lanelet.default_subtype"])
        assertFalse(parsed.containsKey(""))
        assertEquals(4, parsed.size)
    }

    @Test
    fun migrateLegacyFileOnceWritesPrefixedKeysAndDoesNotDeleteFile(@TempDir dir: Path) {
        val file = dir.resolve(".lanelet2_settings").toFile()
        file.writeText(
            """
            routing.auto_debounce_ms=4321
            routing.hook_full_map=1
            lanelet.default_subtype=crosswalk
            merge_anchor.protect=0
            """.trimIndent(),
        )

        LaneletSettings.migrateLegacyFileOnce(file)

        assertEquals("4321", LaneletSettings.get("routing.auto_debounce_ms", ""))
        assertEquals("4321", Config.getPref().get("lanelet2.routing.auto_debounce_ms", ""))
        assertEquals(4321, LaneletSettings.getRoutingAutoDebounceMs())
        assertTrue(LaneletSettings.getRoutingHookFullMap())
        assertEquals("crosswalk", LaneletSettings.getLaneletDefaultSubtype())
        assertFalse(LaneletSettings.getProtectMergeAnchors())
        assertTrue(LaneletSettings.getBoolean("migrated", false))
        assertTrue(file.isFile)

        file.writeText("routing.auto_debounce_ms=9999\n")
        LaneletSettings.migrateLegacyFileOnce(file)
        assertEquals("4321", LaneletSettings.get("routing.auto_debounce_ms", ""))
        assertTrue(file.isFile)
    }

    @Test
    fun migrateLegacyFileOnceNoopsWhenFileMissing(@TempDir dir: Path) {
        val missing = File(dir.toFile(), "does-not-exist")
        LaneletSettings.migrateLegacyFileOnce(missing)
        assertFalse(LaneletSettings.getBoolean("migrated", false))
        assertEquals("road", LaneletSettings.getLaneletDefaultSubtype())
    }

    @Test
    fun persistBackendInterpreterUsesPrefixedKeysAndNoneEnvScript() {
        LaneletSettings.persistBackendInterpreter("/tmp/venv/bin/python", "/tmp/backends")
        assertEquals("/tmp/venv/bin/python", LaneletSettings.getBackendsPython())
        assertEquals("/tmp/backends", LaneletSettings.getBackendsDir())
        assertEquals("none", LaneletSettings.get("backends.env_script", ""))
        assertEquals("/tmp/venv/bin/python", Config.getPref().get("lanelet2.backends.python", null))
        assertNull(Config.getPref().get("backends.python", null))
    }

    @Test
    fun routingDefaultParticipantFallsBackWhenUnknown() {
        assertEquals("vehicle", LaneletSettings.getRoutingDefaultParticipant())
        LaneletSettings.setRoutingDefaultParticipant("bicycle")
        assertEquals("bicycle", LaneletSettings.getRoutingDefaultParticipant())
        LaneletSettings.setRoutingDefaultParticipant("spaceship")
        assertEquals("vehicle", LaneletSettings.getRoutingDefaultParticipant())
        assertEquals("vehicle", Config.getPref().get("lanelet2.routing.default_participant", null))
    }

    @Test
    fun getAndPutUseLanelet2Prefix() {
        LaneletSettings.put("backends.dir", "/tmp/backends")
        assertEquals("/tmp/backends", LaneletSettings.get("backends.dir", ""))
        assertEquals("/tmp/backends", Config.getPref().get("lanelet2.backends.dir", ""))
        assertEquals("", Config.getPref().get("backends.dir", ""))
    }

    @Test
    fun getBooleanUnderstandsLegacyOneAndZero() {
        LaneletSettings.put("routing.hook_full_map", "1")
        assertTrue(LaneletSettings.getBoolean("routing.hook_full_map", false))
        LaneletSettings.put("routing.hook_full_map", "0")
        assertFalse(LaneletSettings.getBoolean("routing.hook_full_map", true))
    }

    @Test
    fun getIntAndGetList() {
        LaneletSettings.put("routing.auto_debounce_ms", "2500")
        assertEquals(2500, LaneletSettings.getInt("routing.auto_debounce_ms", 0))
        LaneletSettings.put("presets.toolbar.names", "Virtual line, Dashed line")
        assertEquals(
            listOf("Virtual line", "Dashed line"),
            LaneletSettings.getList("presets.toolbar.names", emptyList()),
        )
    }

    @Test
    fun debounceMsIsClamped() {
        LaneletSettings.setRoutingAutoDebounceMs(-10)
        assertEquals(0, LaneletSettings.getRoutingAutoDebounceMs())
        LaneletSettings.setRoutingAutoDebounceMs(99_999)
        assertEquals(60_000, LaneletSettings.getRoutingAutoDebounceMs())
    }

    @Test
    fun doesNotReadHomeWhenPointedAtTempFile(@TempDir dir: Path) {
        val homeSettings = File(System.getProperty("user.home"), ".lanelet2_settings")
        val file = dir.resolve("legacy").toFile()
        file.writeText("lanelet.default_subtype=walkway\n")
        LaneletSettings.migrateLegacyFileOnce(file)
        assertEquals("walkway", LaneletSettings.getLaneletDefaultSubtype())
        if (homeSettings.isFile) {
            val homeParsed = parseLegacySettingsFile(homeSettings)
            val homeSubtype = homeParsed["lanelet.default_subtype"]
            if (homeSubtype != null && homeSubtype != "walkway") {
                assertEquals("walkway", LaneletSettings.get("lanelet.default_subtype", ""))
            }
        }
    }

    @Test
    fun gitCommitReminderDefaultsOffAndStoresOneZero() {
        assertFalse(LaneletSettings.getGitCommitReminder())
        LaneletSettings.setGitCommitReminder(true)
        assertTrue(LaneletSettings.getGitCommitReminder())
        assertEquals("1", Config.getPref().get("lanelet2.git.commit_reminder", null))
        LaneletSettings.setGitCommitReminder(false)
        assertFalse(LaneletSettings.getGitCommitReminder())
        assertEquals("0", Config.getPref().get("lanelet2.git.commit_reminder", null))
    }
}
