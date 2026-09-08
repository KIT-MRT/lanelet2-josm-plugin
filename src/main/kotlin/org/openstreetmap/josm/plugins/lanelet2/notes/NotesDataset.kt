package org.openstreetmap.josm.plugins.lanelet2.notes

import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.OsmPrimitiveType
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way

/**
 * Notes storage over a JOSM [DataSet]. Uses `data.osm` only — no MapFrame,
 * layers, or Swing — so it can run in headless tests.
 *
 * Mutations match `notes_core.NotesManager`: they write the notes dataset
 * directly and keep undo on [NotesUndoStack]. They do **not** go through
 * JOSM Commands (the Jython never touched the global undo stack; a Command
 * would attach to whichever map layer is active).
 */
class NotesDataset(
    var dataset: DataSet,
    val idMap: MutableMap<Long, Long> = LinkedHashMap(),
    val undoStack: NotesUndoStack = NotesUndoStack(),
    var onRefresh: () -> Unit = {},
) {
    fun seedIdMap() {
        try {
            for (p in dataset.nodes + dataset.ways) {
                val uid = p.uniqueId
                if (uid > 0) idMap[uid] = uid
            }
        } catch (_: Exception) {
        }
    }

    fun serialize(): String = serializeNotes(nodeRecs(), wayRecs(), idMap)

    fun nodeRecs(): List<NoteNodeRec> = dataset.nodes.map { it.toNodeRec() }

    fun wayRecs(): List<NoteWayRec> = dataset.ways.map { it.toWayRec() }

    fun anchors(): List<Node> {
        val out = ArrayList<Node>()
        try {
            for (p in dataset.nodes) {
                if (!p.isDeleted && primTag(p, NotesTags.TAG_ANCHOR) == "yes") {
                    out.add(p)
                }
            }
        } catch (_: Exception) {
        }
        out.sortWith(compareBy({ primTag(it, NotesTags.TAG_CREATED) }, { NotesDataset.noteId(it) }))
        return out
    }

    fun noteGroup(nid: String): List<OsmPrimitive> {
        val out = ArrayList<OsmPrimitive>()
        if (nid.isEmpty()) return out
        try {
            for (p in dataset.nodes + dataset.ways) {
                if (p.isDeleted) continue
                if (primTag(p, NotesTags.TAG_ID) != nid) continue
                if (primTag(p, NotesTags.TAG_ANCHOR) == "yes" ||
                    primTag(p, NotesTags.TAG_MARKER) == "yes" ||
                    primTag(p, NotesTags.TAG_MEMBER) == "yes"
                ) {
                    out.add(p)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun groupFor(marker: OsmPrimitive): List<OsmPrimitive> {
        val grp = noteGroup(NotesDataset.noteId(marker)).toMutableList()
        if (marker !in grp) grp.add(marker)
        return grp
    }

    fun setNoteField(marker: OsmPrimitive, key: String, value: String?, propagate: Boolean = false) {
        val targets = if (propagate) groupFor(marker) else listOf(marker)
        for (p in targets) {
            try {
                if (value.isNullOrEmpty()) {
                    p.remove(key)
                } else {
                    p.put(key, value)
                }
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    fun addPointNote(lat: Double, lon: Double, tags: Map<String, String>): Node {
        val node = Node(LatLon(lat, lon))
        dataset.addPrimitive(node)
        putAll(node, tags)
        node.put(NotesTags.TAG_ANCHOR, "yes")
        refresh()
        return node
    }

    fun addNoteFromSelection(selection: Collection<OsmPrimitive>, tags: Map<String, String>): Node? {
        val ways = selection.filterIsInstance<Way>().filter { !it.isIncomplete }
        val nodes = selection.filterIsInstance<Node>().filter { !it.isIncomplete }

        val wayNodeIds = HashSet<Long>()
        for (w in ways) {
            for (nd in w.nodes) wayNodeIds.add(nd.uniqueId)
        }
        val standalone = nodes.filter { it.uniqueId !in wayNodeIds }

        val memberWay = memberWayTags(tags)
        val memberNode = memberNodeTags(tags)
        val members = ArrayList<OsmPrimitive>()
        val coords = ArrayList<Pair<Double, Double>>()

        for (w in ways) {
            val nw = copyWay(w)
            putAll(nw, memberWay)
            members.add(nw)
            for (nd in nw.nodes) {
                val c = nd.coor ?: continue
                coords.add(c.lat() to c.lon())
            }
        }
        for (n in standalone) {
            val c = n.coor ?: continue
            val nn = Node(LatLon(c.lat(), c.lon()))
            dataset.addPrimitive(nn)
            putAll(nn, memberNode)
            members.add(nn)
            coords.add(c.lat() to c.lon())
        }
        if (members.isEmpty()) return null

        // Single copied node: promote it to the marker (no separate pin).
        // Jython leaves the earlier note_member tags in place.
        val only = members.singleOrNull()
        if (only is Node) {
            putAll(only, tags)
            only.put(NotesTags.TAG_ANCHOR, "yes")
            refresh()
            return only
        }

        val center = centroid(coords) ?: return null
        val marker = Node(LatLon(center.first, center.second))
        dataset.addPrimitive(marker)
        putAll(marker, tags)
        marker.put(NotesTags.TAG_ANCHOR, "yes")
        refresh()
        return marker
    }

    fun copyWay(orig: Way): Way {
        val nodeMap = HashMap<Long, Node>()
        val seq = ArrayList<Node>()
        for (nd in orig.nodes) {
            val uid = nd.uniqueId
            val nn = nodeMap.getOrPut(uid) {
                val c = nd.coor
                val created = Node(LatLon(c.lat(), c.lon()))
                dataset.addPrimitive(created)
                created
            }
            seq.add(nn)
        }
        val w = Way()
        for (nn in seq) w.addNode(nn)
        dataset.addPrimitive(w)
        return w
    }

    fun snapshotPrim(prim: OsmPrimitive): NoteSnapshot {
        val tags = LinkedHashMap<String, String>()
        try {
            for ((k, v) in prim.keys) {
                tags[k.toString()] = v.toString()
            }
        } catch (_: Exception) {
        }
        val coords = ArrayList<Pair<Double, Double>>()
        return if (prim is Way) {
            for (nd in prim.nodes) {
                val c = nd.coor
                coords.add(c.lat() to c.lon())
            }
            NoteSnapshot("way", tags, coords)
        } else {
            val c = (prim as Node).coor
            coords.add(c.lat() to c.lon())
            NoteSnapshot("node", tags, coords)
        }
    }

    fun snapshotGroup(marker: OsmPrimitive): List<NoteSnapshot> =
        groupFor(marker).map { snapshotPrim(it) }

    fun deletePrimitives(prims: Collection<OsmPrimitive>) {
        val ways = prims.filterIsInstance<Way>()
        val nodes = prims.filterIsInstance<Node>()
        val extra = ArrayList<Node>()
        for (w in ways) {
            try {
                extra.addAll(w.nodes)
                dataset.removePrimitive(w)
            } catch (_: Exception) {
            }
        }
        val seen = HashSet<Long>()
        for (nd in nodes + extra) {
            try {
                val uid = nd.uniqueId
                if (uid in seen) continue
                seen.add(uid)
                if (!nd.isDeleted && nd.referrers.isEmpty()) {
                    dataset.removePrimitive(nd)
                }
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    fun restorePrims(snaps: List<NoteSnapshot>) {
        for (snap in snaps) {
            val coords = snap.coords
            val tags = snap.tags
            val prim: OsmPrimitive? = if (snap.kind == "way" && coords.isNotEmpty()) {
                val seq = ArrayList<Node>()
                for ((lat, lon) in coords) {
                    val nn = Node(LatLon(lat, lon))
                    dataset.addPrimitive(nn)
                    seq.add(nn)
                }
                val w = Way()
                for (nn in seq) w.addNode(nn)
                dataset.addPrimitive(w)
                w
            } else if (coords.isNotEmpty()) {
                val (lat, lon) = coords[0]
                val n = Node(LatLon(lat, lon))
                dataset.addPrimitive(n)
                n
            } else {
                null
            }
            if (prim != null) putAll(prim, tags)
        }
        refresh()
    }

    fun selectOnMap(anchors: Collection<OsmPrimitive>) {
        try {
            dataset.setSelected(ArrayList(anchors))
        } catch (_: Exception) {
        }
    }

    fun clearMapSelection() {
        try {
            dataset.clearSelection()
        } catch (_: Exception) {
        }
    }

    fun discoveredTypes(): Set<String> = discoverNoteTypes(nodeRecs(), wayRecs())

    fun refresh() {
        try {
            onRefresh()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun primTag(prim: OsmPrimitive?, key: String, default: String = ""): String {
            if (prim == null) return default
            return try {
                prim.get(key) ?: default
            } catch (_: Exception) {
                default
            }
        }

        fun noteText(prim: OsmPrimitive?): String = primTag(prim, NotesTags.TAG_TEXT)

        fun noteType(prim: OsmPrimitive?): String {
            val t = primTag(prim, NotesTags.TAG_TYPE)
            return t.ifEmpty { "issue" }
        }

        fun noteDone(prim: OsmPrimitive?): Boolean =
            primTag(prim, NotesTags.TAG_DONE).lowercase() in setOf("yes", "true", "1")

        fun noteSeverity(prim: OsmPrimitive?): String {
            val s = primTag(prim, NotesTags.TAG_SEVERITY).lowercase()
            return if (s in NotesTags.SEVERITIES) s else "minor"
        }

        fun noteRefs(prim: OsmPrimitive?): String = primTag(prim, NotesTags.TAG_REFS)

        fun noteId(prim: OsmPrimitive?): String = primTag(prim, NotesTags.TAG_ID)

        fun putAll(prim: OsmPrimitive, tags: Map<String, String>) {
            for ((k, v) in tags) {
                if (v.isNotEmpty()) prim.put(k, v)
            }
        }

        fun refToken(prim: OsmPrimitive): String {
            return try {
                val kind = when (prim) {
                    is Way -> RefKind.WAY
                    is Relation -> RefKind.RELATION
                    else -> RefKind.NODE
                }
                refToken(kind, prim.uniqueId)
            } catch (_: Exception) {
                ""
            }
        }

        fun resolveRef(dataset: DataSet, token: String): OsmPrimitive? {
            val parsed = parseRefToken(token) ?: return null
            val type = when (parsed.kind) {
                RefKind.NODE -> OsmPrimitiveType.NODE
                RefKind.WAY -> OsmPrimitiveType.WAY
                RefKind.RELATION -> OsmPrimitiveType.RELATION
            }
            return try {
                dataset.getPrimitiveById(parsed.uniqueId, type)
            } catch (_: Exception) {
                null
            }
        }

        fun Node.toNodeRec(): NoteNodeRec {
            val c = coor
            return NoteNodeRec(
                uniqueId = uniqueId,
                lat = c?.lat() ?: 0.0,
                lon = c?.lon() ?: 0.0,
                tags = keys.toMap(),
                deleted = isDeleted,
                incomplete = isIncomplete,
            )
        }

        fun Way.toWayRec(): NoteWayRec = NoteWayRec(
            uniqueId = uniqueId,
            nodeUniqueIds = nodes.map { it.uniqueId },
            tags = keys.toMap(),
            deleted = isDeleted,
            incomplete = isIncomplete,
        )

        fun anchorCoord(anchor: OsmPrimitive): Pair<Double, Double>? {
            return try {
                if (anchor is Way) {
                    val nodes = anchor.nodes
                    if (nodes.isEmpty()) return null
                    val lat = nodes.sumOf { it.coor.lat() } / nodes.size.toDouble()
                    val lon = nodes.sumOf { it.coor.lon() } / nodes.size.toDouble()
                    lat to lon
                } else {
                    val c = (anchor as Node).coor
                    c.lat() to c.lon()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
