package org.openstreetmap.josm.plugins.lanelet2.api

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletSelection
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils

/**
 * Lanelet model over JOSM primitives, plus the selection extractors used by
 * the edit actions.
 */
class LaneletAccess internal constructor() {

    /** Wrap an existing `type=lanelet` relation. */
    fun wrap(relation: Relation): Lanelet = Lanelet(relation)

    /**
     * Lanelets in [data]'s current selection: directly selected relations
     * plus those inferred from selected linestrings.
     */
    fun fromSelection(data: DataSet): List<Lanelet> =
        fromSelection(data, data.allSelected)

    /**
     * Same as [fromSelection] but using an explicit [selection] instead of
     * the dataset's live selection.
     */
    fun fromSelection(data: DataSet, selection: Collection<OsmPrimitive?>): List<Lanelet> =
        LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)
            .map { Lanelet(it) }

    /**
     * [fromSelection] on the active edit layer. Empty when no edit layer is
     * open.
     */
    fun fromCurrentSelection(): List<Lanelet> {
        val layer = LaneletUtils.getEditLayer() ?: return emptyList()
        return fromSelection(layer.data)
    }
}
