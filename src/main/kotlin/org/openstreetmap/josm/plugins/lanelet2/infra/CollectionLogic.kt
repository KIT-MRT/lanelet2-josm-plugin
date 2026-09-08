package org.openstreetmap.josm.plugins.lanelet2.infra

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings

/**
 * Headless rules for the collection dialog and the wizards that sit on it.
 *
 * Port of the non-Swing decisions in `lanelet2_collection_dialog.py` /
 * `lanelet2_dialogs.py`. The Swing shells live in [CollectionDialog].
 */
object CollectionLogic {
    const val GITHUB_BASE =
        "https://github.com/fzi-forschungszentrum-informatik/Lanelet2/blob/master/lanelet2_core/doc/"

    const val HIGHLIGHT_LINESTRINGS = "linestrings"
    const val HIGHLIGHT_WAYS = "ways"

    const val KIND_LANELET = "lanelet(s)"
    const val KIND_RELATION = "relation(s)"

    val DEFAULT_LANELET_HELP_LINKS: List<Pair<String, String>> = listOf(
        "LaneletAndAreaTagging" to "LaneletAndAreaTagging.md",
        "LinestringTagging" to "LinestringTagging.md",
    )

    val DEFAULT_RELATION_HELP_LINKS: List<Pair<String, String>> = listOf(
        "RegulatoryElementTagging" to "RegulatoryElementTagging.md",
        "LaneletPrimitives" to "LaneletPrimitives.md",
    )

    data class DialogPos(val x: Int, val y: Int)

    /**
     * Incremental collection + undo batches.
     *
     * Jython seeds [addHistory] with a copy of [initial] when non-empty, so the
     * first Undo removes the pre-populated set.
     *
     * Membership is by identity. Jython `item not in collected` uses Java
     * `equals`; [org.openstreetmap.josm.data.osm.OsmPrimitive.equals] is OSM
     * `id`, so every unsaved primitive (`id == 0`) would collapse to one entry.
     * Identity matches how a mapper uses the dialog on a new layer.
     */
    class Basket<T>(initial: List<T> = emptyList()) {
        private val collected: MutableList<T> = ArrayList(initial)
        private val addHistory: MutableList<List<T>> =
            if (initial.isEmpty()) ArrayList() else arrayListOf(ArrayList(initial))

        fun items(): List<T> = collected.toList()

        fun size(): Int = collected.size

        fun isEmpty(): Boolean = collected.isEmpty()

        fun add(candidates: List<T>): List<T> {
            val added = ArrayList<T>()
            for (item in candidates) {
                if (collected.none { it === item }) {
                    collected.add(item)
                    added.add(item)
                }
            }
            if (added.isNotEmpty()) addHistory.add(added)
            return added
        }

        /** Null means nothing to undo (Jython toast). */
        fun undo(): List<T>? {
            if (addHistory.isEmpty()) return null
            val last = addHistory.removeAt(addHistory.lastIndex)
            for (item in last) {
                collected.removeAll { it === item }
            }
            return last
        }

        fun remove(item: T): Boolean {
            val idx = collected.indexOfFirst { it === item }
            if (idx < 0) return false
            collected.removeAt(idx)
            return true
        }

        fun clear() {
            collected.clear()
            addHistory.clear()
        }

        fun canFinish(minCount: Int): Boolean = collected.size >= minCount
    }

    /**
     * Opt-in plus a display. Headless (tests / CI) always stays on the
     * current-selection shortcut so `run()` cannot open a window.
     */
    fun shouldOpenCollectionDialog(
        enabled: Boolean = LaneletSettings.isCollectionDialogEnabled(),
        headless: Boolean = Dialogs.isHeadless(),
    ): Boolean = enabled && !headless

    fun extractLaneletsForAdd(
        data: DataSet,
        selection: Iterable<OsmPrimitive?>,
        modeA: Boolean,
    ): List<Relation> =
        if (modeA) LaneletSelection.extractLanelets(selection)
        else LaneletSelection.extractLaneletsOrFromLinestrings(data, selection)

