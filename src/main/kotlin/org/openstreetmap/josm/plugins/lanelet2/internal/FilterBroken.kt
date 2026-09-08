package org.openstreetmap.josm.plugins.lanelet2.internal

import org.openstreetmap.josm.actions.SaveAction
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.dependent.OsmIo
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.util.Locale
import javax.swing.SwingUtilities

/**
 * Filter Broken Lanelets and Regulatory Elements.
 *
 * Port of `internal/ll2_filter_broken_lanelets_regElements.py`. Shells out via
 * `bash -c "source ll2_env.sh && <binary> <input> <output> [--origin lat,lon]"`
 * to the Conan-built `load_filtered_osm`.
 *
 * **Approved divergence:** the Jython overwrote the layer `.osm` in place with
 * no confirmation, no backup, and no undo (the reload swaps the `OsmDataLayer`
 * instead of issuing a Command). We ask first and write a
 * [JosmStateBackup] before running the binary. The filtering result itself is
 * still whatever the binary writes — byte-identical to invoking it the same way.
 *
 * Quirks preserved (do not "fix"):
 * - `--origin` is `"%.8f,%.8f"` of the **first valid node**, not the bbox centre.
 *   The flag is omitted entirely when no valid node exists, so the tool falls
 *   back to its own default (`49.0,8.4`).
 * - The binary is chosen by globbing and taking `sorted(...).last()`, which is
 *   lexicographic rather than newest, so a stale Conan deploy can win.
 *
 * Threading is **not** a divergence: the Jython iterated `ds.getNodes()` on the
 * worker thread, which violates JOSM's rule that DataSet reads happen on the
 * EDT (its own 3D hook is careful about this). We read the dataset on the EDT
 * and keep only the subprocess off it. Observable behaviour is unchanged.
 *
 * Reload stays a layer swap ([OsmIo.reloadLayerFromFile]), outside the undo
 * stack; the backup is what makes the overwrite recoverable.
 */
object FilterBroken {
    const val TITLE = "Filter Broken Lanelets"

    fun isToolingRoot(path: File): Boolean {
        if (!path.isDirectory) return false
        if (File(path, "lanelet2_deploy").isDirectory) return true
        return File(path, "ll2_mapping_gui${File.separator}ll2_env.sh").isFile
    }

    fun ll2EnvSh(toolingRoot: File): File =
        File(toolingRoot, "ll2_mapping_gui${File.separator}ll2_env.sh")

    /**
     * Infer tooling root the way the Jython did: `LL2_TOOLING_ROOT`, else walk
     * up from [startDir] (Jython used `__file__`; from a jar that is
     * `user.dir`) looking for [isToolingRoot], else `~/ll2_tooling_root`.
     */
    fun resolveToolingRoot(
        envRoot: String? = System.getenv("LL2_TOOLING_ROOT"),
        startDir: File = File(System.getProperty("user.dir", ".")),
        userHome: String = System.getProperty("user.home"),
    ): File {
        val env = envRoot?.trim().orEmpty()
        if (env.isNotEmpty()) {
            return File(BackendRunner.expandUser(env)).absoluteFile
        }
        var d: File? = startDir.absoluteFile
        var hops = 0
        while (d != null && hops < 8) {
            if (isToolingRoot(d)) return d
            val parent = d.parentFile
            if (parent == null || parent.absolutePath == d.absolutePath) break
            d = parent
            hops++
        }
        return File(userHome, "ll2_tooling_root").absoluteFile
    }

    /**
     * Absolute path of the Conan-deployed `load_filtered_osm`, or null.
     *
     * Tries the `bin/` glob first, then `lib/lanelet2_validation/`. Each glob's
     * hits are sorted as strings and the **last** is returned — lexicographic,
     * so `1.2.2` wins over `1.2.10`. A filesystem walk is only used when both
     * globs miss; directories named `include` / `share` / `site-packages` are
     * not descended into.
     */
    fun findLoadFilteredOsm(toolingRoot: File): File? {
        val deploy = File(toolingRoot, "lanelet2_deploy")
        val patterns = listOf(
            listOf("full_deploy", "host", "lanelet2", "*", "*", "*", "bin", "load_filtered_osm"),
            listOf(
                "full_deploy", "host", "lanelet2", "*", "*", "*",
                "lib", "lanelet2_validation", "load_filtered_osm",
            ),
        )
        for (pat in patterns) {
            val hits = globUnder(deploy, pat)
            if (hits.isEmpty()) continue
            val last = hits.map { it.absolutePath }.sorted().last()
            return File(last)
        }
        if (deploy.isDirectory) return walkForBinary(deploy)
        return null
    }

    /**
     * `"%.8f,%.8f"` of the first valid node. Null when none exist (caller must
     * omit `--origin` rather than passing an empty string).
     *
     * Must be called on the EDT when [ds] is a live edit-layer dataset.
     */
    fun projectionOriginFromDataSet(ds: DataSet?): String? {
        if (ds == null) return null
        return try {
            projectionOriginFromNodes(ds.nodes)
        } catch (_: Exception) {
            null
        }
    }

