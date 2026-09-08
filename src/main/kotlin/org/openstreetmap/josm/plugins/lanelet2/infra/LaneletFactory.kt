package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.command.AddCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer

/**
 * Creates a lanelet relation and returns a [Lanelet].
 * Selection order of the original script: first = left, second = right.
 *
 * Port of `LaneletFactory` in `lanelet_representation.py`. Does not register
 * menus or actions. The Jython `routing_refresh_hook.prepare_for_edit_layer_paint()`
 * call is omitted until that hook is ported (the original already swallowed
 * ImportError).
 */
object LaneletFactory {
    /**
     * Build the relation object only (no undo, no layer invalidate). Useful
     * for headless tests and for callers that want to compose their own command.
     */
    fun createRelation(
        leftWay: Way,
        rightWay: Way,
        subtype: String = "road",
        location: String? = null,
        oneWay: String? = null,
    ): Relation {
        val newRelation = Relation()
        newRelation.put("type", "lanelet")
        newRelation.put("subtype", subtype)
        if (location != null) newRelation.put("location", location)
        if (oneWay != null) newRelation.put("one_way", oneWay)
        newRelation.addMember(0, RelationMember("left", leftWay))
        newRelation.addMember(1, RelationMember("right", rightWay))
        return newRelation
    }

    /**
     * Create Relation `type=lanelet` with the given subtype; set location only
     * if [location] is not null. Set `one_way` only if [oneWay] is not null
     * (typically `"no"` for bidirectional; omit the tag for one-way default).
     *
     * [layer] may be null (skips invalidate); the Jython always passed a layer.
     */
    fun create(
        data: DataSet,
        layer: OsmDataLayer?,
        leftWay: Way,
        rightWay: Way,
        undo: UndoRedoHandler,
        subtype: String = "road",
        location: String? = null,
        oneWay: String? = null,
    ): Lanelet {
        val newRelation = createRelation(leftWay, rightWay, subtype, location, oneWay)
        undo.add(AddCommand(data, newRelation))
        layer?.invalidate()
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
        return Lanelet(newRelation)
    }
}
