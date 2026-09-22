package org.openstreetmap.josm.plugins.lanelet2.tools

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.BBox
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.tools.OpenBrowser
import java.awt.Component
import java.awt.event.ActionEvent
import java.util.Locale
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Open the current spot in street-level imagery (Mapillary, Google Street
 * View, Apple Maps). New feature, not a Jython port. The 3D viewer has the
 * same button (static/js/imagery.js) and builds the same URLs.
 *
 * The spot is the one selected node, else the centre of the selection, else
 * the centre of the map view.
 */
object StreetImagery {
    const val SLOT_ID = "ll2_street_imagery"
    const val DISPLAY_NAME = "Street-level imagery here (Mapillary / Google / Apple)"
    const val TOOLBAR_LABEL = "SV"
    const val ICON_PATH = "street_imagery.svg"

    enum class Provider(val label: String) {
        MAPILLARY("Mapillary"),
        GOOGLE("Google Street View"),
        APPLE("Apple Maps"),
    }

    /** [headingDeg]: compass heading to look toward (Google only), null for its default. */
    fun url(provider: Provider, lat: Double, lon: Double, headingDeg: Double? = null): String = when (provider) {
        Provider.MAPILLARY ->
            String.format(Locale.US, "https://www.mapillary.com/app/?lat=%.7f&lng=%.7f&z=17&menu=false", lat, lon)
        // Google Maps URLs API: Street View at the panorama nearest the viewpoint.
        Provider.GOOGLE -> buildString {
            append(String.format(Locale.US, "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=%.7f,%.7f", lat, lon))
            headingDeg?.let { append(String.format(Locale.US, "&heading=%.0f", ((it % 360) + 360) % 360)) }
        }
        Provider.APPLE ->
            String.format(Locale.US, "https://maps.apple.com/frame?center=%.7f%%2C%.7f&span=0.000545%%2C0.000709", lat, lon)
    }

    /** One selected node, else the centre of the selection's bounds, else [viewCenter]. */
    fun position(selected: Collection<OsmPrimitive>, viewCenter: LatLon?): LatLon? {
        val usable = selected.filter { !it.isDeleted }
        val single = usable.singleOrNull()
        if (single is Node) return single.coor
        if (usable.isNotEmpty()) {
            val box = BBox()
            usable.forEach { box.addPrimitive(it, 0.0) }
            if (box.isValid) return box.center
        }
        return viewCenter
    }

    fun currentPosition(): LatLon? {
        val selected = LaneletUtils.getEditLayer()?.data?.selected.orEmpty()
        val viewCenter = try {
            MainApplication.getMap()?.mapView?.realBounds?.center
        } catch (_: Exception) {
            null
        }
        return position(selected, viewCenter)
    }

    fun open(provider: Provider) {
        val at = currentPosition() ?: run {
            Dialogs.error("Nothing selected and no map view to take a position from.", DISPLAY_NAME)
            return
        }
        val error = OpenBrowser.displayUrl(url(provider, at.lat(), at.lon()))
        if (error != null) Dialogs.error("Could not open a browser:\n$error", DISPLAY_NAME)
    }

    /** The provider choice, shown under the button that was clicked (or over the map). */
    fun showMenu(invoker: Component?) {
        val menu = JPopupMenu()
        for (p in Provider.entries) {
            menu.add(JMenuItem(p.label).apply { addActionListener { open(p) } })
        }
        val anchor = invoker?.takeIf { it.isShowing } ?: MainApplication.getMap()?.mapView
        if (anchor == null || !anchor.isShowing) return
        if (anchor === invoker) menu.show(anchor, 0, anchor.height) else menu.show(anchor, anchor.width / 2, anchor.height / 2)
    }

    fun slot(): ActionSlot {
        val action = object : LaneletAction(DISPLAY_NAME, ICON_PATH, DISPLAY_NAME, null) {
            override fun actionPerformed(e: ActionEvent) = showMenu(e.source as? Component)
        }
        return ActionSlot(
            id = SLOT_ID,
            action = action,
            toolbarLabel = TOOLBAR_LABEL,
            iconName = ICON_PATH,
            menu = MenuId.UTILS,
        )
    }

    /** After the 3D viewer's entry in the utilities menu and toolbar. */
    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        registry.register(slot())
        registry.insertAfter("ll2_viewer3d_window", listOf(SLOT_ID))
    }
}
