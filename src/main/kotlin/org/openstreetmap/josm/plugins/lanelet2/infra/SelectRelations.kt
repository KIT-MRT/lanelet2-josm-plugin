package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.tools.Logging
import java.io.File

/**
 * Shared load/save/find logic for "select lanelets / relations from linestrings".
 *
 * Port of `select_relations_core.py` minus the collection-dialog shell
 * ([CollectionDialog] + [org.openstreetmap.josm.plugins.lanelet2.selection.SelectFromLinestrings]).
 *
 * File I/O failures are logged instead of shown in a `JOptionPane`.
 */
object SelectRelations {
    fun tmpFilePrefix(): File = File(System.getProperty("user.home"), ".lanelet2_selected")

    fun laneletTmpFile(): File = File(System.getProperty("user.home"), ".lanelet2_selected_lanelets.txt")

    /**
     * Tmp file path for the given type/subtype.
     * For lanelet with no subtype: `~/.lanelet2_selected_lanelets.txt` (backward compat).
     * Else: `~/.lanelet2_selected_relations_{type}_{subtype}.txt`
     */
    fun getTmpFilePath(relType: String, relSubtype: String?): File {
        if (relType == "lanelet" && relSubtype.isNullOrEmpty()) {
            return laneletTmpFile()
        }
        val st = relSubtype ?: ""
        val suffix = if (st.isNotEmpty()) "${relType}_$st" else relType
        val safe = suffix.replace("/", "_").replace(" ", "_")
        return File("${tmpFilePrefix().path}_relations_$safe.txt")
    }

    /** Load relation IDs from file. Returns list of numeric IDs. Missing/unreadable file -> empty. */
    fun loadIdsFromFile(path: File): List<Long> {
        if (!path.exists()) return emptyList()
        return try {
            val ids = ArrayList<Long>()
            path.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val id = line.toLongOrNull()
                if (id != null) ids.add(id)
            }
            ids
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Save relation unique IDs to [path], one per line. Returns false on I/O error. */
    fun saveRelationsToFile(relations: Iterable<OsmPrimitive>, path: File): Boolean {
        return try {
            path.printWriter().use { writer ->
                for (r in relations) {
                    try {
                        writer.println(r.uniqueId.toString())
                    } catch (_: Exception) {
                    }
                }
            }
            true
        } catch (e: Exception) {
            Logging.warn("lanelet2: could not save to {0}: {1}", path, e.message)
            false
        }
    }

    /** Find relations in [data] with the given unique IDs, matching type and subtype. */
    fun findRelationsByIds(
        data: DataSet,
        ids: Collection<Long>,
        relType: String,
        relSubtype: String? = null,
    ): List<Relation> {
        val idSet = ids.toSet()
        val found = ArrayList<Relation>()
        for (prim in data.relations) {
            val t = prim.get("type") ?: continue
            if (t.lowercase() != relType.lowercase()) continue
            if (!relSubtype.isNullOrEmpty()) {
                val st = prim.get("subtype")
                if (st == null || st.lowercase() != relSubtype.lowercase()) continue
            }
            try {
                if (prim.uniqueId in idSet) found.add(prim)
            } catch (_: Exception) {
            }
        }
        return found
    }
}
