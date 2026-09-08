package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class CreateLaneletRelationTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        CreateLaneletRelation.clearChain()
        CreateLaneletRelation.useDirectedSuccessorOverride = null
    }

    private fun twoBorderWays(): Pair<Way, Way> {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        return Pair(left, right)
    }

    @Test
    fun createAddsLaneletAndUndoRemovesIt() {
        val (left, right) = twoBorderWays()
        val ds = OsmFixtures.dataSet(left, right)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertEquals(1, result.outcomes.size)
            assertEquals(CreateLaneletRelation.OUTCOME_CREATED, result.outcomes[0].outcome)
            val rel = result.outcomes[0].rel
            assertTrue(rel in ds.relations)
            assertEquals("lanelet", rel.get("type"))
            assertEquals("road", rel.get("subtype"))
            assertEquals("urban", rel.get("location"))
            assertNull(rel.get("one_way"))
            val (lw, rw) = Lanelet.extractLeftRightWays(rel)
            assertSame(left, lw)
            assertSame(right, rw)
            assertEquals(1, undo.undoCommands.size)
            undo.undo()
            assertTrue(rel !in ds.relations)
        }
    }

    @Test
    fun createUsesSettingsForBidirectionalAndSubtype() {
        LaneletSettings.setLaneletDefaultSubtype("bicycle_lane")
        LaneletSettings.setLaneletDefaultOneWay(LaneletSettings.ONE_WAY_NO)
        val (left, right) = twoBorderWays()
        val ds = OsmFixtures.dataSet(left, right)
        OsmFixtures.withUndo { undo ->
            val rel = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo).outcomes[0].rel
            assertEquals("bicycle_lane", rel.get("subtype"))
            assertNull(rel.get("location"))
            assertEquals("no", rel.get("one_way"))
        }
    }

    @Test
    fun existingSameRetagsFromDefaults() {
        val (left, right) = twoBorderWays()
        val rel = OsmFixtures.laneletRelation(left, right, subtype = "walkway")
        rel.put("one_way", "no")
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertEquals(CreateLaneletRelation.OUTCOME_EXISTING_SAME, result.outcomes[0].outcome)
            assertEquals("road", rel.get("subtype"))
            assertEquals("urban", rel.get("location"))
            assertNull(rel.get("one_way"))
            undo.undo()
            assertEquals("walkway", rel.get("subtype"))
            assertEquals("no", rel.get("one_way"))
        }
    }

    @Test
    fun existingSwappedRevertsRolesToMatchSelection() {
        val (left, right) = twoBorderWays()
        val rel = OsmFixtures.laneletRelation(right, left)
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertEquals(CreateLaneletRelation.OUTCOME_EXISTING_REVERTED, result.outcomes[0].outcome)
            val (lw, rw) = Lanelet.extractLeftRightWays(rel)
            assertSame(left, lw)
            assertSame(right, rw)
            undo.undo()
            val (lw2, rw2) = Lanelet.extractLeftRightWays(rel)
            assertSame(right, lw2)
            assertSame(left, rw2)
        }
    }

    @Test
    fun partialCompletionAddsMissingBorder() {
        val (left, right) = twoBorderWays()
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel, right)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertEquals(CreateLaneletRelation.OUTCOME_PARTIAL, result.outcomes[0].outcome)
            assertEquals("right", result.outcomes[0].role)
            val (lw, rw) = Lanelet.extractLeftRightWays(rel)
            assertSame(left, lw)
            assertSame(right, rw)
            undo.undo()
            assertEquals(1, rel.membersCount)
        }
    }

    @Test
    fun successorWaysAreSelectedWhenPresent() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val leftSucc = OsmFixtures.way(1.0 to 1.0, 2.0 to 1.0)
        val rightSucc = OsmFixtures.way(1.0 to 0.0, 2.0 to 0.0)
        leftSucc.setNodes(listOf(left.nodes.last(), leftSucc.nodes.last()))
        rightSucc.setNodes(listOf(right.nodes.last(), rightSucc.nodes.last()))
        val ds = OsmFixtures.dataSet(left, right, leftSucc, rightSucc)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertEquals(setOf(leftSucc, rightSucc), result.nextSelection.toSet())
        }
    }

    @Test
    fun foldBackCandidateIsRejectedWhenUndirected() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        // Projects back onto the left border (fold-back), 180 deg from driving dir.
        val fold = OsmFixtures.way(1.0 to 1.0, 0.5 to 1.0)
        fold.setNodes(listOf(left.nodes.last(), fold.nodes.last()))
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel, fold)
        val (ls, rs) = CreateLaneletRelation.findSuccessorWays(ds, Lanelet(rel), useDirected = false)
        assertNull(ls)
        assertNull(rs)
    }

    @Test
    fun directedSuccessorPrefersSameDirection() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val fwd = OsmFixtures.way(1.0 to 1.0, 2.0 to 1.0)
        fwd.setNodes(listOf(left.nodes.last(), fwd.nodes.last()))
        val back = OsmFixtures.way(1.0 to 1.0, 0.0 to 2.0)
        back.setNodes(listOf(left.nodes.last(), back.nodes.last()))
        val rel = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(rel, fwd, back)
        val (ls, _) = CreateLaneletRelation.findSuccessorWays(ds, Lanelet(rel), useDirected = true)
        assertSame(fwd, ls)
    }

    @Test
    fun batchEvenPairsCreatesNUndoCommands() {
        val a = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val b = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val c = OsmFixtures.way(0.0 to 3.0, 1.0 to 3.0)
        val d = OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0)
        val ds = OsmFixtures.dataSet(a, b, c, d)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applyBatch(ds, null, listOf(a, b, c, d), undo = undo)
            assertEquals(2, result.outcomes.size)
            assertTrue(result.outcomes.all { it.outcome == CreateLaneletRelation.OUTCOME_CREATED })
            assertEquals(2, undo.undoCommands.size)
            assertEquals(2, ds.relations.size)
            undo.undo()
            assertEquals(1, ds.relations.size)
            undo.undo()
            assertEquals(0, ds.relations.size)
        }
    }

    @Test
    fun parallelStripCreatesNMinusOneLanelets() {
        val left = OsmFixtures.way(0.0 to 2.0, 1.0 to 2.0)
        val mid = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ds = OsmFixtures.dataSet(left, mid, right)
        OsmFixtures.withUndo { undo ->
            val result = CreateLaneletRelation.applyParallelStrip(ds, null, listOf(left, mid, right), undo = undo)
            assertEquals(2, result.outcomes.size)
            assertEquals(2, ds.relations.size)
            assertEquals(2, undo.undoCommands.size)
        }
    }

    @Test
    fun multiMemberWarningIsRecorded() {
        val (left, right) = twoBorderWays()
        val extra = OsmFixtures.way(0.0 to 1.5, 1.0 to 1.5)
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.addMember(RelationMember("left", extra))
        val ds = OsmFixtures.dataSet(rel)
        val warnings = ArrayList<String>()
        val seen = HashSet<Long>()
        CreateLaneletRelation.findExistingLanelet(ds, left, right, warnings, seen)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("2 left"))
    }

    @Test
    fun chainIdsAccumulateOnlyCreated() {
        val (left, right) = twoBorderWays()
        val existing = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(existing)
        OsmFixtures.withUndo { undo ->
            CreateLaneletRelation.applySinglePair(ds, null, left, right, undo = undo)
            assertTrue(CreateLaneletRelation.loadChainIds().isEmpty())
        }
        val a = OsmFixtures.way(2.0 to 1.0, 3.0 to 1.0)
        val b = OsmFixtures.way(2.0 to 0.0, 3.0 to 0.0)
        val ds2 = OsmFixtures.dataSet(a, b)
        OsmFixtures.withUndo { undo ->
            val r = CreateLaneletRelation.applySinglePair(ds2, null, a, b, undo = undo)
            assertEquals(1, CreateLaneletRelation.loadChainIds().size)
            assertEquals(r.createdIds, CreateLaneletRelation.loadChainIds())
        }
    }
}
