package org.openstreetmap.josm.plugins.lanelet2.tools

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Chord-style modal for applying linestring presets to selected ways.
 *
 * Port of `core/josm_tools/quick_tag_modal.py`. Registry shortcut is `None`;
 * the launcher binds unmodified Space (the module docstring says Ctrl+Space).
 * Dialog x-position uses Python 2 int division `(pw - dw) / 2`.
 */
object QuickTagModal {
    const val TITLE = "Quick Tag Modal"
    const val DIALOG_TITLE = "Quick Tag"
    const val ACTION_KEY = "lanelet2_quick_tag"
    const val MIN_WIDTH = 560

    sealed class Value {
        data class Tags(val tags: Map<String, String>) : Value()
        data class Submenu(val stateId: String) : Value()
    }

    data class Entry(val label: String, val value: Value)

    val ROOT: Map<String, Entry> = linkedMapOf(
        "B" to Entry("Bike marking...", Value.Submenu("BIKE")),
        "V" to Entry("Virtual", Value.Tags(mapOf("type" to "virtual"))),
        "D" to Entry("Dashed line", Value.Tags(mapOf("type" to "line_thin", "subtype" to "dashed"))),
        "S" to Entry("Solid line", Value.Tags(mapOf("type" to "line_thin", "subtype" to "solid"))),
        "P" to Entry("Pedestrian marking", Value.Tags(mapOf("type" to "pedestrian_marking"))),
        "R" to Entry("Road border", Value.Tags(mapOf("type" to "road_border"))),
        "C" to Entry("Curbstone...", Value.Submenu("CURB")),
        "X" to Entry("Stop line", Value.Tags(mapOf("type" to "stop_line"))),
    )

    val BIKE: Map<String, Entry> = linkedMapOf(
        "D" to Entry("Bike dashed", Value.Tags(mapOf("type" to "bike_marking", "subtype" to "dashed"))),
        "S" to Entry("Bike solid", Value.Tags(mapOf("type" to "bike_marking", "subtype" to "solid"))),
    )

    val CURB: Map<String, Entry> = linkedMapOf(
        "H" to Entry("High", Value.Tags(mapOf("type" to "curbstone", "subtype" to "high"))),
        "L" to Entry("Low...", Value.Submenu("CURB_LOW")),
    )

    val CURB_LOW: Map<String, Entry> = linkedMapOf(
        "L" to Entry(
            "Low restricted (default)",
            Value.Tags(mapOf("type" to "curbstone", "subtype" to "low", "road_network" to "no")),
        ),
        "S" to Entry(
            "Low sidewalk entry",
            Value.Tags(
                mapOf(
                    "type" to "curbstone",
                    "subtype" to "low",
                    "road_network" to "yes",
                    "sidewalk_entry" to "true",
                ),
            ),
        ),
        "R" to Entry(
            "Low road side-entry",
            Value.Tags(
                mapOf("type" to "curbstone_regular_road", "subtype" to "low", "road_network" to "yes"),
            ),
        ),
    )

    val STATES: Map<String, Map<String, Entry>> = mapOf(
        "ROOT" to ROOT,
        "BIKE" to BIKE,
        "CURB" to CURB,
        "CURB_LOW" to CURB_LOW,
    )

    fun hintLine(stateId: String): String {
        val table = STATES.getValue(stateId)
        val parts = table.keys.sorted().map { key -> "$key:${table.getValue(key).label}" }
        return "[$stateId] ${parts.joinToString("  ")}  (Esc)"
    }

