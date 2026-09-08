package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.FileChoosers
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.tools.Logging
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
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Graphical "Set up Lanelet2 backends" wizard. Creates a private virtualenv,
 * pip-installs `lanelet2` and `numpy<2`, streams the log into the dialog, and
 * offers an advanced override to point at an existing interpreter.
 *
 * Headless: [open] is a no-op. Tests inject [BackendSetupWizard.showHandler].
 */
object BackendSetupDialog {
    fun open(ui: UserPrompts = Dialogs) {
        if (Dialogs.isHeadless()) {
            Logging.info("lanelet2: setup wizard skipped (headless)")
            return
        }
        val parent = try {
            MainApplication.getMainFrame()
        } catch (_: Exception) {
            null
        }
        val dlg = JDialog(parent, BackendSetupWizard.TITLE, true)
        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dlg.layout = BorderLayout(8, 8)

        val extracted = try {
            BackendStore.ensureExtracted()
        } catch (e: Exception) {
            ui.warn("Could not extract backend scripts:\n${e.message}", BackendSetupWizard.TITLE)
            return
        }

        val intro = JLabel(
            "<html><b>Set up Lanelet2 backends</b><br>" +
                "Creates a private virtualenv and installs <code>lanelet2</code> and <code>numpy&lt;2</code>.<br>" +
                "The upstream wheel is <b>Linux x64</b> and supports " +
                "<b>Python ${SidecarHealth.VERSION_RANGE}</b>.</html>",
        )
        intro.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val status = JLabel(statusText())
        status.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)

        val log = JTextArea(16, 72)
        log.isEditable = false
        log.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        log.lineWrap = true
        log.wrapStyleWord = true

        fun appendLog(line: String) {
            val run = Runnable {
                log.append(line)
                if (!line.endsWith("\n")) log.append("\n")
                log.caretPosition = log.document.length
            }
            if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeLater(run)
        }

        val platform = BackendSetup.platformError()
        if (platform != null) {
            appendLog(platform)
            status.text = "<html><font color='red'>$platform</font></html>"
        }

        val createBtn = JButton("Create virtualenv and install")
        val closeBtn = JButton("Close")
        val pythonField = JTextField(LaneletSettings.getBackendsPython().ifEmpty { BackendRunner.resolvePython() }, 40)
        val useExistingBtn = JButton("Use this interpreter")
        val browseBtn = JButton("Browse…")

        var busy = false
        fun setBusy(value: Boolean) {
            busy = value
            createBtn.isEnabled = !value && platform == null
            useExistingBtn.isEnabled = !value
            browseBtn.isEnabled = !value
        }

        createBtn.addActionListener {
            if (busy) return@addActionListener
            val bootstrap = pythonField.text.trim().ifEmpty { "python3" }
            val check = BackendSetup.checkBootstrapPython(bootstrap)
            if (!check.ok) {
                appendLog(check.message)
                ui.warn(check.message, BackendSetupWizard.TITLE)
                return@addActionListener
            }
            setBusy(true)
            appendLog("Using bootstrap interpreter: ${check.python} (${check.version})")
            val plan = BackendSetup.plan(
                bootstrapPython = check.python,
                venvDir = BackendStore.defaultVenvDir(),
                backendsDir = extracted,
            )
            Thread {
                val err = BackendSetup.install(plan, { appendLog(it) }, BackendSetup::streamProcess)
                SwingUtilities.invokeLater {
                    setBusy(false)
                    status.text = statusText()
                    pythonField.text = LaneletSettings.getBackendsPython().ifEmpty { pythonField.text }
                    if (err == null) {
                        ui.info(
                            "Lanelet2 backends are ready:\n${plan.venvPython.absolutePath}",
                            BackendSetupWizard.TITLE,
                        )
                    } else {
                        ui.warn(err, BackendSetupWizard.TITLE)
                    }
                }
            }.apply { isDaemon = true; name = "lanelet2-backend-setup" }.start()
        }

