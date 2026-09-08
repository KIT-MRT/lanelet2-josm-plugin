package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.actions.search.SearchAction
import org.openstreetmap.josm.data.osm.search.SearchCompiler
import org.openstreetmap.josm.data.osm.search.SearchMode
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.gui.MapFrameListener
import org.openstreetmap.josm.gui.MapView
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Map-view overlay from `autotag_hook.py` (file_origin chip + From sel).
 * Null-layout child of [MapView], same placement as the Jython HUD.
 */
internal object AutotagHud {
    private val HUD_BG = Color(30, 30, 30)
    private val HUD_FG = Color(240, 240, 240)
    private val HUD_BORDER = Color(200, 200, 60)

    private var panel: JPanel? = null
    private var label: JLabel? = null
    private var mapView: MapView? = null
    private var mapFrameListener: MapFrameListener? = null

    fun installListener() {
        if (mapFrameListener != null) return
        val listener = MapFrameListener { oldFrame: MapFrame?, newFrame: MapFrame? ->
            try {
                if (oldFrame != null) {
                    try {
                        detachFrom(oldFrame.mapView)
                    } catch (_: Exception) {
                    }
                }
                if (newFrame != null && AutotagHook.isEnabled()) {
                    attachTo(newFrame.mapView)
                }
            } catch (_: Exception) {
            }
        }
        mapFrameListener = listener
        try {
            MainApplication.addMapFrameListener(listener)
        } catch (_: Exception) {
        }
    }

    fun uninstallListener() {
        val listener = mapFrameListener
        if (listener != null) {
            try {
                MainApplication.removeMapFrameListener(listener)
            } catch (_: Exception) {
            }
            mapFrameListener = null
        }
    }

    fun show() {
        try {
            val current = MainApplication.getMap() ?: return
            attachTo(current.mapView)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        detachFrom(mapView)
    }

    fun refresh() {
        val p = panel
        val lab = label
        if (p == null || lab == null) return
        val path = AutotagHook.getFileOrigin()
        val name = AutotagLogic.fileOriginBasename(path)
        if (path != null) {
            lab.text = "file_origin: $name"
            lab.toolTipText = "Click to select all elements with file_origin=$path"
        } else {
            lab.text = "file_origin: (none)"
            lab.toolTipText = "No file_origin autotag set. Use From sel or the Autotag dialog."
        }
        try {
            p.invalidate()
            val pref = p.preferredSize
            if (pref.width < 120) pref.width = 120
            if (pref.height < 24) pref.height = 24
            p.size = pref
            p.setLocation(AutotagLogic.HUD_X, AutotagLogic.HUD_Y)
            mapView?.validate()
            mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    fun listenerInstalled(): Boolean = mapFrameListener != null

    private fun attachTo(mv: MapView?) {
        if (mv == null) return
        var p = panel
        if (p == null) {
            p = createPanel()
        }
        if (mapView === mv) {
            refresh()
            return
        }
        hide()
        try {
            mv.add(p)
            mapView = mv
            refresh()
            try {
                mv.setComponentZOrder(p, 0)
            } catch (_: Exception) {
            }
            mv.validate()
            mv.repaint()
        } catch (_: Exception) {
            mapView = null
        }
    }

    private fun detachFrom(mv: MapView?) {
        val p = panel
        if (mv == null || p == null) {
            if (mapView === mv) mapView = null
            return
        }
        try {
            mv.remove(p)
            mv.repaint()
        } catch (_: Exception) {
        }
        if (mapView === mv) mapView = null
    }

    private fun createPanel(): JPanel {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        p.background = HUD_BG
        p.isOpaque = true
        p.isFocusable = false
        p.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(HUD_BORDER, 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 6),
        )
        val lab = JLabel("file_origin: (none)")
        lab.foreground = HUD_FG
        lab.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lab.isOpaque = false
        lab.isFocusable = false
        lab.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                try {
                    searchCurrentFileOrigin()
                } catch (_: Exception) {
                }
            }
        }
        lab.addMouseListener(click)
        p.addMouseListener(click)
        val grab = JButton("From sel")
        grab.toolTipText =
            "Replace the file_origin autotag with the value from the current " +
                "selection. Aborts if the selection has more than one file_origin."
        grab.isFocusable = false
        grab.isRequestFocusEnabled = false
        grab.isFocusPainted = false
        grab.margin = Insets(1, 6, 1, 6)
        grab.background = Color(50, 50, 50)
        grab.foreground = HUD_FG
        grab.isOpaque = true
        grab.addActionListener { grabFileOriginFromSelection() }
        p.add(lab)
        p.add(grab)
        panel = p
        label = lab
        return p
    }

    fun searchCurrentFileOrigin() {
        val path = AutotagHook.getFileOrigin()
        if (path == null) {
            Dialogs.warn(
                "No file_origin autotag is set.\n" +
                    "Use From sel on a primitive that already has file_origin, " +
                    "or set it in the Autotag dialog.",
                "Autotag file_origin",
            )
            return
        }
        try {
            val query = SearchCompiler.buildSearchStringForTag(AutotagLogic.FILE_ORIGIN_TAG, path)
            SearchAction.search(query, SearchMode.replace)
        } catch (_: Exception) {
            Dialogs.warn(
                "Could not search for file_origin=$path",
                "Autotag file_origin",
            )
        }
    }

    fun grabFileOriginFromSelection() {
        val layer = LaneletUtils.getEditLayer()
        if (layer == null) {
            Dialogs.warn("No editable layer selected.", "Autotag file_origin")
            return
        }
        val selected = try {
            layer.data.selected.toList()
        } catch (_: Exception) {
            emptyList()
        }
        if (selected.isEmpty()) {
            Dialogs.warn(
                "Nothing selected. Select one or more nodes, ways or relations\n" +
                    "that share precisely one file_origin.",
                "Autotag file_origin",
            )
            return
        }
        val origins = AutotagHook.uniqueFileOriginsInSelection()
        if (origins.isEmpty()) {
            Dialogs.warn(
                "No file_origin tag in the current selection.\n" +
                    "Select primitives that already have file_origin.",
                "Autotag file_origin",
            )
            return
        }
        if (origins.size > 1) {
            Dialogs.warn(
                "Selection has ${origins.size} different file_origin values.\n" +
                    "Select primitives with precisely one file_origin.",
                "Autotag file_origin",
            )
            return
        }
        AutotagHook.setFileOrigin(origins[0])
        Dialogs.infoAutoClose(
            "Autotag file_origin: ${AutotagLogic.fileOriginBasename(origins[0])}",
            "Autotag file_origin",
            2000,
        )
    }
}
