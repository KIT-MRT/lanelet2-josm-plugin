package org.openstreetmap.josm.plugins.lanelet2.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class StreetImageryTest {
    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun providerUrls() {
        assertEquals(
            "https://www.mapillary.com/app/?lat=49.0109600&lng=8.4084800&z=17&menu=false",
            StreetImagery.url(StreetImagery.Provider.MAPILLARY, 49.01096, 8.40848),
        )
        assertEquals(
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=49.0109162,8.4179388&heading=108",
            StreetImagery.url(StreetImagery.Provider.GOOGLE, 49.0109162, 8.4179388, 107.6),
        )
        assertEquals(
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=49.0000000,8.4000000&heading=350",
            StreetImagery.url(StreetImagery.Provider.GOOGLE, 49.0, 8.4, -10.0),
        )
        assertEquals(
            "https://maps.apple.com/frame?center=49.0093440%2C8.4102510&span=0.000545%2C0.000709",
            StreetImagery.url(StreetImagery.Provider.APPLE, 49.009344, 8.410251),
        )
    }

    @Test
    fun positionIsTheNodeOrTheSelectionCentreOrTheView() {
        val ds = DataSet()
        val a = Node(LatLon(49.0, 8.4)).also { ds.addPrimitive(it) }
        val b = Node(LatLon(49.002, 8.404)).also { ds.addPrimitive(it) }
        val w = Way().also {
            it.setNodes(listOf(a, b))
            ds.addPrimitive(it)
        }
        val view = LatLon(48.0, 9.0)
        assertEquals(a.coor, StreetImagery.position(listOf(a), view))
        val centre = StreetImagery.position(listOf(w), view)!!
        assertEquals(49.001, centre.lat(), 1e-9)
        assertEquals(8.402, centre.lon(), 1e-9)
        assertEquals(view, StreetImagery.position(emptyList(), view))
        b.setDeleted(true)
        assertEquals(view, StreetImagery.position(listOf(b), view))
        assertNull(StreetImagery.position(emptyList(), null))
    }

    @Test
    fun sitsAfterThe3dViewerInTheUtilitiesMenu() {
        val registry = ActionRegistry(utilsOrder = listOf("ll2_viewer3d_window", "other"))
        registry.register(org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dActions.slot())
        StreetImagery.registerAll(registry)
        val ids = registry.build(MenuId.UTILS).mapNotNull { it?.id }
        assertEquals(listOf("ll2_viewer3d_window", StreetImagery.SLOT_ID), ids)
    }
}
