package org.openstreetmap.josm.plugins.lanelet2.scripting

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.FileChoosers
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.tools.Logging
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Ships `examples/jython/hello_lanelet2.py` as a jar resource and offers a
 * menu action that copies it to a user-chosen path.
 */
object ExampleScript {
    const val RESOURCE = "lanelet2/examples/hello_lanelet2.py"
    const val FILENAME = "hello_lanelet2.py"
    const val SLOT_ID = "scripting.copy_example_script"
    const val TITLE = "Copy example script to..."

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        val action = object : LaneletAction(TITLE, null, TITLE, null) {
            override fun actionPerformed(e: ActionEvent) = copyInteractively()
        }
        registry.register(
            ActionSlot(
                id = SLOT_ID,
                action = action,
                toolbarLabel = "Copy Ex",
                iconName = null,
                menu = MenuId.UTILS,
            ),
        )
    }

    /**
     * Read the shipped example. Used by tests and by [copyTo].
     */
    fun readText(): String {
        val stream = javaClass.classLoader.getResourceAsStream(RESOURCE)
            ?: javaClass.getResourceAsStream("/$RESOURCE")
            ?: error("missing jar resource $RESOURCE")
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * Write the example to [destination]. If [destination] is a directory the
     * file is named [FILENAME] inside it. Returns the file that was written.
     */
    fun copyTo(destination: File): File {
        val destFile = if (destination.isDirectory) File(destination, FILENAME) else destination
        destFile.parentFile?.mkdirs()
        destFile.writeText(readText())
        return destFile
    }

    private fun copyInteractively() {
        if (FileChoosers.isHeadless()) {
            Logging.info("lanelet2: copy example script skipped (headless)")
            return
        }
        val dest = chooseDestination() ?: return
        if (dest.exists() && !Dialogs.confirm(
                "Overwrite existing file?\n${dest.absolutePath}",
                TITLE,
            )
        ) {
            return
        }
        try {
            val written = copyTo(dest)
            Dialogs.info("Wrote ${written.absolutePath}", TITLE)
        } catch (e: Exception) {
            Logging.error(e)
            Dialogs.error("Could not write the example script:\n${e.message}", TITLE)
        }
    }

    /**
     * Dedicated save chooser: [FileChoosers.chooseSaveFile] always appends
     * `.osm`, which is wrong for a `.py` example.
     */
    private fun chooseDestination(): File? {
        val fc = JFileChooser()
        fc.dialogTitle = TITLE
        fc.fileSelectionMode = JFileChooser.FILES_ONLY
        fc.isFileHidingEnabled = false
        fc.selectedFile = File(System.getProperty("user.home"), FILENAME)
        fc.fileFilter = FileNameExtensionFilter("Jython / Python (*.py)", "py")
        if (fc.showSaveDialog(FileChoosers.parent()) != JFileChooser.APPROVE_OPTION) {
            return null
        }
        var path = fc.selectedFile ?: return null
        if (!path.name.lowercase().endsWith(".py")) {
            path = File(path.path + ".py")
        }
        return path
    }
}
