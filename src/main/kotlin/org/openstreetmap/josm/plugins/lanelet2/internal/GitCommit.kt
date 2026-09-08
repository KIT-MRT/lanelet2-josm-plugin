package org.openstreetmap.josm.plugins.lanelet2.internal

import org.openstreetmap.josm.actions.SaveAction
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.dependent.MakePositiveIds
import org.openstreetmap.josm.plugins.lanelet2.dependent.OsmIo
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.FileChoosers
import org.openstreetmap.josm.plugins.lanelet2.platform.FilePrompts
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import org.openstreetmap.josm.plugins.lanelet2.sidecar.Sidecar
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.SwingUtilities

/**
 * Collaborators for [GitCommit.run]. Tests inject a throwaway repo, a no-op
 * sidecar, and synchronous executors; production uses the defaults.
 */
class GitCommitDeps(
    val git: GitHelpers = GitHelpers,
    val ensureSidecar: (String, UserPrompts) -> Boolean = { title, ui -> Sidecar.ensureUsable(title, ui) },
    val convertIds: (String) -> BackendResult = { MakePositiveIds.convertFile(it) },
    val reload: (OsmDataLayer, File) -> Boolean = { layer, file -> OsmIo.reloadLayerFromFile(layer, file) },
    val now: () -> LocalDateTime = { LocalDateTime.now() },
    val username: () -> String = { GitCommit.resolveUsername() },
    val runOffEdt: (() -> Unit) -> Unit = { block ->
        Thread { block() }.apply { isDaemon = true; name = "lanelet2-git-commit" }.start()
    },
    val runOnEdt: (() -> Unit) -> Unit = { block -> SwingUtilities.invokeLater(block) },
    val pushInTerminal: (File) -> Boolean = { GitHelpers.runGitPushInTerminal(it) },
)

/**
 * Git Commit (current file). Port of `internal/ll2_git_commit.py`.
 *
 * Display name stays **"Git Commit (current file)"** even though the Jython
 * defaulted to `git add -u` across the whole repo (and therefore silently
 * skipped a new untracked `.osm`). **Approved divergence:** default to staging
 * **only the active file**, with a checkbox for `git add -A` (everything in the
 * repo that is not gitignored). The merged-map workflow, where the active file
 * lives outside the repo, has no current file to stage and therefore defaults
 * to the repo-wide option.
 *
 * Other Jython behaviour is kept, including:
 * - commit-message timestamp with a **literal `CET`** regardless of timezone
 * - username via getpass order (`LOGNAME`/`USER`/`LNAME`/`USERNAME`, then
 *   `user.name`, then `"unknown"`)
 * - backup to `.{filename}backup` before rewriting IDs
 * - maps-repo fallback candidate list and dialog
 * - push via xterm when available, else captured-stdio `git push`
 * - positive IDs through [Sidecar] / [MakePositiveIds], not a Python shell-out
 *
 * The Jython ran positive IDs on the EDT, blocking JOSM. We keep the same
 * user-visible sequencing (confirm → backup → convert → reload → message dialog)
 * but run the sidecar off the EDT. Cancelling the message dialog still leaves
 * IDs rewritten — that is upstream behaviour, not a bug to fix.
 *
 * Reload is a layer swap, not a Command; [JosmStateBackup] is the undo.
 */
object GitCommit {
    const val TITLE = "Git Commit"
    const val MAPS_OPTIONS_OK = "OK"
    const val MAPS_OPTIONS_SELECT = "Select different..."
    const val MAPS_OPTIONS_CANCEL = "Cancel"

    private val COMMIT_TS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE dd. MMM HH:mm:ss 'CET' yyyy", Locale.ENGLISH)

    fun mapsRepoDialogOptions(): List<String> =
        listOf(MAPS_OPTIONS_OK, MAPS_OPTIONS_SELECT, MAPS_OPTIONS_CANCEL)

