package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way

/**
 * Regulatory-element constants and member extractors.
 *
 * Port of `regulatory_element_utils.py` minus `show_subtype_dialog` (GUI is a
 * separate task).
 */
object RegulatoryElements {
    val SUBTYPES: List<String> = listOf(
        "(any)",
        "traffic_light",
        "traffic_sign",
        "speed_limit",
        "right_of_way",
        "all_way_stop",
        "Custom",
    )

    val DEFAULT_MEMBER_ROLES: List<String> = listOf(
        "ref_line",
        "refers",
        "cancel_line",
        "cancels",
        "yield",
        "right_of_way",
    )

    /**
     * Return role -> list of primitives.
     * [roles] null means the default regulatory-element roles.
     */
    fun extractMembersByRole(
        regElem: Relation,
        roles: Collection<String>? = null,
    ): Map<String, MutableList<OsmPrimitive>> {
        val byRole = LinkedHashMap<String, MutableList<OsmPrimitive>>()
        val roleSet = if (roles != null) roles.toSet() else DEFAULT_MEMBER_ROLES.toSet()
        for (m in regElem.members) {
            val role = m.role
            if (role !in roleSet) continue
            val mem = m.member ?: continue
            byRole.getOrPut(role) { ArrayList() }.add(mem)
        }
        return byRole
    }

    /**
     * Extract a single stop_line way from [selection].
     *
     * [optional] true: 0 or 1 way allowed. false: exactly 1 required.
     * Returns (way, errorMsg). way is null if none selected (and optional) or on error.
     */
    fun extractRefLine(
        selection: Iterable<OsmPrimitive?>,
        optional: Boolean = true,
    ): Pair<Way?, String?> {
        val ways = ArrayList<Way>()
        for (prim in selection) {
            if (prim != null && prim is Way) {
                val t = prim.get("type")
                if (t != null && t.lowercase() == "stop_line") {
                    ways.add(prim)
                }
            }
        }
        if (ways.isEmpty()) {
            return if (optional) Pair(null, null)
            else Pair(null, "Select exactly 1 stop line (type=stop_line).")
        }
        if (ways.size > 1) {
            return Pair(null, "Select at most 1 stop line (type=stop_line). Found ${ways.size}.")
        }
        return Pair(ways[0], null)
    }

    /** Alias for [extractRefLine] with optional=false. Requires exactly 1 stop line. */
    fun extractStopLine(selection: Iterable<OsmPrimitive?>): Pair<Way?, String?> =
        extractRefLine(selection, optional = false)
}
