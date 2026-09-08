package org.openstreetmap.josm.plugins.lanelet2.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.platform.FilePrompts
import org.openstreetmap.josm.plugins.lanelet2.platform.TextAndCheckboxResult
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime

class GitCommitTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        OsmFixtures.ensurePrefs()
    }

    @Test
    fun addArgsDefaultToActiveFileNotAddU() {
        assertEquals(listOf("add", "--", "maps/a.osm"), GitCommit.addArgs(stageAll = false, fileRelPath = "maps/a.osm"))
        assertEquals(listOf("add", "-A"), GitCommit.addArgs(stageAll = true, fileRelPath = "maps/a.osm"))
        assertEquals(listOf("add", "-A"), GitCommit.addArgs(stageAll = true, fileRelPath = null))
        assertEquals(listOf("add", "-u"), GitCommit.addArgs(stageAll = false, fileRelPath = null))
    }

    @Test
    fun usernameFollowsGetpassThenFallback() {
        assertEquals(
            "from-logname",
            GitCommit.resolveUsername(
                env = { if (it == "LOGNAME") "from-logname" else "user" },
                javaUser = { "java" },
            ),
        )
        assertEquals(
            "unknown",
            GitCommit.resolveUsername(env = { null }, javaUser = { null }),
        )
        assertEquals(
            "java-user",
            GitCommit.resolveUsername(env = { null }, javaUser = { "java-user" }),
        )
    }

    @Test
    fun commitTimestampUsesLiteralCet() {
        val ts = GitCommit.formatCommitTimestamp(LocalDateTime.of(2026, 9, 8, 16, 2, 0))
        assertEquals("Tue 08. Sep 16:02:00 CET 2026", ts)
        assertTrue("CET" in ts)
        val inRepo = GitCommit.defaultCommitMessage("alice", "map.osm", repoOnly = false, ts)
        val merged = GitCommit.defaultCommitMessage("alice", "map.osm", repoOnly = true, ts)
        assertEquals("alice editing: map.osm $ts", inRepo)
        assertEquals("alice editing (merged layer: map.osm) $ts", merged)
    }

    @Test
    fun mapsRepoCandidatesMatchJythonOrder(@TempDir dir: Path) {
        val tooling = dir.resolve("tooling").toFile()
        val home = dir.resolve("home").toFile()
        val c = GitCommit.mapsRepoCandidates(tooling, home.absolutePath)
        assertEquals(
            listOf(
                File(tooling, "ws_ll2_mapping_hiwis${File.separator}src${File.separator}ll2_maps_hiwis"),
                File(tooling, "ll2_maps_hiwis"),
                File(home, "ll2_maps_hiwis"),
                File(home, "workspaces${File.separator}hiwi_mapping_ws${File.separator}src${File.separator}ll2_maps_hiwis"),
            ),
            c,
        )
        val fallback = GitCommit.defaultMapsRepo(tooling, envMapsRoot = "", userHome = home.absolutePath)
        assertEquals(c.first(), fallback)
    }

    @Test
    fun envMapsRootWinsWhenItIsAGitRepo(@TempDir dir: Path) {
        val maps = ThrowawayGit.init(dir.resolve("maps").toFile())
        val picked = GitCommit.defaultMapsRepo(
            dir.resolve("tooling").toFile(),
            envMapsRoot = maps.absolutePath,
            userHome = dir.resolve("home").toFile().absolutePath,
        )
        assertEquals(maps.canonicalFile, picked.canonicalFile)
    }

    @Test
    fun resolveRepoDirUsesFileRepoWithoutDialog(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val osm = File(repo, "map.osm").apply { writeText("<osm/>\n") }
        val ui = GitPrompts()
        val resolved = GitCommit.resolveRepoDir(osm, dir.toFile(), ui, FakeFiles())
        assertEquals(repo.canonicalFile, resolved!!.first.canonicalFile)
        assertFalse(resolved.second)
        assertTrue(ui.options.isEmpty(), "in-repo file must not open the maps dialog")
    }

    @Test
    fun defaultStageOnlyActiveFileLeavesUntrackedUnstaged(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val current = File(repo, "current.osm").apply { writeText("v1\n") }
        ThrowawayGit.git(repo, "add", "current.osm")
        ThrowawayGit.git(repo, "commit", "-m", "base")
        current.writeText("v2\n")
        File(repo, "new.osm").writeText("untracked\n")
        val add = GitHelpers.runGit(repo, GitCommit.addArgs(stageAll = false, fileRelPath = "current.osm"))
        assertTrue(add.success, add.stderr)
        val status = GitHelpers.runGit(repo, listOf("status", "--short"))
        assertTrue(status.stdout.contains("current.osm"), status.stdout)
        assertTrue(status.stdout.contains("??") && status.stdout.contains("new.osm"), status.stdout)
        val staged = GitHelpers.runGit(repo, listOf("diff", "--cached", "--name-only"))
        assertEquals(listOf("current.osm"), staged.stdout.trim().lines().filter { it.isNotEmpty() })
    }

    @Test
    fun stageAllAddAPicksUpNewFiles(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        File(repo, "current.osm").writeText("v1\n")
        ThrowawayGit.git(repo, "add", "current.osm")
        ThrowawayGit.git(repo, "commit", "-m", "base")
        File(repo, "new.osm").writeText("untracked\n")
        val add = GitHelpers.runGit(repo, GitCommit.addArgs(stageAll = true, fileRelPath = "current.osm"))
        assertTrue(add.success, add.stderr)
        val staged = GitHelpers.runGit(repo, listOf("diff", "--cached", "--name-only"))
        assertEquals(setOf("new.osm"), staged.stdout.trim().lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun sidecarUnhealthyAfterConfirmDoesNotWriteBackup(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val osm = File(repo, "map.osm").apply { writeText("<osm version='0.6'/>\n") }
        ThrowawayGit.git(repo, "add", "map.osm")
        ThrowawayGit.git(repo, "commit", "-m", "base")
        osm.writeText("<osm version='0.6'><node id='1' lat='1' lon='1'/></osm>\n")
        val ui = GitPrompts()
        val deps = GitCommitDeps(
            ensureSidecar = { _, u ->
                u.warn("sidecar down", GitCommit.TITLE)
                false
            },
            convertIds = { error("positive ids must not run") },
            reload = { _, _ -> true },
            runOffEdt = { it() },
            runOnEdt = { it() },
            pushInTerminal = { false },
        )
        GitCommit.continueAfterConfirm(ui, osm, repo, repoOnly = false, layer = null, deps)
        assertFalse(JosmStateBackup.pathFor(osm).exists())
        assertTrue(ui.warnings.any { it.first.contains("sidecar down") })
    }

    @Test
    fun afterPositiveIdsDefaultCommitStagesOnlyCurrentFile(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val osm = File(repo, "map.osm").apply { writeText("v1\n") }
        ThrowawayGit.git(repo, "add", "map.osm")
        ThrowawayGit.git(repo, "commit", "-m", "base")
        osm.writeText("v2\n")
        File(repo, "other.osm").writeText("new\n")
        val ui = GitPrompts(
            text = TextAndCheckboxResult("tester editing: map.osm", checked = false),
            confirmPush = false,
        )
        val deps = GitCommitDeps(
            convertIds = { BackendResult.ok() },
            reload = { _, _ -> true },
            username = { "tester" },
            now = { LocalDateTime.of(2026, 9, 8, 16, 2, 0) },
            runOffEdt = { it() },
            runOnEdt = { it() },
            pushInTerminal = { false },
        )
        GitCommit.afterPositiveIds(ui, null, osm, repo, repoOnly = false, BackendResult.ok(), deps)
        val log = GitHelpers.runGit(repo, listOf("log", "-1", "--pretty=%s"))
        assertEquals("tester editing: map.osm", log.stdout.trim())
        val tracked = GitHelpers.runGit(repo, listOf("ls-files", "other.osm"))
        assertEquals("", tracked.stdout.trim(), "new file must stay untracked when checkbox is off")
        assertTrue(ui.infos.any { it.first.contains("Remember to push later") })
        assertTrue(JosmStateBackup.pathFor(osm).exists().not())
    }

    @Test
    fun cancellingMessageDialogLeavesNote(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val osm = File(repo, "map.osm").apply { writeText("v1\n") }
        val ui = GitPrompts(text = null)
        val deps = GitCommitDeps(
            reload = { _, _ -> true },
            runOffEdt = { it() },
            runOnEdt = { it() },
            pushInTerminal = { false },
        )
        GitCommit.afterPositiveIds(ui, null, osm, repo, repoOnly = false, BackendResult.ok(), deps)
        assertTrue(ui.infos.any { it.first.contains("Commit cancelled") })
    }

    @Test
    fun checkboxLabelsDescribeApprovedDivergence() {
        val inRepo = GitCommit.stageAllCheckboxLabel(repoOnly = false)
        val merged = GitCommit.stageAllCheckboxLabel(repoOnly = true)
        assertTrue(inRepo.contains("git add -A"), inRepo)
        assertTrue(inRepo.contains("only the active file"), inRepo)
        assertTrue(merged.contains("git add -A"), merged)
        assertTrue(merged.contains("git add -u"), merged)
    }
}

private class GitPrompts(
    var confirms: Boolean = true,
    var confirmPush: Boolean = true,
    var text: TextAndCheckboxResult? = TextAndCheckboxResult("msg", false),
) : UserPrompts {
    val warnings = mutableListOf<Pair<String, String>>()
    val infos = mutableListOf<Pair<String, String>>()
    val errors = mutableListOf<Pair<String, String>>()
    val options = mutableListOf<String>()
    var nextOption: String? = null

    override fun warn(message: String, title: String) {
        warnings.add(message to title)
    }

    override fun info(message: String, title: String) {
        infos.add(message to title)
    }

    override fun infoAutoClose(message: String, title: String, delayMs: Int) {
        infos.add(message to title)
    }

    override fun confirm(message: String, title: String): Boolean {
        if (title.contains("Push")) return confirmPush
        return confirms
    }

    override fun pick(title: String, message: String, options: List<String>): String? = nextOption

    override fun ask(title: String, message: String): String? = null

    override fun error(message: String, title: String) {
        errors.add(message to title)
    }

    override fun option(title: String, message: String, options: List<String>): String? {
        this.options.addAll(options)
        return nextOption
    }

    override fun textAndCheckbox(
        title: String,
        headerHtml: String,
        textLabel: String,
        defaultText: String,
        checkboxLabel: String,
        checkboxSelected: Boolean,
    ): TextAndCheckboxResult? = text
}

private class FakeFiles(private val directory: File? = null) : FilePrompts {
    override fun chooseDirectory(title: String, initial: File?): File? = directory
    override fun chooseFile(title: String, initial: File?): File? = null
    override fun chooseFiles(title: String, initial: File?): List<File>? = null
    override fun chooseSaveFile(title: String, initialDir: File, initialName: String): File? = null
}