    fun projectionOriginFromNodes(nodes: Iterable<Node?>): String? {
        return try {
            for (n in nodes) {
                if (n == null || n.isIncomplete || n.isDeleted) continue
                val coor = n.coor ?: continue
                try {
                    if (!coor.isValid) continue
                } catch (_: Exception) {
                    // Jython: if isValid() throws, still use this node.
                }
                return formatOrigin(coor.lat(), coor.lon())
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Python `"%.8f,%.8f" % (lat, lon)` — always a `.` decimal, not a locale comma. */
    fun formatOrigin(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.8f,%.8f", lat, lon)

    fun confirmMessage(file: File): String {
        val backup = JosmStateBackup.pathFor(file).name
        return "This will overwrite the map file in place after writing a backup as '$backup'. " +
            "The reload is not an undo step.\n\nFile: ${file.name}"
    }

    fun buildBashCommand(
        ll2Env: File,
        binary: File,
        input: File,
        output: File,
        origin: String?,
    ): String {
        val cmd = StringBuilder()
        cmd.append("source ").append(BackendRunner.shellQuote(ll2Env.absolutePath))
        cmd.append(" && ").append(BackendRunner.shellQuote(binary.absolutePath))
        cmd.append(' ').append(BackendRunner.shellQuote(input.absolutePath))
        cmd.append(' ').append(BackendRunner.shellQuote(output.absolutePath))
        if (!origin.isNullOrEmpty()) {
            cmd.append(" --origin ").append(BackendRunner.shellQuote(origin))
        }
        return cmd.toString()
    }

    fun runLoadFilteredOsm(
        toolingRoot: File,
        input: File,
        output: File,
        origin: String?,
    ): BackendResult {
        val ll2Env = ll2EnvSh(toolingRoot)
        if (!ll2Env.isFile) {
            return BackendResult.fail(
                "ll2_env.sh not found at ${ll2Env.path}. Run build_lanelet2_conan.sh first.",
            )
        }
        val binary = findLoadFilteredOsm(toolingRoot)
        if (binary == null) {
            return BackendResult.fail(
                "load_filtered_osm not found under ${toolingRoot.path}/lanelet2_deploy. " +
                    "Run ll2_mapping_gui/build_lanelet2_conan.sh first.",
            )
        }
        val cmd = buildBashCommand(ll2Env, binary, input, output, origin)
        val result = BackendRunner.runProcess(listOf("bash", "-c", cmd), toolingRoot)
        if (result.success) return result
        val err = result.stderr.trim().ifEmpty {
            "load_filtered_osm failed with code ${result.exitCode ?: -1}"
        }
        return BackendResult.fail(err, result.stdout, result.stderr, result.exitCode)
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val assoc = layer.associatedFile
        if (assoc == null || !assoc.exists()) {
            ui.warn(
                "The active layer has no associated file. Save the map to a .osm file first.",
                TITLE,
            )
            return
        }
        val toolingRoot = resolveToolingRoot()
        if (!toolingRoot.isDirectory) {
            ui.error(
                "Tooling root not found: ${toolingRoot.absolutePath}\n" +
                    "Set LL2_TOOLING_ROOT or run from standard layout.",
                TITLE,
            )
            return
        }
        saveIfDirty(layer)
        if (!ui.confirm(confirmMessage(assoc), "$TITLE - Confirm")) return
        try {
            JosmStateBackup.copy(assoc)
        } catch (e: Exception) {
            ui.error("Failed to create backup: ${e.message}", TITLE)
            return
        }
        val origin = projectionOriginFromDataSet(layer.dataSet)
        val filePath = assoc.absolutePath
        Thread {
            val result = runLoadFilteredOsm(toolingRoot, File(filePath), File(filePath), origin)
            SwingUtilities.invokeLater { onDone(ui, layer, File(filePath), result) }
        }.apply {
            isDaemon = true
            name = "lanelet2-filter-broken"
        }.start()
    }

    internal fun onDone(ui: UserPrompts, layer: OsmDataLayer, file: File, result: BackendResult) {
        if (!result.success) {
            ui.error("Filter failed:\n${result.error ?: "Unknown error"}", TITLE)
            return
        }
        if (!file.isFile) {
            ui.error("Output file not found: ${file.absolutePath}", TITLE)
            return
        }
        val ok = try {
            OsmIo.reloadLayerFromFile(layer, file)
        } catch (e: Exception) {
            Logging.warn("lanelet2: reload after filter-broken failed: {0}", e.message)
            false
        }
        if (ok) {
            ui.infoAutoClose("Filtered map. Reloaded layer.", TITLE, 2000)
        } else {
            ui.warn(
                "Filter succeeded but failed to reload layer. Reload the file manually.",
                TITLE,
            )
        }
    }

    internal fun globUnder(base: File, parts: List<String>): List<File> {
        if (!base.isDirectory || parts.isEmpty()) return emptyList()
        var current = listOf(base)
        for (part in parts) {
            current = current.flatMap { dir ->
                if (!dir.isDirectory) return@flatMap emptyList()
                val children = dir.listFiles() ?: return@flatMap emptyList()
                if (part == "*") children.toList() else children.filter { it.name == part }
            }
        }
        return current.filter { it.isFile }
    }

    private fun walkForBinary(deploy: File): File? {
        val skip = setOf("include", "share", "site-packages")
        fun walk(dir: File): File? {
            val entries = dir.listFiles() ?: return null
            if (entries.any { it.isFile && it.name == "load_filtered_osm" }) {
                return File(dir, "load_filtered_osm")
            }
            for (sub in entries) {
                if (sub.isDirectory && sub.name !in skip) {
                    walk(sub)?.let { return it }
                }
            }
            return null
        }
        return walk(deploy)
    }

    private fun saveIfDirty(layer: OsmDataLayer) {
        try {
            if (layer.requiresSaveToFile()) {
                SaveAction.getInstance().doSave(layer, true)
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: save before filter-broken failed: {0}", e.message)
        }
    }
}
