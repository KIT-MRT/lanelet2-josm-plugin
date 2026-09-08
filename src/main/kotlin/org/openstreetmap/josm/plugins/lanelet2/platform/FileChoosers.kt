package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.tools.Logging
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser

/**
 * File / directory pickers. Headless (tests, CI): always return null so an
 * accidental GUI path cannot mutate data.
 */
interface FilePrompts {
    fun chooseDirectory(title: String, initial: File?): File?
    fun chooseFile(title: String, initial: File?): File?
    fun chooseFiles(title: String, initial: File?): List<File>?
    fun chooseSaveFile(title: String, initialDir: File, initialName: String): File?
}

object FileChoosers : FilePrompts {
    fun parent(): Component? = try {
        MainApplication.getMainFrame()
    } catch (_: Exception) {
        null
    }

    fun isHeadless(): Boolean = GraphicsEnvironment.isHeadless()

    override fun chooseDirectory(title: String, initial: File?): File? =
        choose(title, initial, JFileChooser.DIRECTORIES_ONLY, multi = false)?.firstOrNull()

    override fun chooseFile(title: String, initial: File?): File? =
        choose(title, initial, JFileChooser.FILES_ONLY, multi = false)?.firstOrNull()

    override fun chooseFiles(title: String, initial: File?): List<File>? =
        choose(title, initial, JFileChooser.FILES_ONLY, multi = true)

    override fun chooseSaveFile(title: String, initialDir: File, initialName: String): File? {
        if (isHeadless()) return null
        val fc = JFileChooser(initialDir)
        fc.fileSelectionMode = JFileChooser.FILES_ONLY
        fc.dialogTitle = title
        fc.isFileHidingEnabled = false
        fc.selectedFile = File(initialDir, initialName)
        if (fc.showSaveDialog(parent()) != JFileChooser.APPROVE_OPTION) return null
        var path = fc.selectedFile ?: return null
        if (!path.name.lowercase().endsWith(".osm")) {
            path = File(path.path + ".osm")
        }
        return path
    }

    private fun choose(title: String, initial: File?, mode: Int, multi: Boolean): List<File>? {
        if (isHeadless()) {
            Logging.info("lanelet2: file chooser skipped (headless): {0}", title)
            return null
        }
        val fc = JFileChooser()
        fc.dialogTitle = title
        fc.fileSelectionMode = mode
        fc.isMultiSelectionEnabled = multi
        fc.isFileHidingEnabled = false
        if (initial != null) {
            if (initial.isFile) {
                fc.selectedFile = initial
                initial.parentFile?.let { if (it.isDirectory) fc.currentDirectory = it }
            } else if (initial.isDirectory) {
                fc.currentDirectory = initial
            } else {
                val parent = initial.parentFile
                if (parent != null && parent.isDirectory) fc.currentDirectory = parent
            }
        }
        if (fc.showOpenDialog(parent()) != JFileChooser.APPROVE_OPTION) return null
        return if (multi) {
            val files = fc.selectedFiles
            if (files.isNullOrEmpty()) null else files.toList()
        } else {
            listOfNotNull(fc.selectedFile)
        }
    }
}
