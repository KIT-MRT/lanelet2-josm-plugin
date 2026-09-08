package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.FileChoosers
import org.openstreetmap.josm.plugins.lanelet2.platform.FilePrompts
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendScripts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.Sidecar
import java.io.File

/**
 * Split Merged OSM File. Port of `ll2_split_merged_osm_files_launcher.py`.
 *
 * Inverse of [MergeOsmFiles]. Production merge tags `file_origin` with
 * absolute paths; split resolves those back. Relative paths in the golden
 * corpus are a corpus device only.
 */
object SplitMergedOsmFile {
    const val TITLE = "Split Merged OSM File"

    fun hasFileOriginTag(file: File): Boolean = MergeOsmFiles.hasFileOriginTag(file)

    fun splitArgs(
        mergedFile: String,
        outputDir: String,
        outputMode: String,
        dryRun: Boolean,
    ): List<String> {
        val args = mutableListOf(
            "--merged-file", mergedFile,
            "--output", outputDir,
            "--output-mode", outputMode,
        )
        if (dryRun) args.add("--dry-run")
        return args
    }

    fun runSplit(
        mergedFile: String,
        outputDir: String,
        outputMode: String = "staging",
        dryRun: Boolean = false,
        runner: BackendRunner = Sidecar.runner(),
    ): BackendResult = runner.run(
        BackendScripts.SPLIT_MERGED_OSM_FILE,
        splitArgs(mergedFile, outputDir, outputMode, dryRun),
    )

