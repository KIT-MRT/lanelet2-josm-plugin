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
 * Merge OSM Files. Port of `ll2_merge_input_osm_files_launcher.py`.
 *
 * Production passes **absolute** paths (`File.absolutePath` / Jython
 * `getAbsolutePath()`). Relative paths in the golden corpus are a corpus
 * device only. `file_origin` on the merged map therefore stores absolute
 * paths, which split later resolves back.
 *
 * [_hasFileOriginTag] returns true when the file is unreadable so the backend
 * can report the real error — Jython fail-open.
 */
object MergeOsmFiles {
    const val TITLE = "Merge OSM Files"
    private val FILE_ORIGIN_RE = Regex("""k=["']file_origin["']""")

    fun hasFileOriginTag(file: File): Boolean {
        return try {
            file.bufferedReader().useLines { lines -> lines.any { FILE_ORIGIN_RE.containsMatchIn(it) } }
        } catch (_: Exception) {
            true
        }
    }

    fun listOsmFilesInDir(directory: File): List<File> =
        (directory.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".osm") } ?: emptyArray())
            .sortedBy { it.name }

    fun excludeBase(paths: List<File>, base: File?): List<File> {
        if (base == null) return paths
        val baseAbs = base.canonicalFile
        return paths.filter { it.canonicalFile != baseAbs }
    }

    /**
     * Default merged filename: `'<directory name>.osm'`. Append mode reuses
     * the base file's name. Hand-picked files sharing one parent use that
     * parent's basename; otherwise `merged.osm`.
     */
    fun defaultOutputBasename(inputDir: File?, files: List<File>, base: File?): String {
        if (base != null) {
            val name = base.name
            return name.ifEmpty { "merged.osm" }
        }
        if (inputDir != null) {
            val name = inputDir.normalize().name
            return (name.ifEmpty { "merged" }) + ".osm"
        }
        val parents = files.map { it.absoluteFile.parent }.toSet()
        if (parents.size == 1) {
            val name = File(parents.first()).name
            return (name.ifEmpty { "merged" }) + ".osm"
        }
        return "merged.osm"
    }

    /**
     * Argv for `merge_osm_files.py`. Paths must already be absolute.
     */
    fun mergeArgs(outputPath: String, inputDir: String?, files: List<String>?, base: String?): List<String> {
        val args = mutableListOf("--output", outputPath)
        if (base != null) {
            args.add("--base")
            args.add(base)
        }
        if (inputDir != null) {
            args.add("--input-dir")
            args.add(inputDir)
        } else {
            args.add("--files")
            args.addAll(files ?: emptyList())
        }
        return args
    }

    fun runMerge(
        outputPath: String,
        inputDir: String? = null,
        files: List<String>? = null,
        base: String? = null,
        runner: BackendRunner = Sidecar.runner(),
    ): BackendResult = runner.run(
        BackendScripts.MERGE_OSM_FILES,
        mergeArgs(outputPath, inputDir, files, base),
    )

    fun run(
        ui: UserPrompts = Dialogs,
        files: FilePrompts = FileChoosers,
    ) {
        if (!Sidecar.ensureUsable(TITLE, ui)) return
        val mode = ui.option(
            "$TITLE - Source",
            "What should be merged?\n\n" +
                "  Directory: every *.osm file found directly in a chosen directory\n" +
                "  Files: individually hand-picked .osm files (any naming)\n" +
                "  Append: add files to an already-merged map without changing its tags",
            listOf("Choose directory", "Choose files", "Append to merged map", "Cancel"),
        ) ?: return
        if (mode == "Cancel") return

        var inputDir: File? = null
        var fileList: List<File>? = null
        var base: File? = null

        when (mode) {
            "Choose directory" -> {
                inputDir = files.chooseDirectory(
                    "Select directory of .osm files to merge",
                    lastInputDir(),
                ) ?: return
                if (listOsmFilesInDir(inputDir).isEmpty()) {
                    ui.warn("No .osm files found directly in:\n${inputDir.absolutePath}", TITLE)
                    return
                }
                LaneletSettings.put(LaneletSettings.KEY_MERGE_LAST_INPUT_DIR, inputDir.absolutePath)
            }
            "Choose files" -> {
                fileList = files.chooseFiles(
                    "Select .osm files to merge",
                    lastInputDir(),
                ) ?: return
                if (fileList.isEmpty()) return
                LaneletSettings.put(
                    LaneletSettings.KEY_MERGE_LAST_INPUT_DIR,
                    fileList.first().parent ?: homeDir().absolutePath,
                )
            }
            "Append to merged map" -> {
                base = files.chooseFile(
                    "Select the already-merged .osm file to append to",
                    lastOutputDir(),
                ) ?: return
                if (!hasFileOriginTag(base)) {
                    if (!ui.confirmWarn(
                            "This file does not contain any 'file_origin' tags.\n\n" +
                                "It was probably not produced by \"Merge OSM Files\". " +
                                "Appending will keep it untagged and only tag the added files.\n\n" +
                                "Continue anyway?",
                            "$TITLE - Warning",
                        )
                    ) return
                }
                val appendMode = ui.option(
                    "$TITLE - Append",
                    "What should be appended onto the merged map?\n\n" +
                        "  Directory: every *.osm file found directly in a chosen directory\n" +
                        "  Files: individually hand-picked .osm files (any naming)\n\n" +
                        "Only the appended files get new file_origin / original_id tags.",
                    listOf("Choose directory", "Choose files", "Cancel"),
                ) ?: return
                if (appendMode == "Cancel") return
                if (appendMode == "Choose directory") {
                    inputDir = files.chooseDirectory(
                        "Select directory of .osm files to append",
                        lastInputDir(),
                    ) ?: return
                    val found = excludeBase(listOsmFilesInDir(inputDir), base)
                    if (found.isEmpty()) {
                        ui.warn(
                            "No .osm files to append in:\n${inputDir.absolutePath}\n\n" +
                                "(the already-merged base file is ignored if it lives there)",
                            TITLE,
                        )
                        return
                    }
                    LaneletSettings.put(LaneletSettings.KEY_MERGE_LAST_INPUT_DIR, inputDir.absolutePath)
                } else {
                    val picked = files.chooseFiles(
                        "Select .osm files to append",
                        lastInputDir(),
                    ) ?: return
                    fileList = excludeBase(picked, base)
                    if (fileList.isEmpty()) {
                        ui.warn("No files left to append after excluding the base map.", TITLE)
                        return
                    }
                    LaneletSettings.put(
                        LaneletSettings.KEY_MERGE_LAST_INPUT_DIR,
                        fileList.first().parent ?: homeDir().absolutePath,
                    )
                }
                LaneletSettings.put(LaneletSettings.KEY_MERGE_LAST_OUTPUT_DIR, base.absoluteFile.parent)
            }
            else -> return
        }

        val defaultName = defaultOutputBasename(inputDir, fileList ?: emptyList(), base)
        val initialDir = if (base != null) {
            File(base.absoluteFile.parent ?: ".")
        } else {
            lastOutputDir()
        }
        val output = files.chooseSaveFile("Save merged OSM file as...", initialDir, defaultName) ?: return
        if (output.isFile) {
            if (!ui.confirmWarn(
                    "File already exists:\n${output.absolutePath}\n\nOverwrite?",
                    TITLE,
                )
            ) return
        }
        LaneletSettings.put(LaneletSettings.KEY_MERGE_LAST_OUTPUT_DIR, output.absoluteFile.parent)

        val outAbs = output.absolutePath
        val dirAbs = inputDir?.absolutePath
        val filesAbs = fileList?.map { it.absolutePath }
        val baseAbs = base?.absolutePath
        Thread {
            val result = runMerge(outAbs, dirAbs, filesAbs, baseAbs)
            javax.swing.SwingUtilities.invokeLater { onDone(ui, outAbs, result) }
        }.apply { isDaemon = true; name = "lanelet2-merge" }.start()
    }

    internal fun onDone(ui: UserPrompts, outputPath: String, result: BackendResult) {
        if (!result.success) {
            ui.error("Merge failed:\n${result.error ?: "Unknown error"}", TITLE)
            return
        }
        if (!File(outputPath).isFile) {
            ui.warn("Merge completed but output file not found:\n$outputPath", TITLE)
            return
        }
        if (!ui.confirm("Merged successfully:\n$outputPath\n\nLoad it into a new layer?", "$TITLE - Done")) {
            return
        }
        OsmIo.tryEnableWireframe()
        val layer = OsmIo.addLayer(File(outputPath), File(outputPath).name)
        if (layer != null) {
            ui.infoAutoClose("Loaded: ${File(outputPath).name}", TITLE, 2000)
        }
    }

    private fun lastInputDir(): File = File(
        LaneletSettings.get(LaneletSettings.KEY_MERGE_LAST_INPUT_DIR, homeDir().absolutePath),
    )

    private fun lastOutputDir(): File = File(
        LaneletSettings.get(LaneletSettings.KEY_MERGE_LAST_OUTPUT_DIR, homeDir().absolutePath),
    )

    private fun homeDir(): File = File(System.getProperty("user.home"))
}