    /**
     * getpass.getuser() order (`LOGNAME`, `USER`, `LNAME`, `USERNAME`, then the
     * account name), then the Jython except-branch (`USER`/`USERNAME`/`LOGNAME`),
     * then `"unknown"`.
     */
    fun resolveUsername(
        env: (String) -> String? = { System.getenv(it) },
        javaUser: () -> String? = { System.getProperty("user.name") },
    ): String {
        return try {
            for (name in listOf("LOGNAME", "USER", "LNAME", "USERNAME")) {
                val v = env(name)
                if (!v.isNullOrEmpty()) return v
            }
            javaUser()?.takeIf { it.isNotEmpty() } ?: error("no username")
        } catch (_: Exception) {
            env("USER") ?: env("USERNAME") ?: env("LOGNAME") ?: "unknown"
        }
    }

    /**
     * `datetime.datetime.now().strftime("%a %d. %b %H:%M:%S CET %Y")`. The
     * letters `CET` are literal — not the real timezone. Weekday/month names
     * are English; Jython followed `LC_TIME`.
     */
    fun formatCommitTimestamp(now: LocalDateTime = LocalDateTime.now()): String = now.format(COMMIT_TS)

    fun defaultCommitMessage(
        username: String,
        fileName: String,
        repoOnly: Boolean,
        datestring: String,
    ): String = if (repoOnly) {
        "$username editing (merged layer: $fileName) $datestring"
    } else {
        "$username editing: $fileName $datestring"
    }

    fun normalizeCommitMessage(raw: String?, default: String): String {
        val stripped = raw?.trim().orEmpty()
        return stripped.ifEmpty { default }.ifEmpty { default }
    }

    /**
     * `git add` argv after the approved staging divergence.
     *
     * @param stageAll checkbox: `git add -A`
     * @param fileRelPath relative path of the active file when it lives in the
     *   repo; null for the merged-map workflow
     */
    fun addArgs(stageAll: Boolean, fileRelPath: String?): List<String> = when {
        stageAll -> listOf("add", "-A")
        fileRelPath != null -> listOf("add", "--", fileRelPath)
        else -> listOf("add", "-u")
    }

    fun stageAllCheckboxLabel(repoOnly: Boolean): String = if (repoOnly) {
        "Stage everything in the repository that is not gitignored (git add -A). " +
            "If unchecked: only tracked files (git add -u)."
    } else {
        "Stage everything in the repository that is not gitignored (git add -A). " +
            "If unchecked: only the active file."
    }

    fun confirmBackupMessage(fileName: String, repoDir: File, repoOnly: Boolean): String = if (repoOnly) {
        "Back up JOSM state as '.<filename>.osmbackup' and make IDs positive on the " +
            "active merged layer, then commit all changes in the maps repository?\n\n" +
            "Layer file: $fileName\nRepository: ${repoDir.absolutePath}"
    } else {
        "Back up JOSM state as '.<filename>.osmbackup' and make IDs positive, then commit?\n\n" +
            "File: $fileName"
    }

    fun statusHeader(fileName: String, repoDir: File, repoOnly: Boolean): String = if (repoOnly) {
        "Repository: ${repoDir.absolutePath}<br>Active layer: $fileName"
    } else {
        "File: $fileName"
    }

    fun statusHtml(header: String, statusText: String): String {
        val escaped = statusText.replace("<", "&lt;").replace(">", "&gt;")
        return "<html>$header<br><br>Git status:<br><pre>$escaped</pre></html>"
    }

    /**
     * Tooling root for the maps-repo fallback. Jython: `LL2_TOOLING_ROOT`, else
     * the grandparent of `internal/` if `ws_ll2_mapping_hiwis/` exists, else
     * `~/ll2_tooling_root`. From a jar we walk [startDir] (`user.dir`) for that
     * workspace directory instead of `__file__`.
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
            if (File(d, "ws_ll2_mapping_hiwis").isDirectory) return d
            val parent = d.parentFile
            if (parent == null || parent.absolutePath == d.absolutePath) break
            d = parent
            hops++
        }
        return File(userHome, "ll2_tooling_root").absoluteFile
    }

    fun mapsRepoCandidates(
        toolingRoot: File,
        userHome: String = System.getProperty("user.home"),
    ): List<File> = listOf(
        File(toolingRoot, "ws_ll2_mapping_hiwis${File.separator}src${File.separator}ll2_maps_hiwis"),
        File(toolingRoot, "ll2_maps_hiwis"),
        File(userHome, "ll2_maps_hiwis"),
        File(userHome, "workspaces${File.separator}hiwi_mapping_ws${File.separator}src${File.separator}ll2_maps_hiwis"),
    )

    /**
     * First existing git repo among env + [mapsRepoCandidates], else
     * `candidates[0]` even when that path is not a repo (Jython shows it in
     * the dialog anyway).
     */
    fun defaultMapsRepo(
        toolingRoot: File,
        envMapsRoot: String? = System.getenv("LL2_MAPS_HIWIS_ROOT"),
        userHome: String = System.getProperty("user.home"),
        isRepo: (File) -> Boolean = { GitHelpers.isGitRepo(it) },
    ): File {
        val env = envMapsRoot?.trim().orEmpty()
        if (env.isNotEmpty()) {
            val p = File(BackendRunner.expandUser(env)).absoluteFile
            if (isRepo(p)) return p
        }
        val candidates = mapsRepoCandidates(toolingRoot, userHome)
        for (p in candidates) {
            if (isRepo(p)) return p
        }
        return candidates.first()
    }

