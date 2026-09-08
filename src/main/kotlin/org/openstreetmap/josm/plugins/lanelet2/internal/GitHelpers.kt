package org.openstreetmap.josm.plugins.lanelet2.internal

import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendRunner
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException

/**
 * Outcome of a `git` CLI invocation. Port of `git_helpers.run_git`'s
 * `(success, stdout, stderr)` triple.
 *
 * The Jython decodes stdout with `utf-8/replace` and has **no** `AttributeError`
 * fallback (unlike its siblings). Kotlin always has a `String`; we do not
 * emulate that artifact.
 */
data class GitOutcome(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
)

/**
 * Thin `git` CLI wrappers. Port of `core/josm_tools/git_helpers.py`.
 *
 * [isGitRepo] / [findGitRepo] require `.git` to be a **directory**. Git
 * worktrees (where `.git` is a file) therefore look like "not a repo", matching
 * the Jython. [hasUnpushedCommits] returns false when there is no upstream —
 * `rev-list @{u}..HEAD` fails and the Jython treats any non-ok / non-digit
 * output as "nothing to push".
 */
object GitHelpers {
    fun findGitRepo(filePath: File): File? {
        var path = filePath.absoluteFile
        if (path.isFile) {
            path = path.parentFile ?: return null
        }
        while (true) {
            if (File(path, ".git").isDirectory) return path
            val parent = path.parentFile
            if (parent == null || parent.absolutePath == path.absolutePath) return null
            path = parent
        }
    }

    /** True iff [path] contains a `.git` **directory** (not a worktree gitfile). */
    fun isGitRepo(path: File?): Boolean {
        if (path == null) return false
        return File(path.absoluteFile, ".git").isDirectory
    }

    fun runGit(repoDir: File, args: List<String>): GitOutcome {
        val result = try {
            BackendRunner.runProcess(listOf("git") + args, repoDir)
        } catch (e: Exception) {
            return GitOutcome(false, "", e.message ?: e.toString())
        }
        if (result.exitCode == null && !result.success) {
            return GitOutcome(false, result.stdout, result.error ?: result.stderr)
        }
        return GitOutcome(result.success, result.stdout, result.stderr)
    }

    /**
     * `git push` in xterm so credentials can be typed. Returns true if xterm
     * was started. Headless / missing xterm → false; caller falls back to a
     * captured-stdio `git push` with no TTY for credentials.
     */
    fun runGitPushInTerminal(repoDir: File, start: (List<String>) -> Boolean = ::startProcess): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        return start(xtermPushCommand(repoDir))
    }

    /**
     * Exact xterm argv from `run_git_push_in_terminal`. Quoting of [repoDir]
     * matches the Jython `replace("'", "'\\''")` dance, including wrapping the
     * whole `cd ...; git push; exec bash` in `bash -i -c '...'`.
     */
    fun xtermPushCommand(repoDir: File): List<String> {
        val abs = repoDir.absoluteFile.absolutePath
        val safeDir = abs.replace("'", "'\\''")
        val cmdInShell = "cd '$safeDir'; git push; exec bash"
        val argE = "bash -i -c '" + cmdInShell.replace("'", "'\\''") + "'"
        return listOf(
            "xterm", "-fa", "DejaVu Sans Mono", "-fs", "11",
            "-bg", "#1e1e1e", "-fg", "#d4d4d4",
            "-e", argE,
        )
    }

    fun isFileModified(repoDir: File, filePath: File): Boolean {
        val rel = relativize(repoDir, filePath)
        val r = runGit(repoDir, listOf("status", "--short", rel))
        return r.success && r.stdout.trim().isNotEmpty()
    }

    fun repoHasChanges(repoDir: File): Boolean {
        val r = runGit(repoDir, listOf("status", "--short"))
        return r.success && r.stdout.trim().isNotEmpty()
    }

    /**
     * True if HEAD is ahead of upstream. No upstream → false (the `rev-list`
     * `@{u}` lookup fails and is not treated as "unpushed").
     */
    fun hasUnpushedCommits(repoDir: File): Boolean {
        val r = runGit(repoDir, listOf("rev-list", "@{u}..HEAD", "--count"))
        val count = r.stdout.trim()
        if (!r.success || count.isEmpty() || !count.all { it.isDigit() }) return false
        return count.toInt() > 0
    }

    fun relativize(repoDir: File, filePath: File): String {
        return repoDir.absoluteFile.toPath().relativize(filePath.absoluteFile.toPath()).toString()
    }

    fun startProcess(command: List<String>): Boolean {
        return try {
            ProcessBuilder(command).start()
            true
        } catch (_: IOException) {
            false
        }
    }
}
