package org.openstreetmap.josm.plugins.lanelet2.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GitHelpersTest {

    @Test
    fun findGitRepoWalksUpAndRequiresDotGitDirectory(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val nested = File(repo, "maps/sub").apply { mkdirs() }
        val osm = File(nested, "city.osm").apply { writeText("<osm/>\n") }
        assertEquals(repo.canonicalFile, GitHelpers.findGitRepo(osm)?.canonicalFile)
        assertTrue(GitHelpers.isGitRepo(repo))

        val fileGit = dir.resolve("worktree-like").toFile().apply { mkdirs() }
        File(fileGit, ".git").writeText("gitdir: /somewhere")
        assertFalse(GitHelpers.isGitRepo(fileGit), "worktree .git file must not count")
        assertNull(GitHelpers.findGitRepo(File(fileGit, "a.osm").apply { writeText("x") }))
    }

    @Test
    fun hasUnpushedCommitsIsFalseWithoutUpstream(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        File(repo, "a.txt").writeText("a\n")
        ThrowawayGit.git(repo, "add", "a.txt")
        ThrowawayGit.git(repo, "commit", "-m", "first")
        assertFalse(GitHelpers.hasUnpushedCommits(repo))

        val bare = dir.resolve("remote.git").toFile()
        ThrowawayGit.git(dir.toFile(), "init", "--bare", bare.absolutePath)
        ThrowawayGit.git(repo, "remote", "add", "origin", bare.absolutePath)
        assertFalse(
            GitHelpers.hasUnpushedCommits(repo),
            "a remote without upstream still returns false",
        )
    }

    @Test
    fun hasUnpushedCommitsIsTrueWhenAheadOfUpstream(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        File(repo, "a.txt").writeText("a\n")
        ThrowawayGit.git(repo, "add", "a.txt")
        ThrowawayGit.git(repo, "commit", "-m", "first")
        val bare = dir.resolve("remote.git").toFile()
        ThrowawayGit.git(dir.toFile(), "init", "--bare", bare.absolutePath)
        ThrowawayGit.git(repo, "remote", "add", "origin", bare.absolutePath)
        val branch = ThrowawayGit.currentBranch(repo)
        val pushed = ThrowawayGit.git(repo, "push", "-u", "origin", branch)
        assertTrue(pushed.success, pushed.stderr)
        assertFalse(GitHelpers.hasUnpushedCommits(repo))
        File(repo, "a.txt").writeText("b\n")
        ThrowawayGit.git(repo, "add", "a.txt")
        ThrowawayGit.git(repo, "commit", "-m", "second")
        assertTrue(GitHelpers.hasUnpushedCommits(repo))
    }

    @Test
    fun isFileModifiedSeesUntrackedAndDirty(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val tracked = File(repo, "tracked.osm").apply { writeText("one\n") }
        ThrowawayGit.git(repo, "add", "tracked.osm")
        ThrowawayGit.git(repo, "commit", "-m", "add")
        assertFalse(GitHelpers.isFileModified(repo, tracked))
        tracked.writeText("two\n")
        assertTrue(GitHelpers.isFileModified(repo, tracked))
        val fresh = File(repo, "new.osm").apply { writeText("new\n") }
        assertTrue(GitHelpers.isFileModified(repo, fresh), "untracked must count as modified")
        assertTrue(GitHelpers.repoHasChanges(repo))
    }

    @Test
    fun xtermPushCommandKeepsJythonQuoting() {
        val cmd = GitHelpers.xtermPushCommand(File("/tmp/o'reilly"))
        assertEquals("xterm", cmd[0])
        assertTrue("git push" in cmd.last())
        assertTrue("exec bash" in cmd.last())
        assertTrue("'\\''" in cmd.last(), cmd.last())
    }
}

/**
 * Throwaway git repos under JUnit [TempDir]. Never pointed at `/ll2_tooling_root`.
 */
internal object ThrowawayGit {
    fun init(repo: File): File {
        repo.mkdirs()
        val abs = repo.canonicalFile.absolutePath
        check(abs != File("/ll2_tooling_root").canonicalFile.absolutePath) {
            "refusing to git-init the tooling root"
        }
        val init = GitHelpers.runGit(repo, listOf("init"))
        check(init.success) { "git init failed: ${init.stderr}" }
        git(repo, "config", "user.name", "Test")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "commit.gpgsign", "false")
        return repo
    }

    fun git(repo: File, vararg args: String): GitOutcome {
        val abs = repo.canonicalFile.absolutePath
        check(abs != File("/ll2_tooling_root").canonicalFile.absolutePath)
        val r = GitHelpers.runGit(repo, args.toList())
        check(r.success) { "git ${args.joinToString(" ")} failed: ${r.stderr}\n${r.stdout}" }
        return r
    }

    fun currentBranch(repo: File): String {
        val r = GitHelpers.runGit(repo, listOf("rev-parse", "--abbrev-ref", "HEAD"))
        check(r.success) { r.stderr }
        return r.stdout.trim()
    }
}