    fun resolveRepoDir(
        filePath: File,
        toolingRoot: File,
        ui: UserPrompts,
        files: FilePrompts,
        git: GitHelpers = GitHelpers,
        envMapsRoot: String? = System.getenv("LL2_MAPS_HIWIS_ROOT"),
    ): Pair<File, Boolean>? {
        val found = git.findGitRepo(filePath)
        if (found != null && git.isGitRepo(found)) return found to false
        val defaultRepo = defaultMapsRepo(toolingRoot, envMapsRoot)
        val picked = showMapsRepoDialog(ui, files, defaultRepo) ?: return null
        if (!git.isGitRepo(picked)) {
            ui.error("Not a git repository:\n\n${picked.absolutePath}", TITLE)
            return null
        }
        return picked to true
    }

    fun showMapsRepoDialog(ui: UserPrompts, files: FilePrompts, defaultPath: File): File? {
        val msg =
            "The active layer file is not in a git repository (typical for merged maps).\n\n" +
                "Commit changes in the maps repository:\n\n${defaultPath.absolutePath}\n\n" +
                "Click OK to use this path, or 'Select different...' to choose another directory."
        val choice = ui.option(
            "Git Commit - Maps Repository",
            msg,
            mapsRepoDialogOptions(),
        ) ?: return null
        return when (choice) {
            MAPS_OPTIONS_CANCEL -> null
            MAPS_OPTIONS_SELECT -> files.chooseDirectory(
                "Select ll2_maps_hiwis repository root",
                defaultPath,
            )
            else -> defaultPath
        }
    }

