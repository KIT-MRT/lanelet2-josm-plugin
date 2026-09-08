package org.openstreetmap.josm.plugins.lanelet2.tools

import org.openstreetmap.josm.actions.AutoScaleAction
import org.openstreetmap.josm.data.coor.EastNorth
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor
import org.openstreetmap.josm.data.projection.ProjectionRegistry
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles
import org.openstreetmap.josm.gui.mappaint.mapcss.MapCSSStyleSource
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.ArrayList
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.table.DefaultTableModel

/**
 * Visualise per-file footprints of a merged Lanelet2 map.
 *
 * Port of `core/josm_tools/highlight_file_boundaries.py`.
 *
 * Hulls are a new layer (not Commands). Mismatch detection always reads the
 * literal `file_origin` tag, even when [LaneletSettings.getHighlightGroupTag]
 * is customised — that split is Jython behaviour.
 */
object HighlightFileBoundaries {
    const val TITLE = "Highlight File Boundaries"
    const val DEFAULT_GROUP_TAG = "file_origin"
    const val FILE_ORIGIN_TAG = DEFAULT_GROUP_TAG
    const val UNTAGGED_ORIGIN = "<untagged>"
    const val STYLE_NAME = "ll2_file_boundaries"

    data class OriginMismatch(
        val rel: Relation,
        val type: String,
        val id: String,
        val parentFo: String,
        val memberFos: List<String>,
    )

    private var mismatchDialog: JDialog? = null

    fun groupTag(): String = LaneletSettings.getHighlightGroupTag()

    fun collectPointsByOrigin(dataset: DataSet, groupTag: String = groupTag()): Pair<Map<String, List<Pair<Double, Double>>>, Int> {
        val groups = LinkedHashMap<String, ArrayList<Pair<Double, Double>>>()
        var untagged = 0
        for (node in dataset.nodes) {
            try {
                if (node.isDeleted || node.isIncomplete) continue
                val fo = node.get(groupTag)
                if (fo.isNullOrEmpty()) {
                    untagged++
                    continue
                }
                val en = node.eastNorth ?: continue
                groups.getOrPut(fo) { ArrayList() }.add(Pair(en.east(), en.north()))
            } catch (_: Exception) {
            }
        }
        return groups to untagged
    }

