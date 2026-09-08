package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

internal object OsmFixtures {
    fun ensurePrefs() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(MemoryPreferences())
        }
    }

    /** [lon], [lat] matching the Jython (lon, lat) geometry convention. */
    fun node(lon: Double, lat: Double): Node = Node(LatLon(lat, lon))

    fun way(vararg lonLat: Pair<Double, Double>): Way {
        ensurePrefs()
        val w = Way()
        w.setNodes(lonLat.map { node(it.first, it.second) })
        return w
    }

    fun laneletRelation(left: Way, right: Way, subtype: String = "road"): Relation {
        ensurePrefs()
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.put("subtype", subtype)
        rel.addMember(RelationMember("left", left))
        rel.addMember(RelationMember("right", right))
        return rel
    }

    fun relation(
        type: String,
        subtype: String? = null,
        vararg members: Pair<String, OsmPrimitive>,
    ): Relation {
        ensurePrefs()
        val rel = Relation()
        rel.put("type", type)
        if (subtype != null) rel.put("subtype", subtype)
        for ((role, mem) in members) {
            rel.addMember(RelationMember(role, mem))
        }
        return rel
    }

    fun dataSet(vararg primitives: OsmPrimitive): DataSet {
        ensurePrefs()
        val ds = DataSet()
        for (p in primitives) {
            if (p.dataSet !== ds) {
                ds.addPrimitiveRecursive(p)
            }
        }
        return ds
    }

    fun withUndo(block: (UndoRedoHandler) -> Unit) {
        ensurePrefs()
        val undo = UndoRedoHandler.getInstance()
        undo.clean()
        try {
            block(undo)
        } finally {
            undo.clean()
        }
    }
}
