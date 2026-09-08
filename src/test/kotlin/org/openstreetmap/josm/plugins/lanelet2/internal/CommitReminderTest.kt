package org.openstreetmap.josm.plugins.lanelet2.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class CommitReminderTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        CommitReminder.uninstall()
    }

    @AfterEach
    fun tearDown() {
        CommitReminder.uninstall()
    }

    @Test
    fun shouldRemindMatchesJythonThreshold() {
        assertFalse(CommitReminder.shouldRemind(100, hasUnpushed = false))
        assertFalse(CommitReminder.shouldRemind(3600, hasUnpushed = false))
        assertTrue(CommitReminder.shouldRemind(3601, hasUnpushed = false))
        assertTrue(CommitReminder.shouldRemind(100, hasUnpushed = true))
        assertTrue(CommitReminder.shouldRemind(null, hasUnpushed = false))
    }

    @Test
    fun evaluateRepoRemindsWhenNeverCommitted(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        assertTrue(CommitReminder.evaluateRepo(repo, nowEpochSec = 1_000_000L))
    }

    @Test
    fun evaluateRepoSilentWhenRecentCommitAndNoUpstream(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        File(repo, "a.txt").writeText("a\n")
        ThrowawayGit.git(repo, "add", "a.txt")
        ThrowawayGit.git(repo, "commit", "-m", "first")
        val log = GitHelpers.runGit(repo, listOf("log", "-1", "--format=%ct"))
        val stamp = log.stdout.trim().toLong()
        assertFalse(CommitReminder.evaluateRepo(repo, nowEpochSec = stamp + 10))
        assertTrue(CommitReminder.evaluateRepo(repo, nowEpochSec = stamp + 4000))
    }

    @Test
    fun installLeavesTimerOffByDefault() {
        assertFalse(LaneletSettings.getGitCommitReminder())
        CommitReminder.install()
        assertFalse(CommitReminder.isRunning())
        LaneletSettings.setGitCommitReminder(true)
        CommitReminder.syncFromSettings()
        assertTrue(CommitReminder.isRunning())
        LaneletSettings.setGitCommitReminder(false)
        CommitReminder.syncFromSettings()
        assertFalse(CommitReminder.isRunning())
    }
}
