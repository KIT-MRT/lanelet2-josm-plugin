package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.util.ArrayList

/**
 * Validate that every lanelet has exactly one left and one right border way.
 *
 * No OSM mutation; selects broken relations. No undo. Port of
 * `check_lanelet_borders.py`.
 */
object CheckLaneletBorders {
    const val TITLE = "Check Lanelet Borders"

    data class BrokenBorders(
        val broken: List<Relation>,
        val countLt1: Int,
        val countGt1: Int,
    )

    fun countLeftRightMembers(lanelet: Relation): Pair<Int, Int> {
        var leftC = 0
        var rightC = 0
        for (m in lanelet.members) {
            val role = m.role
            val mem = m.member ?: continue
            if (mem !is Way) continue
            when (role) {
                "left" -> leftC++
                "right" -> rightC++
            }
        }
        return Pair(leftC, rightC)
    }

    /**
     * Type is compared to `"lanelet"` exactly (not case-folded), matching the Jython.
     * A lanelet missing one side *and* duplicated on the other increments both counters.
     */
    fun findBrokenLanelets(data: DataSet): BrokenBorders {
        val broken = ArrayList<Relation>()
        var countLt1 = 0
        var countGt1 = 0
        for (prim in data.relations) {
            if (prim == null || prim.isDeleted || prim.get("type") != "lanelet") continue
            val (leftC, rightC) = countLeftRightMembers(prim)
            if (leftC != 1 || rightC != 1) {
                broken.add(prim)
                if (leftC < 1 || rightC < 1) countLt1++
                if (leftC > 1 || rightC > 1) countGt1++
            }
        }
        return BrokenBorders(broken, countLt1, countGt1)
    }

    fun selectBroken(data: DataSet, broken: Collection<Relation>) {
        data.setSelected(ArrayList(broken))
        zoomTo(broken)
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    fun run(showOkFeedback: Boolean = true, ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val result = findBrokenLanelets(layer.data)
        if (result.broken.isEmpty()) {
            if (showOkFeedback) {
                ui.infoAutoClose("Lanelet borders are correct.", TITLE, 1000)
            }
            return
        }
        selectBroken(layer.data, result.broken)
        val msg =
            "Selected ${result.broken.size} lanelet(s) with border issues.\n\n" +
                "- ${result.countLt1} with <1 left or <1 right border\n" +
                "- ${result.countGt1} with >1 left or >1 right border"
        ui.warn(msg, TITLE)
    }

    private fun zoomTo(prims: Collection<Relation>) {
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
}