    fun extractRelationsForAdd(
        data: DataSet,
        selection: Iterable<OsmPrimitive?>,
        relType: String,
        relSubtype: String?,
        modeA: Boolean,
    ): List<Relation> =
        if (modeA) LaneletSelection.extractRelations(selection, relType, relSubtype)
        else LaneletSelection.extractRelationsOrFromWays(data, selection, relType, relSubtype)

    fun relationMembers(rel: Relation?): List<OsmPrimitive> {
        val out = ArrayList<OsmPrimitive>()
        if (rel == null) return out
        try {
            for (m in rel.members) {
                val mem = m.member ?: continue
                out.add(mem)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun relationMembersFromList(relations: Iterable<Relation?>): List<OsmPrimitive> {
        val seen = HashSet<Long>()
        val out = ArrayList<OsmPrimitive>()
        for (rel in relations) {
            for (mem in relationMembers(rel)) {
                try {
                    val uid = mem.uniqueId
                    if (uid !in seen) {
                        seen.add(uid)
                        out.add(mem)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    /**
     * Primitives the Select / S buttons should highlight.
     * Empty member list falls back to the relations themselves (Jython).
     */
    fun primitivesToSelect(
        collected: List<Relation>,
        selectMembers: Boolean,
        single: Relation? = null,
    ): List<OsmPrimitive> {
        if (single != null) {
            if (!selectMembers) return listOf(single)
            val members = relationMembers(single)
            return members.ifEmpty { listOf(single) }
        }
        if (!selectMembers) return collected
        val members = relationMembersFromList(collected)
        return members.ifEmpty { collected }
    }

    fun styledMessageHtml(message: String, highlight: String): String {
        val styled = "<b><font size='+1'>$highlight</font></b>"
        val messageStyled = message.replace(highlight, styled)
        return "<html>${messageStyled.replace("\n", "<br>")}</html>"
    }

    fun multiStepLabelHtml(htmlBase: String, collectedCount: Int): String =
        if (collectedCount > 0) "$htmlBase<br>(Added $collectedCount so far.)</html>"
        else "$htmlBase</html>"

    fun countLabel(count: Int, kind: String): String = "Collected: $count $kind"

    fun helpBody(bodyText: String, docLinks: List<Pair<String, String>>?): String {
        if (docLinks.isNullOrEmpty()) return bodyText
        val sb = StringBuilder(bodyText)
        sb.append("\n\n--- Documentation ---\n")
        for ((name, path) in docLinks) {
            sb.append(name).append('\n')
            sb.append(GITHUB_BASE).append(path).append('\n')
        }
        return sb.toString()
    }

    /**
     * Offset from the parent's upper-left, then clamp so the dialog stays
     * inside the parent (Jython `position_dialog_upper_left`).
     */
    fun positionUpperLeft(
        parentX: Int,
        parentY: Int,
        parentW: Int,
        parentH: Int,
        dialogW: Int,
        dialogH: Int,
        offsetXFrac: Double = 0.06,
        offsetYFrac: Double = 0.09,
    ): DialogPos {
        var x = (parentX + parentW * offsetXFrac).toInt()
        var y = (parentY + parentH * offsetYFrac).toInt()
        x = minOf(x, parentX + parentW - dialogW - 20)
        y = minOf(y, parentY + parentH - dialogH - 20)
        x = maxOf(x, parentX + 10)
        y = maxOf(y, parentY + 10)
        return DialogPos(x, y)
    }

    fun primIdStr(prim: OsmPrimitive?): String {
        if (prim == null) return "?"
        return try {
            prim.uniqueId.toString()
        } catch (_: Exception) {
            "?"
        }
    }

    fun tagOrDash(prim: OsmPrimitive?, key: String): String {
        return try {
            prim?.get(key) ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }

    fun emptyExtractMessage(kindLanelets: Boolean): String =
        if (kindLanelets) "Select at least 1 lanelet or linestring to add."
        else "Select at least 1 relation or way to add."

    fun needMinMessage(minCount: Int, kind: String): String = "Need at least $minCount $kind."

    fun nothingCollectedMessage(kind: String): String = "No $kind collected yet."
}
