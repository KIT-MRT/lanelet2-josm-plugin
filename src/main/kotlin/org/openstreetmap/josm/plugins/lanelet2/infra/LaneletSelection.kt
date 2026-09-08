package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way

/**
 * Selection extractors shared by lanelet edit actions.
 *
 * Port of the non-GUI helpers in `lanelet2_collection_dialog.py`. The dialog
 * itself is [CollectionDialog]; these extractors are shared by both that UI
 * and the current-selection shortcut.
 */
object LaneletSelection {
    fun extractLanelets(selection: Iterable<OsmPrimitive?>): MutableList<Relation> {
        val out = ArrayList<Relation>()
        for (prim in selection) {
            if (prim != null && prim is Relation) {
                val t = prim.get("type")
                if (t != null && t.lowercase() == "lanelet") {
                    out.add(prim)
                }
            }
        }
        return out
    }

    fun findLaneletsContainingLinestrings(data: DataSet, ways: Iterable<Way?>): List<Relation> {
        val wayIds = uniqueIds(ways)
        if (wayIds.isEmpty()) return emptyList()
        val lanelets = ArrayList<Relation>()
        for (prim in data.relations) {
            if (prim == null || prim.get("type") != "lanelet") continue
            for (m in prim.members) {
                if (m.role !in LEFT_RIGHT) continue
                val mem = m.member ?: continue
                if (mem.uniqueId in wayIds) {
                    lanelets.add(prim)
                    break
                }
            }
        }
        return lanelets
    }

    /**
     * Mode B: lanelets from [selection] (direct) plus those inferred from selected
     * linestrings. Direct hits are not deduped (matches the Jython); inferred
     * hits skip objects already present by identity.
     */
    fun extractLaneletsOrFromLinestrings(
        data: DataSet,
        selection: Iterable<OsmPrimitive?>,
    ): MutableList<Relation> {
        val lanelets = extractLanelets(selection)
        val seen = HashSet<Int>()
        for (ll in lanelets) seen.add(System.identityHashCode(ll))
        val ways = ArrayList<Way>()
        for (p in selection) {
            if (p != null && p is Way) ways.add(p)
        }
        if (ways.isNotEmpty()) {
            for (ll in findLaneletsContainingLinestrings(data, ways)) {
                val id = System.identityHashCode(ll)
                if (id !in seen) {
                    lanelets.add(ll)
                    seen.add(id)
                }
            }
        }
        return lanelets
    }

    fun extractRelations(
        selection: Iterable<OsmPrimitive?>,
        relType: String,
        relSubtype: String? = null,
    ): MutableList<Relation> {
        val out = ArrayList<Relation>()
        for (prim in selection) {
            if (prim == null || prim !is Relation) continue
            val t = prim.get("type") ?: continue
            if (t.lowercase() != relType.lowercase()) continue
            if (relSubtype.isNullOrEmpty()) {
                out.add(prim)
            } else {
                val st = prim.get("subtype")
                if (st != null && st.lowercase() == relSubtype.lowercase()) {
                    out.add(prim)
                }
            }
        }
        return out
    }

    fun findRelationsContainingWays(
        data: DataSet,
        ways: Iterable<Way?>,
        relType: String,
        relSubtype: String? = null,
    ): List<Relation> {
        val wayIds = uniqueIds(ways)
        if (wayIds.isEmpty()) return emptyList()
        val result = ArrayList<Relation>()
        for (prim in data.relations) {
            if (prim == null) continue
            val t = prim.get("type") ?: continue
            if (t.lowercase() != relType.lowercase()) continue
            if (!relSubtype.isNullOrEmpty()) {
                val st = prim.get("subtype")
                if (st == null || st.lowercase() != relSubtype.lowercase()) continue
            }
            for (m in prim.members) {
                val mem = m.member ?: continue
                if (mem.uniqueId in wayIds) {
                    result.add(prim)
                    break
                }
            }
        }
        return result
    }

    fun extractRelationsOrFromWays(
        data: DataSet,
        selection: Iterable<OsmPrimitive?>,
        relType: String,
        relSubtype: String? = null,
    ): MutableList<Relation> {
        val rels = extractRelations(selection, relType, relSubtype)
        val seen = HashSet<Int>()
        for (r in rels) seen.add(System.identityHashCode(r))
        val ways = ArrayList<Way>()
        for (p in selection) {
            if (p != null && p is Way) ways.add(p)
        }
        if (ways.isNotEmpty()) {
            for (r in findRelationsContainingWays(data, ways, relType, relSubtype)) {
                val id = System.identityHashCode(r)
                if (id !in seen) {
                    rels.add(r)
                    seen.add(id)
                }
            }
        }
        return rels
    }

    private val LEFT_RIGHT = setOf("left", "right")

    private fun uniqueIds(ways: Iterable<OsmPrimitive?>): Set<Long> {
        val ids = HashSet<Long>()
        for (w in ways) {
            if (w == null) continue
            try {
                ids.add(w.uniqueId)
            } catch (_: Exception) {
            }
        }
        return ids
    }
}
