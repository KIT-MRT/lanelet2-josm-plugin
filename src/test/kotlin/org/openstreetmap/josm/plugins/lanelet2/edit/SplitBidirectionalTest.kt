package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.lanelet2.infra.Lanelet
import org.openstreetmap.josm.plugins.lanelet2.infra.OsmFixtures
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class SplitBidirectionalTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    private fun bidirLanelet(left: Way, right: Way): Relation {
        val rel = OsmFixtures.laneletRelation(left, right)
        rel.put("one_way", "no")
        return rel
    }

    @Test
    fun isBidirectionalAcceptsNoAndZeroOnly() {
        val rel = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        assertFalse(SplitBidirectional.isBidirectional(rel))
        rel.put("one_way", "no")
        assertTrue(SplitBidirectional.isBidirectional(rel))
        rel.put("one_way", "0")
        assertTrue(SplitBidirectional.isBidirectional(rel))
        rel.put("one_way", "yes")
        assertFalse(SplitBidirectional.isBidirectional(rel))
        rel.put("one_way", "NO")
        assertTrue(SplitBidirectional.isBidirectional(rel))
    }

    @Test
    fun splitCreatesTwoOneWayLaneletsAndUndoRestores() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val old = bidirLanelet(left, right)
        val ds = OsmFixtures.dataSet(old)
        OsmFixtures.withUndo { undo ->
            val plan = SplitBidirectional.apply(ds, listOf(old), undo = undo)
            assertTrue(plan is SplitBidirectional.Plan.Ready)
            val ready = plan as SplitBidirectional.Plan.Ready
            assertEquals(1, ready.deleted)
            assertEquals(2, ready.created)
            assertTrue(old.isDeleted)
            assertEquals(1, undo.undoCommands.size)
            val news = ds.relations.filter { !it.isDeleted }
            assertEquals(2, news.size)
            assertTrue(news.all { it.get("one_way") == "yes" })
            assertTrue(news.all { it.get("type") == "lanelet" })
            val virtuals = ds.ways.filter { !it.isDeleted && it.get("type") == "virtual" }
            assertEquals(1, virtuals.size)
            for (n in news) {
                val (lw, rw) = Lanelet.extractLeftRightWays(n)
                assertSame(virtuals[0], lw)
                assertTrue(rw === left || rw === right)
            }
            undo.undo()
            assertFalse(old.isDeleted)
            assertTrue(ds.relations.none { it !== old && !it.isDeleted })
        }
    }

    @Test
    fun regulatoryMembersAreCopiedToBothNewLanelets() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val old = bidirLanelet(left, right)
        val reg = OsmFixtures.relation("regulatory_element", "traffic_light")
        old.addMember(RelationMember("regulatory_element", reg))
        val ds = OsmFixtures.dataSet(old, reg)
        OsmFixtures.withUndo { undo ->
            SplitBidirectional.apply(ds, listOf(old), undo = undo)
            val news = ds.relations.filter { !it.isDeleted && it.get("type") == "lanelet" }
            assertEquals(2, news.size)
            for (n in news) {
                assertTrue(n.members.any { it.role == "regulatory_element" && it.member === reg })
            }
        }
    }

    @Test
    fun referrerIsExpandedToBothNewLanelets() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val old = bidirLanelet(left, right)
        val row = OsmFixtures.relation("regulatory_element", "right_of_way")
        row.addMember(RelationMember("yield", old))
        val ds = OsmFixtures.dataSet(old, row)
        OsmFixtures.withUndo { undo ->
            SplitBidirectional.apply(ds, listOf(old), undo = undo)
            val yieldMembers = row.members.filter { it.role == "yield" }.map { it.member }
            assertEquals(2, yieldMembers.size)
            assertTrue(yieldMembers.all { it is Relation && !it.isDeleted })
            undo.undo()
            assertEquals(1, row.members.count { it.role == "yield" })
            assertSame(old, row.members.first { it.role == "yield" }.member)
        }
    }

    @Test
    fun chainedLaneletsReuseJunctionNode() {
        val l0 = OsmFixtures.node(0.0, 1.0)
        val l1 = OsmFixtures.node(1.0, 1.0)
        val l2 = OsmFixtures.node(2.0, 1.0)
        val r0 = OsmFixtures.node(0.0, 0.0)
        val r1 = OsmFixtures.node(1.0, 0.0)
        val r2 = OsmFixtures.node(2.0, 0.0)
        val leftA = wayOf(l0, l1)
        val rightA = wayOf(r0, r1)
        val leftB = wayOf(l1, l2)
        val rightB = wayOf(r1, r2)
        val a = bidirLanelet(leftA, rightA)
        val b = bidirLanelet(leftB, rightB)
        val ds = OsmFixtures.dataSet(a, b)
        OsmFixtures.withUndo { undo ->
            val plan = SplitBidirectional.apply(ds, listOf(a, b), undo = undo) as SplitBidirectional.Plan.Ready
            assertEquals(1, plan.sharedKeys)
            val virtuals = ds.ways.filter { !it.isDeleted && it.get("type") == "virtual" }
            assertEquals(2, virtuals.size)
            val ends = virtuals.map { it.nodes.last().uniqueId }.toSet() +
                virtuals.map { it.nodes.first().uniqueId }.toSet()
            val shared = virtuals[0].nodes.last()
            val otherStartOrEnd = virtuals[1].nodes.first()
            val otherEnd = virtuals[1].nodes.last()
            val reused = virtuals[0].nodes.any { n -> virtuals[1].nodes.any { it === n } }
            assertTrue(reused, "chain junction should reuse one Node object")
            assertTrue(ends.size < virtuals.sumOf { it.nodesCount })
            assertTrue(shared === otherStartOrEnd || virtuals[0].nodes.first() === otherEnd || reused)
        }
    }

    @Test
    fun missingBoundsAbortsWithoutMutating() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val rel = Relation()
        rel.put("type", "lanelet")
        rel.put("one_way", "no")
        rel.addMember(RelationMember("left", left))
        val ds = OsmFixtures.dataSet(rel)
        OsmFixtures.withUndo { undo ->
            val plan = SplitBidirectional.apply(ds, listOf(rel), undo = undo)
            assertTrue(plan is SplitBidirectional.Plan.Abort)
            assertTrue(undo.undoCommands.isEmpty())
            assertFalse(rel.isDeleted)
        }
    }

    @Test
    fun oneWayYesIsNotCollected() {
        val rel = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        rel.put("one_way", "yes")
        val ds = OsmFixtures.dataSet(rel)
        ds.setSelected(rel)
        val bidir = SplitBidirectional.collectBidirectional(ds, ds.selected)
        assertTrue(bidir.isEmpty())
    }

    private fun wayOf(vararg nodes: Node): Way {
        val w = Way()
        w.setNodes(nodes.toList())
        return w
    }
}
