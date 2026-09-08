package org.openstreetmap.josm.plugins.lanelet2.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.edit.TypeSubtype
import org.openstreetmap.josm.plugins.lanelet2.infra.CollectionLogic
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.infra.SelectRelations
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.nio.file.Path

class SelectFromLinestringsTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun selectLaneletsFromBorderWayAndSave(@TempDir dir: Path) {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        ds.setSelected(left)
        val extracted = org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
            .extractLaneletsOrFromLinestrings(ds, ds.selected)
        val path = dir.resolve("lanelets.txt").toFile()
        val n = SelectLaneletsFromLinestrings.apply(ds, extracted, path, Dialogs)
        assertEquals(1, n)
        assertEquals(setOf(ll), ds.selected.toSet())
        assertEquals(listOf(ll.uniqueId), SelectRelations.loadIdsFromFile(path))
    }

    @Test
    fun emptySelectionRestoresFromFile(@TempDir dir: Path) {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        val path = dir.resolve("lanelets.txt").toFile()
        path.writeText("${ll.uniqueId}\n")
        ds.clearSelection()
        val n = SelectLaneletsFromLinestrings.apply(ds, emptyList(), path, Dialogs)
        assertEquals(1, n)
        assertEquals(setOf(ll), ds.selected.toSet())
    }

    @Test
    fun selectRelationsFiltersTypeAndSubtype(@TempDir dir: Path) {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        w.put("type", "traffic_light")
        val keep = OsmFixtures.relation("regulatory_element", "traffic_light", "refers" to w)
        val other = OsmFixtures.relation("regulatory_element", "traffic_sign", "refers" to w)
        val ds = OsmFixtures.dataSet(keep, other)
        ds.setSelected(w)
        val extracted = org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
            .extractRelationsOrFromWays(ds, ds.selected, "regulatory_element", "traffic_light")
        val path = dir.resolve("rels.txt").toFile()
        val n = SelectRelationsFromLinestrings.apply(
            ds, extracted, "regulatory_element", "traffic_light", path, Dialogs,
        )
        assertEquals(1, n)
        assertEquals(setOf(keep), ds.selected.toSet())
        assertEquals(listOf(keep.uniqueId), SelectRelations.loadIdsFromFile(path))
    }

    @Test
    fun initialFromFileSelectsLoadedRelations(@TempDir dir: Path) {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        val path = dir.resolve("lanelets.txt").toFile()
        path.writeText("${ll.uniqueId}\n")
        ds.clearSelection()
        val loaded = SelectFromLinestrings.initialFromFile(ds, "lanelet", null, path)
        assertEquals(listOf(ll), loaded)
        assertEquals(setOf(ll), ds.selected.toSet())
    }

    @Test
    fun collectionDialogSettingDoesNotChangeApplyShortcut() {
        LaneletSettings.setCollectionDialogEnabled(true)
        assertTrue(LaneletSettings.isCollectionDialogEnabled())
        assertEquals(Dialogs.isHeadless(), !CollectionLogic.shouldOpenCollectionDialog())
    }

    @Test
    fun relationTypeListMatchesJython() {
        assertEquals(
            listOf("lanelet", "regulatory_element", "multipolygon", "area", "Custom"),
            SelectRelationsFromLinestrings.RELATION_TYPES,
        )
        assertEquals(TypeSubtype.LANELET_SUBTYPES, TypeSubtype.subtypesFor("lanelet"))
        assertTrue("traffic_light" in TypeSubtype.subtypesFor("regulatory_element"))
    }
}