    fun applyTags(
        data: DataSet,
        ways: Collection<Way>,
        label: String,
        tags: Map<String, String>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Int {
        if (ways.isEmpty()) return 0
        val commands = ways.map { w ->
            val nw = Way(w)
            for ((k, v) in tags) nw.put(k, v)
            ChangeCommand(w, nw)
        }
        applySequence("Quick tag: $label", commands, layer, undo)
        return ways.size
    }

    fun selectedWays(data: DataSet): List<Way> {
        val ways = ArrayList<Way>()
        for (prim in data.selected) {
            if (prim != null && prim is Way) ways.add(prim)
        }
        return ways
    }

    /**
     * Space on the main frame root pane, matching `install_quick_tag_shortcut`.
     * No-op without a frame. Disabled while focus is in a text component.
     */
    fun installShortcut() {
        val frame = try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        } ?: return
        val rp = frame.rootPane ?: return
        val im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rp.actionMap
        im.put(KeyStroke.getKeyStroke("SPACE"), ACTION_KEY)
        am.put(
            ACTION_KEY,
            object : AbstractAction() {
                override fun isEnabled(): Boolean = !LaneletUtils.focusInTextComponent()
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    if (LaneletUtils.focusInTextComponent()) return
                    run()
                }
            },
        )
    }

    fun run(ui: UserPrompts = Dialogs) {
        if (Dialogs.isHeadless()) {
            applyFromCurrentSelection(ui)
            return
        }
        val parent = try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        showDialog(parent, ui)
    }

    /**
     * Headless / test path: apply nothing (the modal is the interaction).
     * Kept so [run] never opens a window in CI.
     */
    internal fun applyFromCurrentSelection(ui: UserPrompts) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
        }
    }

    internal fun applyLeaf(ui: UserPrompts, label: String, tags: Map<String, String>) {
        val layer = requireVisibleEditLayer()
        if (layer == null || !layer.isVisible) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val ways = selectedWays(layer.data)
        if (ways.isEmpty()) {
            ui.infoAutoClose(
                "No ways selected. Select one or more ways and try again.",
                TITLE,
                1500,
            )
            return
        }
        applyTags(layer.data, ways, label, tags, layer)
    }

    private fun showDialog(parent: JFrame?, ui: UserPrompts) {
        val dlg = JDialog(parent, DIALOG_TITLE, false)
        dlg.isUndecorated = true
        val panel = JPanel(BorderLayout())
        panel.background = Color(30, 30, 30)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(200, 200, 60), 1),
            BorderFactory.createEmptyBorder(6, 10, 6, 10),
        )
        val label = JLabel(hintLine("ROOT"))
        label.foreground = Color(240, 240, 240)
        label.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        panel.add(label, BorderLayout.CENTER)
        dlg.contentPane = panel

        val stateRef = arrayOf("ROOT")

        fun close() {
            try {
                dlg.isVisible = false
                dlg.dispose()
            } catch (_: Exception) {
            }
        }

        fun rebuildBindings() {
            val rp = dlg.rootPane
            val im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            val am = rp.actionMap
            im.clear()
            am.clear()
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
            am.put(
                "close",
                object : AbstractAction() {
                    override fun actionPerformed(e: java.awt.event.ActionEvent?) = close()
                },
            )
            val table = STATES.getValue(stateRef[0])
            for (keyLetter in table.keys) {
                val actionName = "k_$keyLetter"
                im.put(KeyStroke.getKeyStroke(keyLetter.lowercase()[0].code, 0), actionName)
                im.put(KeyStroke.getKeyStroke(keyLetter.uppercase()[0].code, 0), actionName)
                am.put(
                    actionName,
                    object : AbstractAction() {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            val entry = STATES[stateRef[0]]?.get(keyLetter) ?: return
                            when (val value = entry.value) {
                                is Value.Tags -> {
                                    close()
                                    applyLeaf(ui, entry.label, value.tags)
                                }
                                is Value.Submenu -> {
                                    if (value.stateId in STATES) {
                                        stateRef[0] = value.stateId
                                        label.text = hintLine(value.stateId)
                                        dlg.pack()
                                        positionTopCenter(dlg, parent)
                                        rebuildBindings()
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }

        rebuildBindings()
        dlg.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {}
            override fun windowLostFocus(e: WindowEvent?) = close()
        })
        dlg.pack()
        val pref = dlg.size
        if (pref.width < MIN_WIDTH) {
            dlg.size = Dimension(MIN_WIDTH, pref.height)
        }
        positionTopCenter(dlg, parent)
        dlg.isVisible = true
        SwingUtilities.invokeLater {
            try {
                dlg.contentPane.isFocusable = true
                dlg.contentPane.requestFocusInWindow()
                dlg.toFront()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Jython `_position_top_center`. `(pw - dw) / 2` is integer division
     * (Jython 2 `/` on ints truncates).
     */
    internal fun positionTopCenter(dlg: JDialog, parent: java.awt.Component?) {
        try {
            if (parent == null) {
                dlg.setLocationRelativeTo(null)
                return
            }
            val loc = parent.locationOnScreen
            val pw = parent.width
            val dw = dlg.width
            val x = loc.x + (pw - dw) / 2
            val y = loc.y + 90
            dlg.setLocation(x, y)
        } catch (_: Exception) {
            dlg.setLocationRelativeTo(parent)
        }
    }
}
