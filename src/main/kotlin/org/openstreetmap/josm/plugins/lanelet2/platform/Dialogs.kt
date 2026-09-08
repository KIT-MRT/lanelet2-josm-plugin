package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.tools.Logging
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GraphicsEnvironment
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.Timer

/**
 * Minimal Swing prompts used by edit actions. `lanelet2_dialogs.py` / the
 * collection dialog are not ported; this covers message, confirm, and the
 * auto-close toast the Jython used via `show_message_auto_close`.
 *
 * Headless (tests, CI): logs instead of opening a window; [confirm] returns
 * false so an accidental GUI path cannot mutate data.
 */
interface UserPrompts {
    fun warn(message: String, title: String)
    fun info(message: String, title: String)
    fun infoAutoClose(message: String, title: String, delayMs: Int)
    fun confirm(message: String, title: String): Boolean
    fun pick(title: String, message: String, options: List<String>): String?
    fun ask(title: String, message: String): String?

    /** Error-severity dialog. Defaults to [warn] so existing test fakes keep compiling. */
    fun error(message: String, title: String) = warn(message, title)

    /**
     * Confirm that uses a warning icon (overwrite / destructive). Defaults to [confirm].
     */
    fun confirmWarn(message: String, title: String): Boolean = confirm(message, title)

    /**
     * Button-list choice like `JOptionPane.showOptionDialog`. Returns the selected
     * option string, or null if cancelled / headless.
     */
    fun option(title: String, message: String, options: List<String>): String? =
        pick(title, message, options)

    /**
     * Commit-style dialog: HTML header, wrapped text area, checkbox.
     * Headless / cancel → null. Default no-op so existing test fakes compile.
     */
    fun textAndCheckbox(
        title: String,
        headerHtml: String,
        textLabel: String,
        defaultText: String,
        checkboxLabel: String,
        checkboxSelected: Boolean,
    ): TextAndCheckboxResult? = null
}

data class TextAndCheckboxResult(
    val text: String,
    val checked: Boolean,
)

object Dialogs : UserPrompts {
    fun parent(): Component? = try {
        MainApplication.getMainFrame()
    } catch (_: Exception) {
        null
    }

    fun isHeadless(): Boolean = GraphicsEnvironment.isHeadless()

    override fun warn(message: String, title: String) {
        show(message, title, JOptionPane.WARNING_MESSAGE)
    }

    override fun info(message: String, title: String) {
        show(message, title, JOptionPane.INFORMATION_MESSAGE)
    }

    override fun infoAutoClose(message: String, title: String, delayMs: Int) {
        if (isHeadless()) {
            Logging.info("lanelet2: {0}: {1}", title, message)
            return
        }
        val pane = JOptionPane(message, JOptionPane.INFORMATION_MESSAGE)
        val dialog = pane.createDialog(parent(), title)
        dialog.isModal = false
        val timer = Timer(delayMs) {
            try {
                dialog.dispose()
            } catch (_: Exception) {
            }
        }
        timer.isRepeats = false
        timer.start()
        dialog.isVisible = true
    }

    override fun confirm(message: String, title: String): Boolean =
        confirmDialog(message, title, JOptionPane.QUESTION_MESSAGE)

    override fun confirmWarn(message: String, title: String): Boolean =
        confirmDialog(message, title, JOptionPane.WARNING_MESSAGE)

    override fun error(message: String, title: String) {
        show(message, title, JOptionPane.ERROR_MESSAGE)
    }

    override fun option(title: String, message: String, options: List<String>): String? {
        if (isHeadless() || options.isEmpty()) return null
        val choice = JOptionPane.showOptionDialog(
            parent(),
            message,
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options.toTypedArray(),
            options.first(),
        )
        if (choice < 0 || choice >= options.size) return null
        return options[choice]
    }

    override fun pick(title: String, message: String, options: List<String>): String? {
        if (isHeadless() || options.isEmpty()) return null
        val result = JOptionPane.showInputDialog(
            parent(),
            message,
            title,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options.toTypedArray(),
            options.first(),
        )
        return result as? String
    }

    override fun ask(title: String, message: String): String? {
        if (isHeadless()) return null
        return JOptionPane.showInputDialog(parent(), message, title, JOptionPane.QUESTION_MESSAGE)
    }

    override fun textAndCheckbox(
        title: String,
        headerHtml: String,
        textLabel: String,
        defaultText: String,
        checkboxLabel: String,
        checkboxSelected: Boolean,
    ): TextAndCheckboxResult? {
        if (isHeadless()) return null
        val msgLabel = JLabel(textLabel)
        val area = JTextArea(defaultText, 4, 50)
        area.lineWrap = true
        area.wrapStyleWord = true
        val scroll = JScrollPane(area)
        val checkbox = JCheckBox(checkboxLabel, checkboxSelected)
        val content = JPanel(BorderLayout())
        content.add(msgLabel, BorderLayout.NORTH)
        content.add(scroll, BorderLayout.CENTER)
        content.add(checkbox, BorderLayout.SOUTH)
        val statusLabel = JLabel(headerHtml)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(statusLabel, BorderLayout.NORTH)
        wrapper.add(content, BorderLayout.CENTER)
        val result = JOptionPane.showConfirmDialog(
            parent(),
            wrapper,
            title,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return null
        return TextAndCheckboxResult(area.text ?: "", checkbox.isSelected)
    }

    private fun confirmDialog(message: String, title: String, type: Int): Boolean {
        if (isHeadless()) {
            Logging.info("lanelet2: confirm skipped (headless): {0}: {1}", title, message)
            return false
        }
        return JOptionPane.showConfirmDialog(
            parent(),
            message,
            title,
            JOptionPane.YES_NO_OPTION,
            type,
        ) == JOptionPane.YES_OPTION
    }

    private fun show(message: String, title: String, type: Int) {
        if (isHeadless()) {
            Logging.info("lanelet2: {0}: {1}", title, message)
            return
        }
        JOptionPane.showMessageDialog(parent(), message, title, type)
    }
}