    fun findRelationOriginMismatches(dataset: DataSet): List<OriginMismatch> {
        val rows = ArrayList<OriginMismatch>()
        val relations = try {
            dataset.relations
        } catch (_: Exception) {
            return rows
        }
        for (rel in relations) {
            try {
                if (rel == null || rel.isDeleted) continue
                val parentFo = rel.get(FILE_ORIGIN_TAG)
                if (parentFo.isNullOrEmpty()) continue
                val memberFos = LinkedHashSet<String>()
                var mismatched = false
                val members = try {
                    rel.members
                } catch (_: Exception) {
                    continue
                }
                for (m in members) {
                    val mem = try {
                        m.member
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    try {
                        if (mem.isDeleted) continue
                    } catch (_: Exception) {
                    }
                    val mfo = try {
                        mem.get(FILE_ORIGIN_TAG)
                    } catch (_: Exception) {
                        null
                    }
                    if (mfo.isNullOrEmpty()) {
                        mismatched = true
                        memberFos.add(UNTAGGED_ORIGIN)
                    } else if (mfo != parentFo) {
                        mismatched = true
                        memberFos.add(mfo)
                    }
                }
                if (mismatched) {
                    rows.add(
                        OriginMismatch(
                            rel = rel,
                            type = relTypeLabel(rel),
                            id = relIdLabel(rel),
                            parentFo = parentFo,
                            memberFos = memberFos.sorted(),
                        ),
                    )
                }
            } catch (_: Exception) {
            }
        }
        rows.sortWith(compareBy({ it.type }, { it.id }))
        return rows
    }

    fun relTypeLabel(rel: Relation): String {
        val t = try {
            rel.get("type")
        } catch (_: Exception) {
            null
        }
        return if (t.isNullOrEmpty()) "(no type)" else t
    }

    fun relIdLabel(rel: Relation): String = try {
        rel.uniqueId.toString()
    } catch (_: Exception) {
        "?"
    }

    fun originBasename(path: String?): String {
        if (path.isNullOrEmpty() || path == UNTAGGED_ORIGIN) return path ?: UNTAGGED_ORIGIN
        val base = File(path).name
        return base.ifEmpty { path }
    }

    fun layerStem(layer: OsmDataLayer): String {
        val assocName = try {
            layer.associatedFile?.name
        } catch (_: Exception) {
            null
        }
        val layerName = try {
            layer.name?.toString()
        } catch (_: Exception) {
            null
        }
        return layerStemFrom(assocName, layerName)
    }

    /** Jython `_layer_stem`: associated file name wins; empty layer name → `"map"`. */
    fun layerStemFrom(assocName: String?, layerName: String?): String {
        if (!assocName.isNullOrEmpty()) {
            return if (assocName.lowercase().endsWith(".osm")) assocName.dropLast(4) else assocName
        }
        var name = layerName?.ifEmpty { null } ?: "map"
        if (name.lowercase().endsWith(".osm")) name = name.dropLast(4)
        return name
    }

    fun buildLayerDataset(
        groups: Map<String, List<Pair<Double, Double>>>,
        order: List<String>,
        minE: Double,
        minN: Double,
        cell: Double,
        groupTag: String = groupTag(),
    ): Pair<DataSet, Int> {
        val proj = ProjectionRegistry.getProjection()
        val ds = DataSet()
        var ringCount = 0
        for ((idx, fo) in order.withIndex()) {
            val rings = FileBoundaryHull.hullRingsFor(groups.getValue(fo), minE, minN, cell)
            val label = File(fo).name.ifEmpty { fo }
            for (coords in rings) {
                val nodes = ArrayList<Node>()
                for ((e, n) in coords) {
                    val node = Node(proj.eastNorth2latlon(EastNorth(e, n)))
                    ds.addPrimitive(node)
                    nodes.add(node)
                }
                val way = Way()
                for (node in nodes) way.addNode(node)
                way.addNode(nodes[0])
                ds.addPrimitive(way)
                way.put("ll2_boundary", "yes")
                way.put("ll2_bnd", idx.toString())
                way.put(groupTag, fo)
                way.put("name", label)
                ringCount++
            }
        }
        return ds to ringCount
    }

    fun applyStyle(order: List<String>, colors: List<String>) {
        try {
            val existing = MapPaintStyles.getStyles().styleSources.toList()
            for (src in existing) {
                try {
                    if (src.title == FileBoundaryHull.STYLE_TITLE) {
                        MapPaintStyles.removeStyle(src)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val css = FileBoundaryHull.generateMapcss(order, colors)
        val source = MapCSSStyleSource(css)
        source.name = STYLE_NAME
        source.title = FileBoundaryHull.STYLE_TITLE
        MapPaintStyles.addStyle(source)
    }

    fun addLayerBelowActive(ds: DataSet, layerName: String, activeLayer: OsmDataLayer?) {
        val lm = MainApplication.getLayerManager()
        try {
            for (l in lm.layers.toList()) {
                if (l != null && l.name != null && l.name.toString() == layerName) {
                    lm.removeLayer(l)
                }
            }
        } catch (_: Exception) {
        }
        val newLayer = OsmDataLayer(ds, layerName, null)
        try {
            newLayer.setUploadDiscouraged(true)
        } catch (_: Exception) {
        }
        lm.addLayer(newLayer, false)
        if (activeLayer != null) {
            try {
                lm.setActiveLayer(activeLayer)
                val layers = lm.layers
                var editIdx = -1
                var debugIdx = -1
                for (i in layers.indices) {
                    val l = layers[i]
                    if (l === activeLayer) editIdx = i
                    if (l === newLayer) debugIdx = i
                }
                if (editIdx >= 0 && debugIdx >= 0) {
                    val target = minOf(editIdx, layers.size - 1)
                    if (target >= 0 && debugIdx != target) {
                        lm.moveLayer(newLayer, target)
                    }
                }
            } catch (_: Exception) {
            }
        }
        try {
            newLayer.invalidate()
        } catch (_: Exception) {
        }
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    fun relationHasBbox(rel: Relation): Boolean {
        val visitor = BoundingXYVisitor()
        return try {
            visitor.computeBoundingBox(ArrayList<OsmPrimitive>(listOf(rel)))
            visitor.bounds != null
        } catch (_: Exception) {
            false
        }
    }

    fun relationZoomTargets(rel: Relation): ArrayList<OsmPrimitive> {
        val targets = ArrayList<OsmPrimitive>()
        targets.add(rel)
        if (relationHasBbox(rel)) return targets
        try {
            for (m in rel.members) {
                val mem = try {
                    m.member
                } catch (_: Exception) {
                    null
                }
                try {
                    if (mem == null || mem.isDeleted) continue
                    if (mem is Relation || mem is Way || mem is Node) targets.add(mem)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return targets
    }

    fun selectAndPanRelation(layer: OsmDataLayer?, rel: Relation?) {
        if (layer == null || rel == null) return
        try {
            if (rel.isDeleted) return
        } catch (_: Exception) {
        }
        try {
            layer.data.setSelected(ArrayList<OsmPrimitive>(listOf(rel)))
        } catch (_: Exception) {
            return
        }
        val targets = relationZoomTargets(rel)
        try {
            AutoScaleAction.zoomTo(targets)
        } catch (_: Exception) {
        }
        try {
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    fun run(ui: UserPrompts = Dialogs) {
        val parent = Dialogs.parent()
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val dataset = try {
            layer.data
        } catch (_: Exception) {
            null
        }
        if (dataset == null) {
            ui.warn("The active layer has no data.", TITLE)
            return
        }

        val (groups, untagged) = collectPointsByOrigin(dataset)
        val mismatches = findRelationOriginMismatches(dataset)

        if (groups.isEmpty() && mismatches.isEmpty()) {
            ui.warn(
                "No points with a '$FILE_ORIGIN_TAG' tag were found. This tool visualises merged " +
                    "maps where elements keep their source file tag.",
                TITLE,
            )
            return
        }

        var hullOk = false
        var ringCount = 0
        var layerName = ""
        var cell = 0.0
        if (groups.isNotEmpty()) {
            val order = groups.keys.sorted()
            val colors = FileBoundaryHull.palette(order.size)
            val gridCellM = LaneletSettings.getMergeGridCellM()
            val extent = FileBoundaryHull.globalExtent(groups)
            if (extent != null) {
                cell = FileBoundaryHull.chooseCell(extent.minE, extent.minN, extent.maxE, extent.maxN, gridCellM)
                val built = buildLayerDataset(groups, order, extent.minE, extent.minN, cell)
                ringCount = built.second
                if (ringCount == 0 && mismatches.isEmpty()) {
                    ui.warn("Could not compute any boundaries from the points.", TITLE)
                    return
                }
                if (ringCount > 0) {
                    layerName = layerStem(layer) + "_boundaries.osm"
                    addLayerBelowActive(built.first, layerName, layer)
                    applyStyle(order, colors)
                    hullOk = true
                }
            }
        }

        if (mismatches.isNotEmpty() && !Dialogs.isHeadless()) {
            showMismatchWindow(parent, layer, mismatches)
        }

        val parts = ArrayList<String>()
        if (hullOk) {
            val totalPts = groups.values.sumOf { it.size }
            parts.add(
                "Drew $ringCount hull(s) for ${groups.size} file(s) from $totalPts tagged point(s)\n" +
                    "into layer '$layerName' (cell ~${java.lang.String.format(java.util.Locale.US, "%.1f", cell)} m).",
            )
            if (untagged > 0) {
                parts.add("$untagged point(s) without a $FILE_ORIGIN_TAG tag were ignored.")
            }
        }
        if (mismatches.isNotEmpty()) {
            parts.add("${mismatches.size} relation(s) with file_origin member mismatch (see table).")
        } else if (groups.isNotEmpty()) {
            parts.add("No relation file_origin mismatches.")
        }
        if (parts.isEmpty()) return
        ui.infoAutoClose(parts.joinToString("\n"), TITLE, 3500)
    }

    private fun showMismatchWindow(
        parent: java.awt.Component?,
        layer: OsmDataLayer,
        rows: List<OriginMismatch>,
    ) {
        try {
            mismatchDialog?.dispose()
        } catch (_: Exception) {
        }
        mismatchDialog = null

        val dlg = JDialog(parent as? java.awt.Frame, "file_origin mismatches", false)
        dlg.contentPane.layout = BorderLayout(8, 8)
        try {
            dlg.rootPane.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        } catch (_: Exception) {
        }

        val hint = JLabel(
            "<html>${rows.size} relation(s) whose <b>file_origin</b> disagrees with a direct " +
                "member. Click a row (or Zoom) to select it and pan the map.</html>",
        )
        val tableRows = rows.map { arrayOf<Any>(it.type, it.id) }.toTypedArray()
        val model = object : DefaultTableModel(tableRows, arrayOf("Type", "ID")) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.reorderingAllowed = false
        table.autoCreateRowSorter = true
        table.columnModel.getColumn(0).preferredWidth = 220
        table.columnModel.getColumn(1).preferredWidth = 100

        val detail = JLabel(" ")
        table.selectionModel.addListSelectionListener(object : ListSelectionListener {
            override fun valueChanged(e: ListSelectionEvent) {
                if (e.valueIsAdjusting) return
                gotoMismatchRow(table, rows, layer, detail)
            }
        })

        val zoomBtn = JButton("Zoom")
        zoomBtn.toolTipText =
            "Select the highlighted relation and pan the map to it " +
                "(use this when the row is already selected)"
        zoomBtn.addActionListener { gotoMismatchRow(table, rows, layer, detail) }

        val closeBtn = JButton("Close")
        closeBtn.addActionListener {
            try {
                dlg.isVisible = false
                dlg.dispose()
            } catch (_: Exception) {
            }
        }
        val south = JPanel(BorderLayout(6, 0))
        south.add(detail, BorderLayout.CENTER)
        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        btnRow.add(zoomBtn)
        btnRow.add(closeBtn)
        south.add(btnRow, BorderLayout.EAST)

        dlg.add(hint, BorderLayout.NORTH)
        dlg.add(JScrollPane(table), BorderLayout.CENTER)
        dlg.add(south, BorderLayout.SOUTH)

        dlg.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                mismatchDialog = null
            }
        })
        dlg.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dlg.pack()
        val n = rows.size
        val height = minOf(maxOf(260, 90 + n * 22), 520)
        dlg.size = Dimension(440, height)
        positionToolsDialogUpperLeft(dlg, parent)
        mismatchDialog = dlg
        dlg.isVisible = true
    }

    private fun gotoMismatchRow(
        table: JTable,
        rows: List<OriginMismatch>,
        layer: OsmDataLayer,
        detailLabel: JLabel,
    ) {
        val rec = mismatchRowRecord(table, rows) ?: return
        val memberTxt = rec.memberFos.joinToString(", ") { originBasename(it) }
        detailLabel.text = "relation ${originBasename(rec.parentFo)}; members: $memberTxt"
        selectAndPanRelation(layer, rec.rel)
    }

    internal fun mismatchRowRecord(table: JTable, rows: List<OriginMismatch>): OriginMismatch? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = try {
            table.convertRowIndexToModel(viewRow)
        } catch (_: Exception) {
            return null
        }
        if (modelRow < 0 || modelRow >= rows.size) return null
        return rows[modelRow]
    }
}

internal fun positionToolsDialogUpperLeft(
    dialog: JDialog,
    parent: java.awt.Component?,
    offsetXFrac: Double = 0.06,
    offsetYFrac: Double = 0.09,
) {
    if (parent == null) {
        dialog.setLocationRelativeTo(null)
        return
    }
    try {
        val loc = parent.locationOnScreen
        val pw = parent.width
        val ph = parent.height
        val dw = dialog.width
        val dh = dialog.height
        var x = (loc.x + pw * offsetXFrac).toInt()
        var y = (loc.y + ph * offsetYFrac).toInt()
        x = minOf(x, loc.x + pw - dw - 20)
        y = minOf(y, loc.y + ph - dh - 20)
        x = maxOf(x, loc.x + 10)
        y = maxOf(y, loc.y + 10)
        dialog.setLocation(x, y)
    } catch (_: Exception) {
        dialog.setLocationRelativeTo(parent)
    }
}
