package org.openstreetmap.josm.plugins.lanelet2.tools

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.dependent.OsmIo
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.internal.GitHelpers
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import java.io.File
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Load an older git revision of the active layer's file.
 *
 * Port of `core/josm_tools/git_history_loader.py`. Git plumbing is
 * [GitHelpers] — this file does not reimplement `run_git` / `find_git_repo`.
 *
 * Replace associates the **original** file, so a later save overwrites it.
 * Load-into-new-layer keeps the `/tmp` extract (Jython leaves it).
 */
object GitHistoryLoader {
    const val TITLE = "Git History Loader"
    const val DIALOG_TITLE = "Git History - Load Older Version"
    const val DEFAULT_COMMIT_LIMIT = 20

    data class Commit(
        val hash: String,
        val message: String,
        val date: String,
        val time: String,
        val committer: String,
    )

    data class Counts(val nodes: Int, val ways: Int, val relations: Int)

    fun parseCommits(stdout: String): List<Commit> {
        val commits = ArrayList<Commit>()
        for (raw in stdout.trim().split("\n")) {
            val line = raw
            if (line.isEmpty()) continue
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) continue
            val h = parts[0]
            val msg = parts[1]
            val dateIso = parts[2]
            val author = parts[3]
            // Jython `date_iso.split()` (no args): whitespace, drop empty tokens.
            val tokens = dateIso.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val datePart = tokens.getOrNull(0).orEmpty()
            val timePart = tokens.getOrNull(1)?.take(5).orEmpty()
            commits.add(Commit(h, msg, datePart, timePart, author))
        }
        return commits
    }

    fun getCommits(repoDir: File, filePath: File, limit: Int = DEFAULT_COMMIT_LIMIT): List<Commit> {
        val relPath = GitHelpers.relativize(repoDir, filePath)
        val r = GitHelpers.runGit(
            repoDir,
            listOf("log", "-n", limit.toString(), "--follow", "--format=%H|%s|%ci|%an", "--", relPath),
        )
        if (!r.success) return emptyList()
        return parseCommits(r.stdout)
    }

    fun extractFileAtCommit(repoDir: File, filePath: File, commitHash: String): File? {
        val relPath = GitHelpers.relativize(repoDir, filePath)
        val r = GitHelpers.runGit(repoDir, listOf("show", "$commitHash:$relPath"))
        if (!r.success) return null
        val base = filePath.name.substringBeforeLast('.')
        val prefix = "${base}_${commitHash.take(8)}_"
        val path = File.createTempFile(prefix, ".osm", File("/tmp"))
        path.writeBytes(r.stdout.toByteArray(Charsets.UTF_8))
        return path
    }

    fun countElements(file: File): Counts? {
        val ds = OsmIo.parse(file) ?: return null
        return countDataset(ds)
    }

    fun countDataset(ds: DataSet?): Counts {
        if (ds == null) return Counts(0, 0, 0)
        val nodes = ds.nodes
        val ways = ds.ways
        val relations = ds.relations
        return Counts(
            nodes = nodes?.size ?: 0,
            ways = ways?.size ?: 0,
            relations = relations?.size ?: 0,
        )
    }

    fun countLayerElements(layer: OsmDataLayer?): Counts {
        if (layer == null) return Counts(0, 0, 0)
        return try {
            countDataset(layer.data)
        } catch (_: Exception) {
            Counts(0, 0, 0)
        }
    }

    fun deltaMessage(old: Counts, current: Counts): String {
        val dn = old.nodes - current.nodes
        val dw = old.ways - current.ways
        val dr = old.relations - current.relations
        return "Delta vs current layer:\n" +
            "  Nodes: ${signed(dn)}  Ways: ${signed(dw)}  Relations: ${signed(dr)}\n\n" +
            "Load this version into a new layer?"
    }

    fun layerNameFor(filePath: File, commit: Commit): String =
        "${filePath.name} @ ${commit.hash.take(8)} (${commit.date})"

    fun run(ui: UserPrompts = Dialogs) {
        val parent = Dialogs.parent()
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val assoc = try {
            layer.associatedFile
        } catch (_: Exception) {
            null
        }
        if (assoc == null || !assoc.exists()) {
            ui.warn("The active layer has no associated file.", TITLE)
            return
        }
        val filePath = assoc.absoluteFile
        val repoDir = GitHelpers.findGitRepo(filePath)
        if (repoDir == null) {
            ui.warn("The file is not in a git repository.", TITLE)
            return
        }
        val commits = getCommits(repoDir, filePath)
        if (commits.isEmpty()) {
            ui.info("No commits found that modified this file.", TITLE)
            return
        }
        if (Dialogs.isHeadless()) {
            ui.info("${commits.size} commit(s) available for ${filePath.name}.", TITLE)
            return
        }
        showDialog(parent as? Frame, layer, filePath, repoDir, commits, countLayerElements(layer), ui)
    }

    internal fun addLayerFromFile(file: File, layerName: String): OsmDataLayer? = OsmIo.addLayer(file, layerName)

    internal fun replaceActiveLayer(
        old: OsmDataLayer,
        newDs: DataSet,
        filePath: File,
    ): OsmDataLayer? {
        return try {
            val lm = MainApplication.getLayerManager()
            val layerName = old.name?.ifEmpty { filePath.name } ?: filePath.name
            val newLayer = OsmDataLayer(newDs, layerName, filePath)
            lm.removeLayer(old)
            lm.addLayer(newLayer)
            lm.activeLayer = newLayer
            try {
                newLayer.invalidate()
            } catch (_: Exception) {
            }
            try {
                MainApplication.getMap()?.mapView?.repaint()
            } catch (_: Exception) {
            }
            newLayer
        } catch (_: Exception) {
            null
        }
    }

    private fun signed(n: Int): String = if (n >= 0) "+$n" else n.toString()

    private fun showDialog(
        parent: Frame?,
        layer: OsmDataLayer,
        filePath: File,
        repoDir: File,
        commits: List<Commit>,
        currentCounts: Counts,
        ui: UserPrompts,
    ) {
        val dlg = JDialog(parent, DIALOG_TITLE, false)
        dlg.layout = BorderLayout()
        val columns = arrayOf("Message", "Date", "Time", "Committer")
        val data = commits.map { arrayOf<Any>(it.message, it.date, it.time, it.committer) }.toTypedArray()
        val model = DefaultTableModel(data, columns)
        val table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.reorderingAllowed = false
        dlg.add(JScrollPane(table), BorderLayout.CENTER)
        dlg.add(
            JLabel("Select a commit and click Load to open it in a new layer for comparison."),
            BorderLayout.NORTH,
        )

        fun selectedCommit(): Commit? {
            val row = table.selectedRow
            if (row < 0) {
                ui.warn("Select a commit row first.", TITLE)
                return null
            }
            return commits[row]
        }

        val btnLoad = JButton("Load into new layer")
        btnLoad.addActionListener {
            val commit = selectedCommit() ?: return@addActionListener
            val tempPath = extractFileAtCommit(repoDir, filePath, commit.hash)
            if (tempPath == null || !tempPath.isFile) {
                ui.error("Failed to extract file from commit.", TITLE)
                return@addActionListener
            }
            val oldCounts = countElements(tempPath)
            val deltaMsg = if (oldCounts != null) {
                deltaMessage(oldCounts, currentCounts)
            } else {
                "Load this version into a new layer?"
            }
            if (!ui.confirm(deltaMsg, "Load Commit")) {
                try {
                    tempPath.delete()
                } catch (_: Exception) {
                }
                return@addActionListener
            }
            val name = layerNameFor(filePath, commit)
            val newLayer = addLayerFromFile(tempPath, name)
            if (newLayer != null) {
                ui.infoAutoClose("Loaded commit into new layer: $name", TITLE, 2000)
                dlg.isVisible = false
                dlg.dispose()
            } else {
                ui.error("Failed to load file into layer.", TITLE)
            }
        }

        val btnReplace = JButton("Replace active layer with this version")
        btnReplace.addActionListener {
            val commit = selectedCommit() ?: return@addActionListener
            val warnMsg =
                "This will replace the content of the current layer with the selected commit version.\n\n" +
                    "If you save or if tools auto-save, you may overwrite your current file and lose recent changes.\n\n" +
                    "Continue?"
            if (!ui.confirmWarn(warnMsg, "Replace active layer - data loss risk")) {
                return@addActionListener
            }
            val tempPath = extractFileAtCommit(repoDir, filePath, commit.hash)
            if (tempPath == null || !tempPath.isFile) {
                ui.error("Failed to extract file from commit.", TITLE)
                return@addActionListener
            }
            val newDs = OsmIo.parse(tempPath)
            if (newDs == null) {
                ui.error("Failed to load file from commit.", TITLE)
                try {
                    tempPath.delete()
                } catch (_: Exception) {
                }
                return@addActionListener
            }
            val replaced = replaceActiveLayer(layer, newDs, filePath)
            if (replaced != null) {
                ui.infoAutoClose(
                    "Active layer replaced with commit ${commit.hash.take(8)}. Save will overwrite the file.",
                    TITLE,
                    3000,
                )
                dlg.isVisible = false
                dlg.dispose()
            } else {
                ui.error("Failed to replace layer: replace failed", TITLE)
            }
            try {
                tempPath.delete()
            } catch (_: Exception) {
            }
        }

        val btnClose = JButton("Close")
        btnClose.addActionListener {
            dlg.isVisible = false
            dlg.dispose()
        }
        val btnPanel = JPanel(FlowLayout())
        btnPanel.add(btnLoad)
        btnPanel.add(btnReplace)
        btnPanel.add(btnClose)
        dlg.add(btnPanel, BorderLayout.SOUTH)
        dlg.pack()
        dlg.setSize(700, 400)
        positionToolsDialogUpperLeft(dlg, parent)
        dlg.isVisible = true
    }
}
