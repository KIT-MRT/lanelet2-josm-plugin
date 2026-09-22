package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.coor.LatLon
import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.Relation
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

/** Lanelets for the viewer: direction from lanelet2 alignment, arrow on the centerline. */
class Viewer3dLaneletTest {
    private val anchor = Anchor(49.0, 8.4)
    private lateinit var ds: DataSet

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
        ds = DataSet()
    }

    /** Way along x (east) at [y] metres north, from x0 to x1 in 4 steps, at height [z]. */
    private fun bound(y: Double, x0: Double, x1: Double, z: Double = 100.0): Way {
        val nodes = (0..4).map { i ->
            val x = x0 + (x1 - x0) * i / 4.0
            val (lat, lon) = Viewer3dEnu.enuToLatLon(x, y, anchor.lat, anchor.lon)
            Node(LatLon(lat, lon)).also {
                it.put("ele", z.toString())
                ds.addPrimitive(it)
            }
        }
        return Way().also {
            it.setNodes(nodes)
            ds.addPrimitive(it)
        }
    }

    private fun lanelet(left: Way, right: Way, oneWay: String? = null) = Relation().also {
        it.put("type", "lanelet")
        it.put("subtype", "road")
        oneWay?.let { v -> it.put("one_way", v) }
        it.addMember(RelationMember("left", left))
        it.addMember(RelationMember("right", right))
        ds.addPrimitive(it)
    }

    @Test
    fun twoWayFollowsLanelet2BoolParsing() {
        for (v in listOf("no", "false", "0")) assertTrue(Viewer3dFeatures.isTwoWay(v), v)
        for (v in listOf(null, "yes", "true", "1", "maybe")) assertFalse(Viewer3dFeatures.isTwoWay(v), "$v")
    }

    @Test
    fun participantOverridesFollowLanelet2() {
        val car = Viewer3dFeatures.ARROW_PARTICIPANT
        fun two(vararg t: Pair<String, String>, who: String = car) = Viewer3dFeatures.isTwoWayFor(mapOf(*t), who)
        // one_way decides whenever it is a bool; overrides are then ignored.
        assertFalse(two("one_way" to "yes", "one_way:vehicle:car" to "no"))
        // Without it, the override whose key is a prefix of one_way:<participant>.
        assertTrue(two("one_way:vehicle" to "no"))
        assertTrue(two("one_way:vehicle:car" to "no"))
        assertFalse(two("one_way:vehicle:bus" to "no"))
        assertTrue(two("one_way:bicycle" to "no", who = "bicycle"))
        assertFalse(two("one_way:bicycle" to "no"))
        // Nothing at all: only pedestrians walk both ways.
        assertFalse(two())
        assertTrue(two(who = "pedestrian"))

        assertEquals(listOf("bicycle"), Viewer3dFeatures.otherWayParticipants(mapOf("one_way:bicycle" to "no")))
        assertEquals(emptyList<String>(), Viewer3dFeatures.otherWayParticipants(mapOf("one_way" to "yes", "one_way:bicycle" to "no")))
        assertEquals(emptyList<String>(), Viewer3dFeatures.otherWayParticipants(mapOf("one_way:vehicle" to "no")))
    }

    @Test
    fun anOverrideReachesTheViewer() {
        val rel = lanelet(bound(1.75, 0.0, 100.0), bound(-1.75, 0.0, 100.0))
        rel.put("one_way:bicycle", "no")
        val feat = Viewer3dFeatures.featureForLanelet(rel.toLaneletSnapshot()!!, anchor)!!
        assertFalse(feat.lanelet!!.twoWay)
        assertEquals(listOf("bicycle"), feat.lanelet!!.otherWay)
        assertEquals("no", feat.tags["one_way:bicycle"])
        assertTrue(Viewer3dJson.encode(OutboundMessage.Patch(listOf(PatchOp.Upsert(feat)))).contains("\"owx\":[\"bicycle\"]"))
    }

    @Test
    fun arrowSitsAt35PercentOfTheCenterlinePointingDownTheLane() {
        val rel = lanelet(bound(1.75, 0.0, 100.0), bound(-1.75, 0.0, 100.0))
        val feat = Viewer3dFeatures.featureForLanelet(rel.toLaneletSnapshot()!!, anchor)!!
        val a = feat.lanelet!!.arrow!!
        assertEquals(35.0, a[0], 0.05)
        assertEquals(0.0, a[1], 0.05)
        assertEquals(100.0, a[2], 1e-6)
        assertEquals(1.0, a[3], 1e-3) // +x
        assertEquals(0.0, a[4], 1e-3)
        assertEquals(3.5, a[6], 0.01) // lane width
        assertFalse(feat.lanelet!!.twoWay)
        assertEquals("relation/${rel.uniqueId}", feat.id)
        assertEquals("lanelet", feat.kind)
    }

    @Test
    fun boundsStoredBackwardsAreAlignedLikeLanelet2() {
        // Both ways run west, but "left" lies north: lanelet2 aligns both
        // bounds, so the lane still runs east.
        val rel = lanelet(bound(1.75, 100.0, 0.0), bound(-1.75, 100.0, 0.0), oneWay = "no")
        val snap = rel.toLaneletSnapshot()!!
        assertTrue(snap.leftReversed && snap.rightReversed)
        val l = Viewer3dFeatures.featureForLanelet(snap, anchor)!!.lanelet!!
        assertEquals(1.0, l.arrow!![3], 1e-3)
        assertEquals(35.0, l.arrow!![0], 0.05)
        assertTrue(l.twoWay)
    }

    @Test
    fun slopeComesFromTheBoundHeights() {
        val left = bound(1.75, 0.0, 100.0)
        val right = bound(-1.75, 0.0, 100.0)
        // Raise both bounds linearly by 10 m over 100 m.
        for (w in listOf(left, right)) {
            w.nodes.forEachIndexed { i, n -> n.put("ele", (100.0 + 2.5 * i).toString()) }
        }
        val a = Viewer3dFeatures.featureForLanelet(lanelet(left, right).toLaneletSnapshot()!!, anchor)!!.lanelet!!.arrow!!
        assertEquals(103.5, a[2], 0.01)
        assertEquals(0.1 / Math.sqrt(1.01), a[5], 1e-3)
    }

    @Test
    fun encodesReferencesAndArrow() {
        val rel = lanelet(bound(1.75, 0.0, 100.0), bound(-1.75, 0.0, 100.0))
        val json = Viewer3dJson.encode(OutboundMessage.Patch(listOf(
            PatchOp.Upsert(Viewer3dFeatures.featureForLanelet(rel.toLaneletSnapshot()!!, anchor)!!),
        )))
        assertTrue(json.contains("\"kind\":\"lanelet\""), json)
        assertTrue(json.contains("\"lrev\":false,\"rrev\":false,\"two\":false,\"arrow\":[35,"), json)
    }

    @Test
    fun engineStreamsLaneletsAfterTheirWaysAndDropsDeletedOnes() {
        val left = bound(1.75, 0.0, 100.0)
        val right = bound(-1.75, 0.0, 100.0)
        val rel = lanelet(left, right)
        val engine = Viewer3dDiffEngine()
        val snap = engine.computeFull(
            listOf(left, right).map { it.toSnapshot() }, null, listOf(rel.toLaneletSnapshot()!!),
        )!!
        assertEquals(listOf("way/${left.uniqueId}", "way/${right.uniqueId}", "relation/${rel.uniqueId}"), snap.features.map { it.id })

        rel.put("one_way", "no")
        engine.markLaneletDirty(rel.uniqueId)
        val up = engine.computeIncremental({ null }, null, { rel.toLaneletSnapshot() }).patch!!
        assertTrue((up.ops.single() as PatchOp.Upsert).feature.lanelet!!.twoWay)

        rel.setDeleted(true)
        engine.markLaneletDirty(rel.uniqueId)
        val gone = engine.computeIncremental({ null }, null, { rel.toLaneletSnapshot() }).patch
        assertNotNull(gone)
        assertTrue(gone!!.ops.single() is PatchOp.Remove)
    }
}
