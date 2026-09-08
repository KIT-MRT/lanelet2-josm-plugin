package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Viewer3dDiffTest {
    private val anchor = Anchor(49.0, 8.4)

    private fun way(id: Long, vararg lonLat: Pair<Double, Double>): WaySnapshot {
        val nodes = lonLat.mapIndexed { i, (lon, lat) ->
            NodeSnapshot(i.toLong(), lat, lon, null)
        }
        return WaySnapshot(id, false, nodes, "line_thin", "solid", null)
    }

    @Test
    fun snapshotIncludesViewportButDoesNotTrackItInSent() {
        val engine = Viewer3dDiffEngine()
        engine.viewportFeature = ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            points = listOf(listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0)),
        )
        val snap = engine.computeFull(listOf(way(1, 8.4 to 49.0, 8.401 to 49.001)), null)!!
        assertTrue(snap.features.any { it.id == Viewer3dConstants.VIEWPORT_ID })
        assertFalse(Viewer3dConstants.VIEWPORT_ID in engine.sent)
        assertTrue("way/1" in engine.sent)
    }

    @Test
    fun emptyLayerSendsSnapshotWithEmptyFeaturesNotClear() {
        val engine = Viewer3dDiffEngine()
        engine.forceSnapshot = true
        engine.sent["way/1"] = FeatureSignature(emptyList(), emptyList(), null, null, null)
        val snap = engine.computeFull(emptyList(), null)!!
        assertNull(snap.anchor)
        assertTrue(snap.features.isEmpty())
        assertTrue(engine.sent.isEmpty())
    }

    @Test
    fun incrementalRemoveWhenWayDeleted() {
        val engine = Viewer3dDiffEngine()
        engine.anchor = anchor
        engine.forceSnapshot = false
        engine.sent["way/1"] = FeatureSignature(listOf("node/0"), listOf(Triple(0.0, 0.0, 0.0)), null, null, null)
        engine.markWayDirty(1)
        val patch = engine.computeIncremental(mapOf(1L to way(1).copy(deleted = true)), null).patch!!
        assertEquals(1, patch.ops.size)
        assertTrue(patch.ops[0] is PatchOp.Remove)
        assertFalse("way/1" in engine.sent)
    }

    @Test
    fun maxWaysPerCycleIs999() {
        assertEquals(999, Viewer3dConstants.MAX_WAYS_PER_CYCLE)
        val engine = Viewer3dDiffEngine()
        engine.anchor = anchor
        engine.forceSnapshot = false
        for (i in 1..1000) engine.markWayDirty(i.toLong())
        val ways = (1..1000).associate { id ->
            id.toLong() to way(id.toLong(), 8.4 to 49.0, 8.401 to 49.001)
        }
        val result = engine.computeIncremental(ways, null)
        assertEquals(999, result.patch?.ops?.size ?: 999)
        assertTrue(result.morePending)
    }

    @Test
    fun viewportPatchDoesNotAddToSent() {
        val engine = Viewer3dDiffEngine()
        engine.anchor = anchor
        val patch = engine.viewportPatch(
            ViewBounds(48.9, 49.1, 8.3, 8.5),
            49.0 to 8.4,
            followCamera = true,
        )!!
        val feat = (patch.ops.single() as PatchOp.Upsert).feature
        assertEquals(Viewer3dConstants.VIEWPORT_ID, feat.id)
        assertEquals("1", feat.tags["follow_camera"])
        assertFalse(Viewer3dConstants.VIEWPORT_ID in engine.sent)
    }
}
