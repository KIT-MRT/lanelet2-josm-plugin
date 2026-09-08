package org.openstreetmap.josm.plugins.lanelet2.internal

import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener
import java.io.File
import javax.swing.Timer
import javax.swing.SwingUtilities

/**
 * 60-minute commit / push reminder from `internal_launcher.py` (lines ~96–143).
 *
 * Settings-gated and **off by default**. The Jython always started the timer
 * when the internal launcher installed; we do not. The rest of the
 * hooks/lifecycle block in that launcher is a separate future task — this
 * object is only the reminder.
 *
 * Jython ran `git log` on the Swing timer thread (the EDT). We keep the
 * confirm dialog on the EDT but do the git probes off it; the prompt text and
 * 3600-second threshold are unchanged.
 */
object CommitReminder {
    const val INTERVAL_MS = 60 * 60 * 1000
    const val TITLE = "Commit / Push reminder"
    const val MESSAGE =
        "You have not committed in the last 60 minutes and/or have unpushed commits. " +
            "Do you want to commit and push?"

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var installed = false

    private val prefListener = PreferenceChangedListener { syncFromSettings() }

    fun shouldRemind(lastCommitAgoSec: Long?, hasUnpushed: Boolean): Boolean {
        val noCommitIn60Min = lastCommitAgoSec == null || lastCommitAgoSec > 3600
        return noCommitIn60Min || hasUnpushed
    }

    /**
     * `git log -1 --format=%ct` plus [GitHelpers.hasUnpushedCommits], matching
     * `_commit_reminder_tick`. Non-digit / failed log → treat as "never committed".
     */
    fun evaluateRepo(repoDir: File, nowEpochSec: Long, git: GitHelpers = GitHelpers): Boolean {
        val log = git.runGit(repoDir, listOf("log", "-1", "--format=%ct"))
        val stamp = log.stdout.trim()
        val lastAgo = if (log.success && stamp.isNotEmpty() && stamp.all { it.isDigit() }) {
            nowEpochSec - stamp.toLong()
        } else {
            null
        }
        return shouldRemind(lastAgo, git.hasUnpushedCommits(repoDir))
    }

    fun install() {
        if (installed) {
            syncFromSettings()
            return
        }
        installed = true
        LaneletSettings.addChangeListener(LaneletSettings.KEY_GIT_COMMIT_REMINDER, prefListener)
        syncFromSettings()
    }

    fun uninstall() {
        if (!installed) return
        try {
            LaneletSettings.removeChangeListener(LaneletSettings.KEY_GIT_COMMIT_REMINDER, prefListener)
        } catch (_: Exception) {
        }
        stop()
        installed = false
    }

    fun syncFromSettings() {
        if (LaneletSettings.getGitCommitReminder()) start() else stop()
    }

    @Synchronized
    fun start() {
        if (timer != null) return
        val t = Timer(INTERVAL_MS) { onTick() }
        t.isRepeats = true
        t.start()
        timer = t
    }

    @Synchronized
    fun stop() {
        timer?.stop()
        timer = null
    }

    fun isRunning(): Boolean = timer != null

    internal fun onTick() {
        Thread {
            val want = try {
                evaluateCurrentLayer()
            } catch (_: Exception) {
                false
            }
            if (!want) return@Thread
            SwingUtilities.invokeLater {
                try {
                    if (Dialogs.confirm(MESSAGE, TITLE)) {
                        GitCommit.run()
                    }
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            name = "lanelet2-commit-reminder"
        }.start()
    }

    private fun evaluateCurrentLayer(git: GitHelpers = GitHelpers): Boolean {
        val layer = requireVisibleEditLayer() ?: return false
        val assoc = layer.associatedFile ?: return false
        if (!assoc.exists()) return false
        val repoDir = git.findGitRepo(assoc) ?: return false
        if (!git.isGitRepo(repoDir)) return false
        val now = System.currentTimeMillis() / 1000L
        return evaluateRepo(repoDir, now, git)
    }
}
