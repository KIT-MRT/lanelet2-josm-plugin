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
            pts = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
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
        engine.sent["way/1"] = FeatureSignature(LongArray(0), DoubleArray(0), null, null, null)
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
        engine.sent["way/1"] = FeatureSignature(longArrayOf(0), doubleArrayOf(0.0, 0.0, 0.0), null, null, null)
        engine.markWayDirty(1)
        val patch = engine.computeIncremental(mapOf(1L to way(1).copy(deleted = true)), null).patch!!
        assertEquals(1, patch.ops.size)
        assertTrue(patch.ops[0] is PatchOp.Remove)
        assertFalse("way/1" in engine.sent)
    }

    @Test
    fun aSnapshotBegunBeforeALayerChangeIsDropped() {
        val engine = Viewer3dDiffEngine()
        val job = engine.beginFull(listOf(way(1, 8.4 to 49.0, 8.401 to 49.001)), null)
        val built = job.build()
        engine.resetForLayerChange()
        assertNull(engine.commitFull(job, built))
        assertTrue(engine.sent.isEmpty())
        assertTrue(engine.forceSnapshot)
    }

    @Test
    fun changesDuringABuildSurviveItsCommit() {
        val engine = Viewer3dDiffEngine()
        val job = engine.beginFull(listOf(way(1, 8.4 to 49.0, 8.401 to 49.001)), null)
        assertFalse(engine.forceSnapshot)
        engine.markWayDirty(1) // an edit while the worker builds
        engine.forceSnapshot = true // a reconnect while the worker builds
        val snap = engine.commitFull(job, job.build())!!
        assertEquals(listOf("way/1"), snap.features.map { it.id })
        assertTrue("way/1" in engine.sent)
        assertEquals(setOf(1L), engine.dirtyWays) // goes out as a patch next
        assertTrue(engine.forceSnapshot) // and another snapshot follows
    }

    @Test
    fun theCommitCarriesTheLatestViewport() {
        val engine = Viewer3dDiffEngine()
        val job = engine.beginFull(listOf(way(1, 8.4 to 49.0, 8.401 to 49.001)), null)
        engine.viewportFeature = ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            pts = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        )
        val snap = engine.commitFull(job, job.build())!!
        assertEquals(listOf("way/1", Viewer3dConstants.VIEWPORT_ID), snap.features.map { it.id })
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
    fun incrementalReadsOnlyTheDirtyWays() {
        val engine = Viewer3dDiffEngine()
        engine.anchor = anchor
        engine.forceSnapshot = false
        engine.markWayDirty(7)
        val asked = mutableListOf<Long>()
        val result = engine.computeIncremental({ id ->
            asked.add(id)
            way(id, 8.4 to 49.0, 8.401 to 49.001)
        }, null)
        assertEquals(listOf(7L), asked)
        val feat = (result.patch!!.ops.single() as PatchOp.Upsert).feature
        assertEquals("way/7", feat.id)
        assertEquals(2, feat.vertexCount)
        assertEquals(listOf(0L, 1L), feat.nodeIds.toList())
    }

    @Test
    fun unchangedWayIsNotResent() {
        val engine = Viewer3dDiffEngine()
        engine.anchor = anchor
        engine.forceSnapshot = false
        val w = way(3, 8.4 to 49.0, 8.401 to 49.001)
        engine.markWayDirty(3)
        assertEquals(1, engine.computeIncremental({ w }, null).patch!!.ops.size)
        engine.markWayDirty(3)
        assertNull(engine.computeIncremental({ way(3, 8.4 to 49.0, 8.401 to 49.001) }, null).patch)
    }

    @Test
    fun cullSyncAddsVisibleWaysAndDropsTheRest() {
        val engine = Viewer3dDiffEngine(cullEnabled = true, cullRangeM = 200.0)
        engine.anchor = anchor
        engine.forceSnapshot = false
        engine.sent["way/2"] = FeatureSignature(LongArray(0), DoubleArray(0), null, null, null)
        val near = way(1, 8.4 to 49.0, 8.4001 to 49.0001)
        val far = way(2, 8.5 to 49.1, 8.5001 to 49.1001)
        val patch = engine.syncCullVisibility(listOf(near, far), 49.0 to 8.4)!!
        assertTrue(patch.ops.any { it is PatchOp.Upsert && it.feature.id == "way/1" })
        assertTrue(patch.ops.any { it is PatchOp.Remove && it.id == "way/2" })
    }

    @Test
    fun latLonBoxContainsTheCullSquare() {
        val bounds = EnuBounds(-150.0, -150.0, 150.0, 150.0)
        val box = Viewer3dFeatures.latLonBoxOf(bounds, anchor)
        for ((x, y) in listOf(-150.0 to -150.0, 150.0 to 150.0, -150.0 to 150.0, 150.0 to -150.0)) {
            val (lat, lon) = Viewer3dEnu.enuToLatLon(x, y, anchor.lat, anchor.lon)
            assertTrue(lon > box[0] && lat > box[1] && lon < box[2] && lat < box[3], "$x,$y outside")
        }
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
