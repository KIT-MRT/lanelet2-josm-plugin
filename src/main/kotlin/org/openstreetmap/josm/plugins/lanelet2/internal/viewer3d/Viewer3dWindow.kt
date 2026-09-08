package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/** Configuration UI for the live 3D viewer (`ll2_viewer3d_window.py`). */
object Viewer3dWindow {
    fun run() {
        val parent = Dialogs.parent()
        val enabledBox = JCheckBox("Stream active layer to the 3D viewer", Viewer3dSettings.isEnabled())
        val hostField = JTextField(Viewer3dSettings.getHost(), 16)
        val ingestPortField = JTextField(Viewer3dSettings.getIngestPort().toString(), 16)
        val httpPortField = JTextField(Viewer3dSettings.getHttpPort().toString(), 16)
        val profileBox = JCheckBox("Log per-stage timings to console", Viewer3dSettings.profileEnabled())
        val cullBox = JCheckBox(
            "Stream only a square around the JOSM view centre",
            Viewer3dSettings.cullEnabled(),
        )
        val cullRangeField = JTextField(Viewer3dSettings.getCullRangeM().toString(), 16)
        val followBox = JCheckBox(
            "Pan 3D camera when the JOSM view moves",
            Viewer3dSettings.followViewEnabled(),
        )
        val driveBox = JCheckBox(
            "Pan JOSM (and culling) with the 3D camera",
            Viewer3dSettings.driveViewEnabled(),
        )
        val statusLabel = JLabel()
        fun refreshStatus() {
            val host = hostField.text.trim().ifBlank { Viewer3dSettings.DEFAULT_HOST }
            val httpPort = httpPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_HTTP_PORT
            statusLabel.text = "Viewer server: ${Viewer3dHook.serverProcess().statusText(host, httpPort)}"
        }
        refreshStatus()

        val form = JPanel(GridLayout(8, 2, 6, 6))
        form.add(JLabel("Viewer host:"))
        form.add(hostField)
        form.add(JLabel("Viewer ingest port:"))
        form.add(ingestPortField)
        form.add(JLabel("Viewer HTTP port:"))
        form.add(httpPortField)
        form.add(JLabel("Profiling logs:"))
        form.add(profileBox)
        form.add(JLabel("View culling:"))
        form.add(cullBox)
        form.add(JLabel("Cull range (metres, square side):"))
        form.add(cullRangeField)
        form.add(JLabel("Camera follow:"))
        form.add(followBox)
        form.add(JLabel("Drive JOSM view:"))
        form.add(driveBox)

        val startBtn = JButton("Start viewer server")
        val stopBtn = JButton("Stop viewer server")
        val openBtn = JButton("Open browser tab")
        val server = Viewer3dHook.serverProcess()

        startBtn.addActionListener {
            val host = hostField.text.trim().ifBlank { Viewer3dSettings.DEFAULT_HOST }
            val ingest = ingestPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_INGEST_PORT
            val http = httpPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_HTTP_PORT
            val (ok, msg) = server.start(host, ingest, http, profileBox.isSelected, openBrowser = true)
            refreshStatus()
            if (ok) Dialogs.infoAutoClose(msg, "Live 3D Viewer", 2000)
            else Dialogs.error(msg, "Live 3D Viewer")
        }
        stopBtn.addActionListener {
            val host = hostField.text.trim().ifBlank { Viewer3dSettings.DEFAULT_HOST }
            val http = httpPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_HTTP_PORT
            val (ok, msg) = server.stop(host, http)
            refreshStatus()
            if (ok) Dialogs.infoAutoClose(msg, "Live 3D Viewer", 2000)
            else Dialogs.error(msg, "Live 3D Viewer")
        }
        openBtn.addActionListener {
            val host = hostField.text.trim().ifBlank { Viewer3dSettings.DEFAULT_HOST }
            val http = httpPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_HTTP_PORT
            val (ok, url) = server.openBrowserTab(host, http)
            if (!ok) {
                JOptionPane.showMessageDialog(
                    parent,
                    "Could not open a browser automatically.\nOpen this URL manually:\n$url",
                    "Live 3D Viewer",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        btnRow.add(startBtn)
        btnRow.add(stopBtn)
        btnRow.add(openBtn)

        val south = JPanel(BorderLayout(4, 4))
        south.add(statusLabel, BorderLayout.NORTH)
        south.add(btnRow, BorderLayout.SOUTH)
        south.add(
            JLabel(
                "<html><br>With view culling on, only a fixed-size square centred on the " +
                    "JOSM view is streamed. Camera follow pans the 3D view when you pan JOSM. " +
                    "Drive JOSM view recentres JOSM (and the cull box) after ~30 m of 3D camera " +
                    "travel so walking toward the streamed edge loads the next region.</html>",
            ),
            BorderLayout.CENTER,
        )

        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        panel.add(enabledBox, BorderLayout.NORTH)
        panel.add(form, BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)

        val result = JOptionPane.showConfirmDialog(
            parent, panel, "Live 3D Viewer",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return

        val enabled = enabledBox.isSelected
        val host = hostField.text.trim().ifBlank { Viewer3dSettings.DEFAULT_HOST }
        val ingestPort = ingestPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_INGEST_PORT
        val httpPort = httpPortField.text.trim().toIntOrNull() ?: Viewer3dSettings.DEFAULT_HTTP_PORT
        val cullRange = cullRangeField.text.trim().toDoubleOrNull()?.toInt()
            ?: Viewer3dSettings.DEFAULT_CULL_RANGE_M

        Viewer3dSettings.saveConfig(
            enabled = enabled,
            host = host,
            ingestPort = ingestPort,
            httpPort = httpPort,
            profile = profileBox.isSelected,
            cullOn = cullBox.isSelected,
            cullRangeM = cullRange,
            followView = followBox.isSelected,
            driveView = driveBox.isSelected,
        )
        Viewer3dHook.reinstallFromSettings()

        if (enabled) {
            if (!server.isRunning(host, httpPort)) {
                val (ok, msg) = server.start(host, ingestPort, httpPort, profileBox.isSelected, openBrowser = true)
                if (!ok) {
                    Dialogs.error(msg, "Live 3D Viewer")
                    return
                }
            }
            Dialogs.infoAutoClose(
                "3D streaming ON -> $host:$ingestPort.\nBrowser tab: http://$host:$httpPort/",
                "Live 3D Viewer",
                3000,
            )
        } else {
            Dialogs.infoAutoClose("3D streaming OFF.", "Live 3D Viewer", 3000)
        }
    }
}
