package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import java.util.ArrayList

internal fun requireVisibleEditLayer(): OsmDataLayer? {
    val layer = LaneletUtils.getEditLayer()
    if (layer == null || !layer.isVisible) return null
    return layer
}

internal fun applySequence(
    title: String,
    commands: Collection<Command>,
    layer: OsmDataLayer? = null,
    undo: UndoRedoHandler = LaneletUtils.getUndo(),
) {
    if (commands.isEmpty()) return
    undo.add(SequenceCommand(title, ArrayList(commands)))
    try {
        layer?.invalidate()
    } catch (_: Exception) {
    }
    try {
        MainApplication.getMap()?.mapView?.repaint()
    } catch (_: Exception) {
    }
}

/** Jython `routing_refresh_hook.request_update()` — hook is not ported yet. */
internal fun requestRoutingRefresh() {
    // Original already swallowed ImportError / any Exception.
}

/** Pan to [prims] without changing zoom. Headless / no map: no-op. */
internal fun zoomToPrimitives(prims: Collection<OsmPrimitive>) {
    if (prims.isEmpty()) return
    try {
        val mv = MainApplication.getMap() ?: return
        if (mv.mapView == null) return
        val visitor = BoundingXYVisitor()
        visitor.computeBoundingBox(ArrayList(prims))
        val bounds = visitor.bounds ?: return
        visitor.enlargeBoundingBox()
        mv.mapView.zoomTo(bounds.center)
        mv.mapView.repaint()
    } catch (_: Exception) {
    }
}

internal fun invalidateAndRepaint(layer: OsmDataLayer?) {
    try {
        layer?.invalidate()
    } catch (_: Exception) {
    }
    try {
        MainApplication.getMap()?.mapView?.repaint()
    } catch (_: Exception) {
    }
}
