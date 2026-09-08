package org.openstreetmap.josm.plugins.lanelet2.settings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane
import org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin
import org.openstreetmap.josm.plugins.lanelet2.hooks.AutotagHook
import org.openstreetmap.josm.plugins.lanelet2.hooks.AutotagLogic
import org.openstreetmap.josm.plugins.lanelet2.hooks.AutotagSettingsPanel
import org.openstreetmap.josm.plugins.lanelet2.hooks.ZoomFilterHook
import org.openstreetmap.josm.plugins.lanelet2.hooks.ZoomFilterLogic
import org.openstreetmap.josm.plugins.lanelet2.hooks.ZoomFilterSettingsPanel
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class Lanelet2PreferenceSettingTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        AutotagHook.uninstall()
        ZoomFilterHook.uninstall()
    }

    @AfterEach
    fun tearDown() {
        AutotagHook.uninstall()
        ZoomFilterHook.uninstall()
    }

    @Test
    fun getPreferenceSettingReturnsFreshNonNullTab() {
        val plugin = allocate(Lanelet2Plugin::class.java)
        val first = plugin.preferenceSetting
        val second = plugin.preferenceSetting
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first is Lanelet2PreferenceSetting)
        assertTrue(first is DefaultTabPreferenceSetting)
        assertNotSame(first, second)
        assertFalse(first.isExpert)
        assertEquals(Lanelet2PreferenceSetting.TITLE, (first as Lanelet2PreferenceSetting).title)
        assertEquals(Lanelet2PreferenceSetting.ICON_NAME, first.iconName)
        assertTrue(first.iconName.contains("/"))
    }

    @Test
    fun okWithoutAddGuiIsSafeNoop() {
        val setting = Lanelet2PreferenceSetting()
        assertNull(setting.form)
        assertFalse(setting.ok())
        assertEquals("road", LaneletSettings.getLaneletDefaultSubtype())
    }

    @Test
    fun addGuiThenOkDoesNotRequireRestart() {
        val setting = Lanelet2PreferenceSetting()
        setting.addGui(PreferenceTabbedPane())
        assertNotNull(setting.form)
        assertFalse(setting.ok())
    }

    @Test
    fun okRoundTripsEverySettingTheTabExposes() {
        val setting = Lanelet2PreferenceSetting()
        val form = setting.bindForTest()

        form.editingDefaults.selectSubtype("walkway")
        form.editingDefaults.selectLocation(LaneletSettings.LOC_NONURBAN)
        form.editingDefaults.selectOneWay(LaneletSettings.ONE_WAY_NO)
        form.editingOptions.chkDeleteTagged.isSelected = false
        form.editingOptions.chkProtectAnchors.isSelected = false
        form.editingOptions.chkCollectionDialog.isSelected = true
        form.mergeGrid.gridCellSpinner.value = 75
        form.routing.participantCombo.selectedItem = "bicycle"
        form.routing.debounceSpinner.value = 2
        form.routing.hookFull.isSelected = true
        form.routing.reminder.isSelected = true
        form.autotag!!.enabledBox.isSelected = true
        form.autotag.textArea.text = "file_origin=/tmp/map.osm\nnote=hi"
        form.zoomFilter!!.enabledBox.isSelected = true
        form.zoomFilter.setThreshold(19.0)
        form.zoomFilter.model.setValueAt(true, 0, 0)
        form.zoomFilter.model.setValueAt(true, 0, 1)
        form.zoomFilter.model.setValueAt(false, 0, 2)
        form.zoomFilter.model.setValueAt("highway=residential", 0, 3)

        assertFalse(setting.ok())

        assertEquals("walkway", LaneletSettings.getLaneletDefaultSubtype())
        assertEquals(LaneletSettings.LOC_NONURBAN, LaneletSettings.getLaneletDefaultLocation())
        assertEquals(LaneletSettings.ONE_WAY_NO, LaneletSettings.getLaneletDefaultOneWay())
        assertFalse(LaneletSettings.getDeleteTaggedNodes())
        assertFalse(LaneletSettings.getProtectMergeAnchors())
        assertTrue(LaneletSettings.isCollectionDialogEnabled())
        assertEquals(75.0, LaneletSettings.getMergeGridCellM(), 0.0)
        assertEquals("bicycle", LaneletSettings.getRoutingDefaultParticipant())
        assertEquals(2000, LaneletSettings.getRoutingAutoDebounceMs())
        assertTrue(LaneletSettings.getRoutingHookFullMap())
        assertTrue(LaneletSettings.getGitCommitReminder())
        assertTrue(LaneletSettings.isAutotagEnabled())
        assertEquals(
            listOf("file_origin" to "/tmp/map.osm", "note" to "hi"),
            AutotagLogic.deserializeTags(LaneletSettings.getAutotagTagsRaw()),
        )
        assertTrue(LaneletSettings.isZoomFilterEnabled())
        assertEquals(19.0, LaneletSettings.getZoomFilterThreshold(), 0.0)
        assertEquals(
            listOf(
                ZoomFilterLogic.ZoomFilterSpec(
                    enabled = true,
                    hiding = true,
                    inverted = false,
                    text = "highway=residential",
                ),
            ),
            ZoomFilterHook.getFiltersConfig(),
        )
    }

    @Test
    fun tabAndStandaloneWindowAgreeOnSharedKeysAndDefaults() {
        val standalone = SettingsForm.standalone(newBoxContent()) { null }
        val tab = SettingsForm.preferencesTab(newBoxContent()) { null }

        val shared = standalone.keys().intersect(tab.keys())
        assertEquals(standalone.keys(), shared)
        assertTrue(LaneletSettings.KEY_LANELET_DEFAULT_SUBTYPE in shared)
        assertTrue(LaneletSettings.KEY_LANELET_DEFAULT_LOCATION in shared)
        assertTrue(LaneletSettings.KEY_LANELET_DEFAULT_ONE_WAY in shared)
        assertTrue(LaneletSettings.KEY_COLLECTION_DIALOG in shared)
        assertTrue(LaneletSettings.KEY_MERGE_GRID_CELL_M in shared)
        assertTrue(LaneletSettings.KEY_ROUTING_DEFAULT_PARTICIPANT in shared)
        assertTrue(LaneletSettings.KEY_ROUTING_AUTO_DEBOUNCE_MS in shared)
        assertTrue(LaneletSettings.KEY_ROUTING_HOOK_FULL_MAP in shared)
        assertTrue(LaneletSettings.KEY_GIT_COMMIT_REMINDER in shared)
        assertTrue(AutotagSettingsPanel.KEYS.all { it in tab.keys() })
        assertTrue(ZoomFilterSettingsPanel.KEYS.all { it in tab.keys() })
        assertTrue(standalone.keys().intersect(AutotagSettingsPanel.KEYS).isEmpty())
        assertTrue(standalone.keys().intersect(ZoomFilterSettingsPanel.KEYS).isEmpty())

        assertEquals(standalone.editingDefaults.selectedSubtype(), tab.editingDefaults.selectedSubtype())
        assertEquals(standalone.editingDefaults.selectedLocation(), tab.editingDefaults.selectedLocation())
        assertEquals(standalone.editingDefaults.selectedOneWay(), tab.editingDefaults.selectedOneWay())
        assertEquals(
            standalone.editingOptions.chkDeleteTagged.isSelected,
            tab.editingOptions.chkDeleteTagged.isSelected,
        )
        assertEquals(
            standalone.editingOptions.chkProtectAnchors.isSelected,
            tab.editingOptions.chkProtectAnchors.isSelected,
        )
        assertEquals(
            standalone.editingOptions.chkCollectionDialog.isSelected,
            tab.editingOptions.chkCollectionDialog.isSelected,
        )
        assertFalse(standalone.editingOptions.chkCollectionDialog.isSelected)
        assertEquals(standalone.mergeGrid.gridCellSpinner.value, tab.mergeGrid.gridCellSpinner.value)
        assertEquals(
            standalone.routing.participantCombo.selectedItem,
            tab.routing.participantCombo.selectedItem,
        )
        assertEquals(standalone.routing.debounceSpinner.value, tab.routing.debounceSpinner.value)
        assertEquals(standalone.routing.hookFull.isSelected, tab.routing.hookFull.isSelected)
        assertEquals(standalone.routing.reminder.isSelected, tab.routing.reminder.isSelected)
        assertNull(standalone.autotag)
        assertNull(standalone.zoomFilter)
        assertNotNull(tab.autotag)
        assertNotNull(tab.zoomFilter)
        assertEquals(ZoomFilterLogic.DEFAULT_THRESHOLD, tab.zoomFilter!!.threshold(), 0.0)
        assertFalse(tab.autotag!!.enabledBox.isSelected)
        assertFalse(tab.zoomFilter.enabledBox.isSelected)
    }

    @Test
    fun eachKeyHasExactlyOneWriterAmongSharedBuilders() {
        val seen = linkedMapOf<String, String>()
        for (factory in SettingsForm.ALL_WRITERS) {
            val section = factory(newBoxContent())
            for (key in section.keys) {
                val prev = seen.put(key, section.id)
                assertNull(prev, "key $key written by both $prev and ${section.id}")
            }
        }
        val expected = setOf(
            LaneletSettings.KEY_LANELET_DEFAULT_SUBTYPE,
            LaneletSettings.KEY_LANELET_DEFAULT_LOCATION,
            LaneletSettings.KEY_LANELET_DEFAULT_ONE_WAY,
            LaneletSettings.KEY_DELETE_TAGGED_NODES,
            LaneletSettings.KEY_PROTECT_MERGE_ANCHORS,
            LaneletSettings.KEY_COLLECTION_DIALOG,
            LaneletSettings.KEY_MERGE_GRID_CELL_M,
            LaneletSettings.KEY_ROUTING_DEFAULT_PARTICIPANT,
            LaneletSettings.KEY_ROUTING_AUTO_DEBOUNCE_MS,
            LaneletSettings.KEY_ROUTING_HOOK_FULL_MAP,
            LaneletSettings.KEY_GIT_COMMIT_REMINDER,
            LaneletSettings.KEY_AUTOTAG_ENABLED,
            LaneletSettings.KEY_AUTOTAG_TAGS,
            LaneletSettings.KEY_ZOOMFILTER_ENABLED,
            LaneletSettings.KEY_ZOOMFILTER_THRESHOLD,
            LaneletSettings.KEY_ZOOMFILTER_FILTERS,
        )
        assertEquals(expected, seen.keys)
        assertEquals(EditingOptionsControls.ID, seen[LaneletSettings.KEY_COLLECTION_DIALOG])
        assertEquals(AutotagSettingsPanel.ID, seen[LaneletSettings.KEY_AUTOTAG_ENABLED])
        assertEquals(ZoomFilterSettingsPanel.ID, seen[LaneletSettings.KEY_ZOOMFILTER_ENABLED])
        assertEquals(RoutingControls.ID, seen[LaneletSettings.KEY_GIT_COMMIT_REMINDER])
    }

    @Test
    fun togglingAutotagOnTheTabInstallsAndUninstallsTheHook() {
        val on = Lanelet2PreferenceSetting()
        val formOn = on.bindForTest()
        formOn.autotag!!.enabledBox.isSelected = true
        formOn.autotag.textArea.text = "file_origin=/tmp/a.osm"
        assertFalse(on.ok())
        assertTrue(AutotagHook.isEnabled())
        assertTrue(AutotagHook.activeListenerInstalled())

        Config.setPreferencesInstance(MemoryPreferences())
        LaneletSettings.setAutotagEnabled(true)
        AutotagHook.installIfEnabled()
        assertTrue(AutotagHook.activeListenerInstalled())

        val off = Lanelet2PreferenceSetting()
        val formOff = off.bindForTest()
        assertTrue(formOff.autotag!!.enabledBox.isSelected)
        formOff.autotag.enabledBox.isSelected = false
        assertFalse(off.ok())
        assertFalse(AutotagHook.isEnabled())
        assertFalse(AutotagHook.activeListenerInstalled())
    }

    @Test
    fun togglingZoomFilterOnTheTabReinstallsFromSettings() {
        val on = Lanelet2PreferenceSetting()
        val formOn = on.bindForTest()
        formOn.zoomFilter!!.enabledBox.isSelected = true
        assertFalse(on.ok())
        assertTrue(ZoomFilterHook.isEnabled())
        assertTrue(ZoomFilterHook.listenerInstalled())

        val off = Lanelet2PreferenceSetting()
        val formOff = off.bindForTest()
        formOff.zoomFilter!!.enabledBox.isSelected = false
        assertFalse(off.ok())
        assertFalse(ZoomFilterHook.isEnabled())
        assertFalse(ZoomFilterHook.listenerInstalled())
    }

    @Test
    fun dedicatedHookWindowsUseTheSamePanelWriters() {
        val fromWindow = AutotagSettingsPanel.create { null }
        fromWindow.enabledBox.isSelected = true
        fromWindow.textArea.text = "k=v"
        fromWindow.save()
        assertEquals("k=v", LaneletSettings.getAutotagTagsRaw())
        assertTrue(LaneletSettings.isAutotagEnabled())

        val fromZoom = ZoomFilterSettingsPanel.create()
        fromZoom.enabledBox.isSelected = true
        fromZoom.setThreshold(12.0)
        fromZoom.save()
        assertEquals(12.0, LaneletSettings.getZoomFilterThreshold(), 0.0)
        assertTrue(LaneletSettings.isZoomFilterEnabled())

        val tab = SettingsForm.preferencesTab(newBoxContent()) { null }
        assertEquals(AutotagSettingsPanel.KEYS, tab.section(AutotagSettingsPanel.ID).keys)
        assertEquals(ZoomFilterSettingsPanel.KEYS, tab.section(ZoomFilterSettingsPanel.ID).keys)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, type) as T
    }
}