        browseBtn.addActionListener {
            val picked = FileChoosers.chooseFile(
                "Select Python ${SidecarHealth.VERSION_RANGE} executable",
                File(pythonField.text),
            )
            if (picked != null) pythonField.text = picked.absolutePath
        }

        useExistingBtn.addActionListener {
            val path = pythonField.text.trim()
            if (path.isEmpty()) {
                ui.warn("Please choose a Python executable.", BackendSetupWizard.TITLE)
                return@addActionListener
            }
            val check = BackendSetup.checkInterpreter(path)
            appendLog(check.message)
            when {
                check.ok -> {
                    BackendSetup.persistExistingInterpreter(path, extracted)
                    status.text = statusText()
                    ui.info("Using existing interpreter:\n$path", BackendSetupWizard.TITLE)
                }
                check.problem == SidecarProblem.LANELET2_IMPORT_FAILED &&
                    SidecarHealth.isSupportedVersion(
                        check.version?.substringBefore('.')?.toIntOrNull() ?: -1,
                        check.version?.substringAfter('.')?.toIntOrNull() ?: -1,
                    ) -> {
                    if (ui.confirm(
                            "${check.message}\n\nSave this interpreter anyway? " +
                                "(The four Lanelet2 actions will stay disabled until `import lanelet2` works.)",
                            BackendSetupWizard.TITLE,
                        )
                    ) {
                        BackendSetup.persistExistingInterpreter(path, extracted)
                        status.text = statusText()
                    }
                }
                else -> ui.warn(check.message, BackendSetupWizard.TITLE)
            }
        }

        val advanced = JPanel(GridBagLayout())
        advanced.border = BorderFactory.createTitledBorder("Advanced — use an existing interpreter / venv")
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        advanced.add(JLabel("Python executable:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        advanced.add(pythonField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0
        advanced.add(browseBtn, gbc)

        val advButtons = JPanel(FlowLayout(FlowLayout.LEFT))
        advButtons.add(useExistingBtn)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(createBtn)
        buttons.add(closeBtn)
        closeBtn.addActionListener { dlg.dispose() }

        val north = JPanel(BorderLayout())
        north.add(intro, BorderLayout.NORTH)
        north.add(status, BorderLayout.CENTER)

        val center = JPanel(BorderLayout(0, 8))
        center.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        center.add(advanced, BorderLayout.NORTH)
        center.add(advButtons, BorderLayout.CENTER)
        center.add(JScrollPane(log), BorderLayout.SOUTH)

        dlg.add(north, BorderLayout.NORTH)
        dlg.add(center, BorderLayout.CENTER)
        dlg.add(buttons, BorderLayout.SOUTH)
        dlg.preferredSize = Dimension(760, 560)
        dlg.pack()
        dlg.setLocationRelativeTo(parent)
        dlg.isVisible = true
    }

    private fun statusText(): String {
        val plat = BackendSetup.platformError()
        if (plat != null) return "<html><font color='red'>$plat</font></html>"
        val py = BackendRunner.resolvePython()
        val report = SidecarHealth.probe(py)
        val color = if (report.healthy) "green" else "#b36b00"
        val summary = when (report.problem) {
            SidecarProblem.HEALTHY -> "Healthy — Python ${report.pythonVersion} can import lanelet2."
            SidecarProblem.INTERPRETER_MISSING -> "Interpreter missing: $py"
            SidecarProblem.WRONG_PYTHON_VERSION -> "Wrong Python version: ${report.pythonVersion}"
            SidecarProblem.LANELET2_IMPORT_FAILED ->
                "Python ${report.pythonVersion} is present but `import lanelet2` failed."
            SidecarProblem.BACKENDS_MISSING -> "Backend scripts are not extracted yet."
        }
        return "<html><font color='$color'>$summary</font><br>python=<code>$py</code></html>"
    }
}
