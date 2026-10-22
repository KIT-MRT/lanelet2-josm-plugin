package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dHook
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.FileChoosers
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "KITScenes sensor data": point at the dataset, Start. The scene server
 * runs from the jar in the backends' virtualenv; the first Start offers to
 * install what it needs. With a map open in JOSM the scene is placed on it
 * (JOSM keeps the map, so editing and the Selection tab keep working), and
 * in city mode that map also picks the city.
 */
object SceneServerWindow {
    const val TITLE = "KITScenes Sensor Data"
    const val KEY_ROOT = "scenes.root"
    const val KEY_USE_JOSM_MAP = "scenes.use_josm_map"
    const val KEY_HTTP_PORT = "scenes.http_port"
    const val DEFAULT_HTTP_PORT = 8767

    val server: SceneServerProcess by lazy { SceneServerProcess() }
    private var open: JDialog? = null

    fun modeText(root: File): String = when {
        root.path.isBlank() -> "Choose the KITScenes folder: the dataset root, one split, or one scene."
        !root.isDirectory -> "Not a folder."
        SceneLaunch.detectMode(root) == SceneMode.SCENE -> "One scene."
        else -> "A whole city: every scene under this folder, on one map."
    }

    fun run() {
        if (Dialogs.isHeadless()) return
        open?.let { if (it.isDisplayable) { it.toFront(); return } }
        val dlg = JDialog(MainApplication.getMainFrame(), TITLE, false)
        open = dlg
        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dlg.layout = BorderLayout(8, 8)

        val rootField = JTextField(LaneletSettings.get(KEY_ROOT, ""), 40)
        val browseBtn = JButton("Browse…")
        val modeLabel = JLabel()
        val josmBox = JCheckBox("Place it on the map open in JOSM", LaneletSettings.getBoolean(KEY_USE_JOSM_MAP, true))
        josmBox.toolTipText = "<html>JOSM keeps the map (editable), the scene server adopts its position,<br>" +
            "and in city mode the map picks the city. Turns 3D streaming on.</html>"
        val setupLabel = JLabel()
        val serverLabel = JLabel()
        val log = JTextArea(16, 80).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        val startBtn = JButton("Start")
        val rescanBtn = JButton("Rescan everything")
        rescanBtn.toolTipText = "<html>Restart, reading every scene from the dataset instead of the local index<br>" +
            "(about 17 s on the network mount), and rewrite the index. For scenes that<br>" +
            "were unreadable or still being copied at the last index. New scenes need<br>" +
            "no rescan: Start finds them.</html>"
        val stopBtn = JButton("Stop")
        val viewerBtn = JButton("Open viewer")
        val closeBtn = JButton("Close")

        var busy = false
        val installLog = StringBuilder()
        var setupState: SceneSetup.Check? = null

        fun appendInstall(line: String) = SwingUtilities.invokeLater {
            installLog.append(line).append('\n')
            log.text = installLog.toString()
            log.caretPosition = log.document.length
        }

        fun hasJosmMap() = try {
            MainApplication.getLayerManager().editLayer != null
        } catch (_: Exception) {
            false
        }

        fun refresh() {
            modeLabel.text = modeText(File(rootField.text.trim()))
            josmBox.isEnabled = hasJosmMap() && !busy
            setupLabel.text = setupState?.let { if (it.ok) "Python: ready" else "Python: ${it.message}" }
                ?: "Python: checking…"
            serverLabel.text = "Scene server: ${server.statusText()}"
            startBtn.isEnabled = !busy
            val root = File(rootField.text.trim())
            // Only a city has an index to rescan.
            rescanBtn.isEnabled = !busy && root.isDirectory && SceneLaunch.detectMode(root) == SceneMode.CITY
            stopBtn.isEnabled = !busy && server.isAlive()
            if (installLog.isEmpty() || server.isAlive()) {
                val text = server.tail().joinToString("\n")
                if (text != log.text) {
                    log.text = text
                    log.caretPosition = log.document.length
                }
            }
        }

        fun checkSetup() = Thread {
            val c = SceneSetup.check(BackendRunner.resolvePython())
            SwingUtilities.invokeLater { setupState = c; refresh() }
        }.apply { isDaemon = true; name = "lanelet2-scene-setup-check" }.start()

        fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

        fun ask(message: String): Boolean {
            var yes = false
            onEdt { yes = Dialogs.confirm(message, TITLE) }
            return yes
        }

        fun fail(message: String) = SwingUtilities.invokeLater { Dialogs.error(message, TITLE) }

        /** Everything Start (and Rescan) does, off the EDT. */
        fun startFlow(root: File, useJosmMap: Boolean, rescan: Boolean) {
            var python = BackendRunner.resolvePython()
            var check = SceneSetup.check(python)
            if (!check.ok) {
                if (!ask(
                        "KITScenes support is not installed yet.\n\nInstall it now? This is a one-time setup " +
                            "(about a minute, ~500 MB, needs internet access).\nIt goes into the Lanelet2 " +
                            "backends' virtualenv, which is created first if there is none.",
                    )
                ) {
                    return
                }
                SwingUtilities.invokeLater { installLog.setLength(0) }
                val result = SceneSetup.install({ appendInstall(it) })
                if (result.error != null) {
                    fail("Setup failed: ${result.error}\n\nThe log in this window says more.")
                    return
                }
                python = result.python ?: python
                check = SceneSetup.check(python)
                SwingUtilities.invokeLater { setupState = check }
                if (!check.ok) {
                    fail(check.message)
                    return
                }
            }
            val port = LaneletSettings.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)
            val foreign = server.foreignServers(port)
            if (foreign.isNotEmpty()) {
                if (!ask(
                        "A scene server that this JOSM did not start is using port $port " +
                            "(pid ${foreign.joinToString { it.pid().toString() }}).\n\nStop it and start this one?",
                    )
                ) {
                    return
                }
                foreign.forEach { it.destroy() }
                Thread.sleep(1000)
                foreign.filter { it.isAlive }.forEach { it.destroyForcibly() }
            }
            val host = Viewer3dSettings.getHost()
            val ingest = Viewer3dSettings.getIngestPort()
            val http = Viewer3dSettings.getHttpPort()
            if (useJosmMap && !Viewer3dSettings.isEnabled()) {
                onEdt {
                    Viewer3dSettings.saveConfig(enabled = true, host = host, ingestPort = ingest)
                    Viewer3dHook.reinstallFromSettings()
                }
            }
            val viewer = Viewer3dHook.serverProcess()
            val viewerWasUp = viewer.isServingUi(host, http)
            val (viewerOk, viewerMsg) = viewer.start(host, ingest, http, openBrowser = !viewerWasUp)
            if (!viewerOk) {
                fail(viewerMsg)
                return
            }
            val extractDir = try {
                SceneServerStore.ensureExtracted()
            } catch (e: Exception) {
                fail("Could not extract the scene server:\n${e.message}")
                return
            }
            SwingUtilities.invokeLater { installLog.setLength(0) }
            val plan = LaunchPlan(
                root = root, mode = SceneLaunch.detectMode(root), useJosmMap = useJosmMap,
                viewerHost = host, viewerPort = http, ingestPort = ingest, httpPort = port,
                rescan = rescan,
            )
            val (ok, msg) = server.start(plan, python, extractDir, BackendRunner.resolveEnvScript())
            if (!ok) fail(msg)
        }