    fun run(
        ui: UserPrompts = Dialogs,
        files: FilePrompts = FileChoosers,
        layer: OsmDataLayer? = requireVisibleEditLayer(),
        deps: GitCommitDeps = GitCommitDeps(),
        toolingRoot: File = resolveToolingRoot(),
    ) {
        if (layer == null || !layer.isVisible) {
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
        val filePath = assoc.absoluteFile
        val resolved = resolveRepoDir(filePath, toolingRoot, ui, files, deps.git) ?: return
        val (repoDir, repoOnly) = resolved
        saveIfDirty(layer)

        val hasLocalChanges = if (repoOnly) {
            deps.git.repoHasChanges(repoDir)
        } else {
            deps.git.isFileModified(repoDir, filePath)
        }
        if (!hasLocalChanges) {
            handleNoLocalChanges(ui, deps, repoDir, repoOnly)
            return
        }

        if (!ui.confirm(confirmBackupMessage(filePath.name, repoDir, repoOnly), "Git Commit - Confirm")) {
            return
        }
        continueAfterConfirm(ui, filePath, repoDir, repoOnly, layer, deps)
    }

    /**
     * Sidecar check, backup, positive IDs off the EDT, then [afterPositiveIds].
     * [layer] may be null in tests; reload is then skipped.
     */
    internal fun continueAfterConfirm(
        ui: UserPrompts,
        filePath: File,
        repoDir: File,
        repoOnly: Boolean,
        layer: OsmDataLayer?,
        deps: GitCommitDeps,
    ) {
        if (!deps.ensureSidecar(TITLE, ui)) return
        try {
            JosmStateBackup.copy(filePath)
        } catch (e: Exception) {
            ui.error("Failed to create backup: ${e.message}", TITLE)
            return
        }
        deps.runOffEdt {
            val result = deps.convertIds(filePath.absolutePath)
            deps.runOnEdt {
                afterPositiveIds(ui, layer, filePath, repoDir, repoOnly, result, deps)
            }
        }
    }

    internal fun afterPositiveIds(
        ui: UserPrompts,
        layer: OsmDataLayer?,
        filePath: File,
        repoDir: File,
        repoOnly: Boolean,
        result: BackendResult,
        deps: GitCommitDeps,
    ) {
        if (!result.success) {
            ui.error("Make positive IDs failed:\n${result.error ?: "Unknown error"}", TITLE)
            return
        }
        if (layer != null) {
            try {
                deps.reload(layer, filePath)
            } catch (e: Exception) {
                Logging.warn("lanelet2: reload after git-commit positive ids failed: {0}", e.message)
            }
        }

        val status = deps.git.runGit(repoDir, listOf("status", "--short"))
        val statusText = if (status.success) status.stdout else status.stderr.ifEmpty { "git status failed" }
        val datestring = formatCommitTimestamp(deps.now())
        val defaultMsg = defaultCommitMessage(deps.username(), filePath.name, repoOnly, datestring)
        val header = statusHeader(filePath.name, repoDir, repoOnly)
        val choice = ui.textAndCheckbox(
            "Git Commit - Message and scope",
            statusHtml(header, statusText),
            "Commit message:",
            defaultMsg,
            stageAllCheckboxLabel(repoOnly),
            repoOnly,
        )
        if (choice == null) {
            ui.info(
                "Commit cancelled. Remember to push later if you commit from terminal.",
                TITLE,
            )
            return
        }
        val commitMsg = normalizeCommitMessage(choice.text, defaultMsg)
        val fileRel = if (repoOnly) null else deps.git.relativize(repoDir, filePath)
        val add = deps.git.runGit(repoDir, addArgs(choice.checked, fileRel))
        if (!add.success) {
            ui.error("git add failed:\n${add.stderr.ifEmpty { add.stdout }}", TITLE)
            return
        }
        val commit = deps.git.runGit(repoDir, listOf("commit", "-m", commitMsg))
        if (!commit.success) {
            ui.error("git commit failed:\n${commit.stderr.ifEmpty { commit.stdout }}", TITLE)
            return
        }
        askPush(ui, deps, repoDir, afterCommit = true)
    }

    private fun handleNoLocalChanges(
        ui: UserPrompts,
        deps: GitCommitDeps,
        repoDir: File,
        repoOnly: Boolean,
    ) {
        if (deps.git.hasUnpushedCommits(repoDir)) {
            if (ui.confirm("No local changes. You have unpushed commits. Push to remote?", "Git Push")) {
                askPush(ui, deps, repoDir, afterCommit = false)
            }
            return
        }
        val msg = if (repoOnly) {
            "No uncommitted changes in the maps repository and nothing to push."
        } else {
            "No modifications to the current file and nothing to push."
        }
        ui.info(msg, TITLE)
    }

    private fun askPush(ui: UserPrompts, deps: GitCommitDeps, repoDir: File, afterCommit: Boolean) {
        if (afterCommit) {
            if (!ui.confirm("Commit successful. Push to remote?", "Git Push")) {
                ui.info("Remember to push later!", TITLE)
                return
            }
        }
        if (deps.pushInTerminal(repoDir)) {
            ui.info(
                "Push started in a terminal. Enter username/password there if prompted.",
                "Git Push",
            )
            return
        }
        val pushed = deps.git.runGit(repoDir, listOf("push"))
        if (!pushed.success) {
            val detail = pushed.stderr.ifEmpty { pushed.stdout }
            val prefix = if (afterCommit) {
                "git push failed (no terminal for credentials). Push from a terminal if needed.\n\n"
            } else {
                "git push failed.\n\n"
            }
            ui.error(prefix + detail, TITLE)
        } else if (afterCommit) {
            ui.infoAutoClose("Committed and pushed.", TITLE, 2000)
        } else {
            ui.infoAutoClose("Pushed to remote.", TITLE, 2000)
        }
    }

    private fun saveIfDirty(layer: OsmDataLayer) {
        try {
            if (layer.requiresSaveToFile()) {
                SaveAction.getInstance().doSave(layer, true)
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: save before git commit failed: {0}", e.message)
        }
    }
}
