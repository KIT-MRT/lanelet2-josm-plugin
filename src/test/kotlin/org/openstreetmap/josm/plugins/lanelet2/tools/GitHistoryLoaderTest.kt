package org.openstreetmap.josm.plugins.lanelet2.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.internal.ThrowawayGit
import org.openstreetmap.josm.plugins.lanelet2.sidecar.RecordingPrompts
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

class GitHistoryLoaderTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun parseCommitsSplitsOnFirstThreePipesAndTruncatesTime() {
        val raw = """
            abcdef0123456789|plain message|2024-01-15 10:30:00 +0100|Ada
            deadbeef00000000|second|2025-12-01 09:05:59 -0500|Bob
            cafe0000|msg with | pipe|2024-02-01 08:00:00 +0000|Cara
            
        """.trimIndent()
        val commits = GitHistoryLoader.parseCommits(raw)
        assertEquals(3, commits.size)
        assertEquals("abcdef0123456789", commits[0].hash)
        assertEquals("plain message", commits[0].message)
        assertEquals("2024-01-15", commits[0].date)
        assertEquals("10:30", commits[0].time)
        assertEquals("Ada", commits[0].committer)
        assertEquals("09:05", commits[1].time)
        assertEquals("Bob", commits[1].committer)
        // Jython `split("|", 3)` still splits a `|` inside the subject.
        assertEquals("msg with ", commits[2].message)
        assertEquals("pipe", commits[2].date)
        assertEquals("", commits[2].time)
        assertEquals("2024-02-01 08:00:00 +0000|Cara", commits[2].committer)
    }

    @Test
    fun parseCommitsSkipsShortLines() {
        assertTrue(GitHistoryLoader.parseCommits("only|two").isEmpty())
        assertTrue(GitHistoryLoader.parseCommits("").isEmpty())
    }

    @Test
    fun deltaMessageUsesPythonStyleSignedInts() {
        val msg = GitHistoryLoader.deltaMessage(
            GitHistoryLoader.Counts(5, 1, 0),
            GitHistoryLoader.Counts(7, 1, 2),
        )
        assertTrue("Nodes: +-2" in msg || "Nodes: -2" in msg)
        assertTrue("Ways: +0" in msg)
        assertTrue("Relations: +-2" in msg || "Relations: -2" in msg)
        val plus = GitHistoryLoader.deltaMessage(
            GitHistoryLoader.Counts(10, 3, 1),
            GitHistoryLoader.Counts(7, 1, 0),
        )
        assertTrue("Nodes: +3" in plus)
        assertTrue("Ways: +2" in plus)
        assertTrue("Relations: +1" in plus)
    }

    @Test
    fun layerNameUsesHash8AndDate() {
        val c = GitHistoryLoader.Commit("abcdefghijklmnop", "m", "2024-01-15", "10:30", "Ada")
        assertEquals("map.osm @ abcdefgh (2024-01-15)", GitHistoryLoader.layerNameFor(File("/tmp/map.osm"), c))
    }

    @Test
    fun getCommitsAndExtractRoundTrip(@TempDir dir: Path) {
        val repo = ThrowawayGit.init(dir.resolve("repo").toFile())
        val osm = File(repo, "map.osm")
        osm.writeText("<osm version='0.6'><node id='1' lat='1' lon='1'/></osm>\n")
        ThrowawayGit.git(repo, "add", "map.osm")
        ThrowawayGit.git(repo, "commit", "-m", "first")
        osm.writeText(
            "<osm version='0.6'><node id='1' lat='1' lon='1'/><node id='2' lat='2' lon='2'/></osm>\n",
        )
        ThrowawayGit.git(repo, "add", "map.osm")
        ThrowawayGit.git(repo, "commit", "-m", "second")

        val commits = GitHistoryLoader.getCommits(repo, osm, limit = 20)
        assertEquals(2, commits.size)
        assertEquals("second", commits[0].message)
        assertEquals("first", commits[1].message)
        assertTrue(commits[0].hash.length >= 8)
        assertTrue(commits[0].time.matches(Regex("\\d{2}:\\d{2}")))

        val extracted = GitHistoryLoader.extractFileAtCommit(repo, osm, commits[1].hash)
        assertTrue(extracted != null && extracted.isFile)
        val body = extracted!!.readText()
        assertTrue("id='1'" in body, body)
        assertTrue("id='2'" !in body, body)
        assertTrue(extracted.name.startsWith("map_${commits[1].hash.take(8)}_"))
        assertTrue(extracted.name.endsWith(".osm"))
        extracted.delete()

        val ds = org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures.dataSet(
            org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures.node(1.0, 1.0),
            org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        assertEquals(GitHistoryLoader.Counts(3, 1, 0), GitHistoryLoader.countDataset(ds))

        val missing = GitHistoryLoader.extractFileAtCommit(repo, osm, "0".repeat(40))
        assertNull(missing)
    }

    @Test
    fun runWarnsWithoutEditLayer() {
        val ui = RecordingPrompts()
        GitHistoryLoader.run(ui)
        assertTrue(ui.warnings.any { it.second == GitHistoryLoader.TITLE })
    }
}