        rootField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        browseBtn.addActionListener {
            val current = File(rootField.text.trim())
            FileChoosers.chooseDirectory("KITScenes folder", current.takeIf { it.isDirectory })
                ?.let { rootField.text = it.absolutePath }
        }
        fun launch(rescan: Boolean) {
            val root = File(rootField.text.trim())
            if (!root.isDirectory) {
                Dialogs.warn("Choose the KITScenes folder first.", TITLE)
                return
            }
            val useJosmMap = josmBox.isSelected && hasJosmMap()
            LaneletSettings.put(KEY_ROOT, root.path)
            LaneletSettings.putBoolean(KEY_USE_JOSM_MAP, josmBox.isSelected)
            busy = true
            refresh()
            Thread {
                try {
                    startFlow(root, useJosmMap, rescan)
                } finally {
                    SwingUtilities.invokeLater { busy = false; refresh() }
                }
            }.apply { isDaemon = true; name = "lanelet2-scene-start" }.start()
        }
        startBtn.addActionListener { launch(rescan = false) }
        rescanBtn.addActionListener { launch(rescan = true) }
        stopBtn.addActionListener {
            server.stop()
            refresh()
        }
        viewerBtn.addActionListener {
            val (ok, url) = Viewer3dHook.serverProcess().openBrowserTab(Viewer3dSettings.getHost(), Viewer3dSettings.getHttpPort())
            if (!ok) Dialogs.info("Open this address in a browser:\n$url", TITLE)
        }
        closeBtn.addActionListener { dlg.dispose() }

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { insets = Insets(2, 4, 2, 4); fill = GridBagConstraints.HORIZONTAL }
        fun row(y: Int, label: String?, comp: java.awt.Component, extra: java.awt.Component? = null) {
            c.gridy = y
            c.gridx = 0; c.weightx = 0.0; c.gridwidth = 1
            if (label != null) form.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0; c.gridwidth = if (extra == null) 2 else 1
            form.add(comp, c)
            if (extra != null) { c.gridx = 2; c.weightx = 0.0; c.gridwidth = 1; form.add(extra, c) }
        }
        row(0, "KITScenes folder:", rootField, browseBtn)
        row(1, null, modeLabel)
        row(2, null, josmBox)
        row(3, null, setupLabel)
        row(4, null, serverLabel)
        form.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)

        val buttons = JPanel(BorderLayout())
        buttons.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(startBtn); add(rescanBtn); add(stopBtn); add(viewerBtn) }, BorderLayout.WEST)
        buttons.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(closeBtn) }, BorderLayout.EAST)

        dlg.add(form, BorderLayout.NORTH)
        dlg.add(JScrollPane(log).apply { border = BorderFactory.createEmptyBorder(0, 8, 0, 8) }, BorderLayout.CENTER)
        dlg.add(buttons, BorderLayout.SOUTH)
        dlg.preferredSize = Dimension(820, 520)
        dlg.pack()
        dlg.setLocationRelativeTo(MainApplication.getMainFrame())

        val timer = Timer(1000) { refresh() }
        dlg.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) {
                timer.stop()
                open = null
            }
        })
        refresh()
        checkSetup()
        timer.start()
        dlg.isVisible = true
    }
}
