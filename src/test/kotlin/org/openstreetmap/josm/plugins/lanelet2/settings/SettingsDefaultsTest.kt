package org.openstreetmap.josm.plugins.lanelet2.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SettingsDefaultsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun laneletDefaultsMatchJython() {
        assertEquals("road", LaneletSettings.getLaneletDefaultSubtype())
        assertEquals(LaneletSettings.LOC_URBAN, LaneletSettings.getLaneletDefaultLocation())
        assertEquals(LaneletSettings.ONE_WAY_YES, LaneletSettings.getLaneletDefaultOneWay())
    }

    @Test
    fun editingDefaultsMatchJython() {
        assertTrue(LaneletSettings.getDeleteTaggedNodes())
        assertTrue(LaneletSettings.getProtectMergeAnchors())
        assertFalse(LaneletSettings.isCollectionDialogEnabled())
    }

    @Test
    fun mergeGridCellDefaultMatchesJython() {
        assertEquals(60.0, LaneletSettings.getMergeGridCellM(), 0.0)
    }

    @Test
    fun mapstyleAndPresetsLaunchDefaultsMatchJython() {
        assertTrue(LaneletSettings.getMapstyleAutoApplyOnLaunch())
        assertTrue(LaneletSettings.getPresetsAutoInstallOnLaunch())
        assertEquals(LaneletSettings.MAPSTYLE_PRESET_LL2_EDITING, LaneletSettings.getMapstyleLastPreset())
    }

    @Test
    fun settingsRoundTrip() {
        LaneletSettings.setLaneletDefaultSubtype("walkway")
        LaneletSettings.setLaneletDefaultLocation(LaneletSettings.LOC_NONURBAN)
        LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_NO)
        LaneletSettings.setDeleteTaggedNodes(false)
        LaneletSettings.setProtectMergeAnchors(false)
        LaneletSettings.setCollectionDialogEnabled(true)
        LaneletSettings.setMergeGridCellM(75.0)
        LaneletSettings.setMapstyleAutoApplyOnLaunch(false)
        LaneletSettings.setPresetsAutoInstallOnLaunch(false)

        assertEquals("walkway", LaneletSettings.getLaneletDefaultSubtype())
        assertEquals(LaneletSettings.LOC_NONURBAN, LaneletSettings.getLaneletDefaultLocation())
        assertEquals(LaneletSettings.ONE_WAY_NO, LaneletSettings.getLaneletDefaultOneWay())
        assertFalse(LaneletSettings.getDeleteTaggedNodes())
        assertFalse(LaneletSettings.getProtectMergeAnchors())
        assertTrue(LaneletSettings.isCollectionDialogEnabled())
        assertEquals(75.0, LaneletSettings.getMergeGridCellM(), 0.0)
        assertFalse(LaneletSettings.getMapstyleAutoApplyOnLaunch())
        assertFalse(LaneletSettings.getPresetsAutoInstallOnLaunch())
    }

    @Test
    fun mergeGridCellStoredAsIntWhenWhole() {
        LaneletSettings.setMergeGridCellM(60.0)
        assertEquals("60", LaneletSettings.get("merge.grid_cell_m", ""))
    }
}