    fun parseDryRunPaths(logText: String?): List<String> {
        if (logText.isNullOrEmpty()) return emptyList()
        val paths = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in logText.lineSequence()) {
            val line = raw.trim()
            val path = when {
                line.startsWith("SPLIT_TARGET=") -> line.substringAfter("=").trim()
                "Would write " in line -> line.substringAfter("Would write ").substringBefore(" (from ").trim()
                else -> continue
            }
            if (path.isNotEmpty() && path !in seen) {
                seen.add(path)
                paths.add(path)
            }
        }
        return paths
    }

    fun debugExcerpt(logText: String?, maxLines: Int = 12): String {
        if (logText.isNullOrEmpty()) return ""
        val debug = logText.lineSequence().filter { "[split debug]" in it }.toList()
        if (debug.isEmpty()) {
            val t = logText.trim()
            return if (t.length <= 800) t else t.takeLast(800)
        }
        return debug.takeLast(maxLines).joinToString("\n")
    }

    fun listOsmFiles(directory: File): List<File> =
        (directory.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".osm") } ?: emptyArray())
            .sortedBy { it.name }

    fun run(
        ui: UserPrompts = Dialogs,
        files: FilePrompts = FileChoosers,
    ) {
        if (!Sidecar.ensureUsable(TITLE, ui)) return
        val merged = files.chooseFile("Select a merged .osm file to split", defaultBrowseDir()) ?: return
        LaneletSettings.put(LaneletSettings.KEY_MERGE_LAST_OUTPUT_DIR, merged.absoluteFile.parent)
        if (!hasFileOriginTag(merged)) {
            if (!ui.confirmWarn(
                    "This file does not contain any 'file_origin' tags.\n\n" +
                        "It was probably not produced by \"Merge OSM Files\" (or has " +
                        "nothing left to split). Continue anyway?",
                    "$TITLE - Warning",
                )
            ) return
        }
        val outputMode = when (
            ui.option(
                "$TITLE - Output",
                "Where should reconstructed files be written?\n\n" +
                    "  Staging: a directory you choose (safe, default)\n" +
                    "  Overwrite originals: write back to each file's file_origin path\n\n" +
                    "Overwrite mode refuses dirty git repos; non-git targets are backed up first.",
                listOf("Split to a directory", "Overwrite original source files", "Cancel"),
            )
        ) {
            "Split to a directory" -> "staging"
            "Overwrite original source files" -> "in-place"
            else -> return
        }

        var inplaceTargets = emptyList<String>()
        val outputDir: File
        if (outputMode == "in-place") {
            outputDir = defaultSplitOutputDir()
            val dry = runSplit(merged.absolutePath, outputDir.absolutePath, "in-place", dryRun = true)
            if (!dry.success) {
                ui.error("Cannot preview in-place split:\n${dry.error ?: "Unknown error"}", TITLE)
                return
            }
            inplaceTargets = parseDryRunPaths(dry.stdout + "\n" + dry.stderr)
            if (inplaceTargets.isEmpty()) {
                ui.warn(
                    "No target paths found for in-place split.\n\n" +
                        "Check that the merged file has file_origin tags (from Merge OSM Files, " +
                        "not a plain JOSM save).\n\n" +
                        "Split script log excerpt:\n${debugExcerpt(dry.stdout + "\n" + dry.stderr)}",
                    TITLE,
                )
                return
            }
            val preview = inplaceTargets.take(20).joinToString("\n") { "  - $it" } +
                if (inplaceTargets.size > 20) "\n  ... and ${inplaceTargets.size - 20} more" else ""
            if (!ui.confirmWarn(
                    "Overwrite these original source files?\n\n$preview\n\n" +
                        "This cannot be undone except via git or backups.",
                    "Confirm overwrite originals",
                )
            ) return
        } else {
            outputDir = files.chooseDirectory(
                "Select output directory for the reconstructed files",
                defaultSplitOutputDir(),
            ) ?: return
            LaneletSettings.put(LaneletSettings.KEY_SPLIT_LAST_OUTPUT_DIR, outputDir.absolutePath)
        }

        val mergedAbs = merged.absolutePath
        val outAbs = outputDir.absolutePath
        Thread {
            val result = runSplit(mergedAbs, outAbs, outputMode, dryRun = false)
            javax.swing.SwingUtilities.invokeLater {
                onDone(ui, outputMode, outAbs, inplaceTargets, result)
            }
        }.apply { isDaemon = true; name = "lanelet2-split" }.start()
    }

    internal fun onDone(
        ui: UserPrompts,
        outputMode: String,
        outputDir: String,
        inplaceTargets: List<String>,
        result: BackendResult,
    ) {
        if (!result.success) {
            ui.error("Split failed:\n${result.error ?: "Unknown error"}", TITLE)
            return
        }
        if (outputMode == "in-place") {
            val preview = inplaceTargets.take(15).joinToString("\n") { "  - $it" } +
                if (inplaceTargets.size > 15) "\n  ... and ${inplaceTargets.size - 15} more" else ""
            ui.info(
                "Split complete. Overwrote ${inplaceTargets.size} original file(s):\n\n$preview",
                "$TITLE - Done",
            )
            return
        }
        val reconstructed = listOsmFiles(File(outputDir))
        if (reconstructed.isEmpty()) {
            ui.warn("Split completed but no *.osm files found in:\n$outputDir", TITLE)
            return
        }
        val preview = reconstructed.take(10).joinToString("\n") { "  - ${it.name}" } +
            if (reconstructed.size > 10) "\n  ... and ${reconstructed.size - 10} more" else ""
        ui.info(
            "Reconstructed ${reconstructed.size} file(s) in:\n$outputDir\n\n$preview\n\n" +
                "Select a file to load into a new layer.",
            "$TITLE - Done",
        )
        val names = reconstructed.map { it.name }
        val picked = ui.pick("$TITLE - Load", "Select a reconstructed file to load into a new layer:", names)
            ?: return
        val idx = names.indexOf(picked)
        if (idx < 0) return
        val toLoad = reconstructed[idx]
        OsmIo.addLayer(toLoad, toLoad.name)
        ui.infoAutoClose("Loaded: ${toLoad.name}", TITLE, 2000)
    }

    private fun defaultBrowseDir(): File = File(
        LaneletSettings.get(LaneletSettings.KEY_MERGE_LAST_OUTPUT_DIR, home().absolutePath),
    )

    private fun defaultSplitOutputDir(): File = File(
        LaneletSettings.get(LaneletSettings.KEY_SPLIT_LAST_OUTPUT_DIR, home().absolutePath),
    )

    private fun home(): File = File(System.getProperty("user.home"))
}
