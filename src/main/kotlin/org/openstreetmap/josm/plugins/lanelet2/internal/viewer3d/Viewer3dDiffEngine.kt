package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/**
 * Pure scene diff engine for the 3D bridge. Tracks sent way signatures and
 * emits [OutboundMessage] snapshots/patches. The `"viewport"` overlay is
 * deliberately **not** tracked in [sent], so the way-diff never emits a
 * `remove` for it (Jython quirk).
 *
 * The hook **never sends `clear`** despite the protocol defining it; an emptied
 * layer is a [OutboundMessage.Snapshot] with `features: []`.
 *
 * A full snapshot comes in three steps so the expensive middle one can run
 * off the EDT: [beginFull] (engine state, EDT), [FullJob.build] (pure),
 * [commitFull] (engine state, EDT). [computeFull] does all three at once.
 */
class Viewer3dDiffEngine(
    var cullEnabled: Boolean = false,
    var cullRangeM: Double = Viewer3dSettings.DEFAULT_CULL_RANGE_M.toDouble(),
    var followView: Boolean = false,
) {
    var anchor: Anchor? = null
    var forceSnapshot: Boolean = true
    var viewportFeature: ViewerFeature? = null
    var cullBoundsKey: List<Double>? = null
    var dirtyAll: Boolean = true
    val dirtyWays: MutableSet<Long> = linkedSetOf()
    val dirtyLanelets: MutableSet<Long> = linkedSetOf()
    val sent: MutableMap<String, FeatureSignature> = linkedMapOf()

    /** Bumped by [resetForLayerChange]: a snapshot begun before is dropped at commit. */
    var epoch: Int = 0
        private set

    fun resetForLayerChange() {
        epoch++
        anchor = null
        sent.clear()
        viewportFeature = null
        cullBoundsKey = null
        forceSnapshot = true
        dirtyWays.clear()
        dirtyLanelets.clear()
        dirtyAll = true
    }

    fun markLaneletDirty(relationId: Long) {
        dirtyLanelets.add(relationId)
    }

    private fun wayIdOf(fid: String): Long? = if (fid.startsWith("way/")) fid.substring(4).toLongOrNull() else null

    private fun laneletIdOf(fid: String): Long? =
        if (fid.startsWith("relation/")) fid.substring(9).toLongOrNull() else null

    fun markAllDirty() {
        dirtyAll = true
    }

    fun markWayDirty(wayId: Long) {
        dirtyWays.add(wayId)
    }

    fun computeWhenDisconnected(): Boolean {
        if (forceSnapshot || sent.isEmpty() || anchor == null) {
            forceSnapshot = true
            return true
        }
        return false
    }

    fun computeFull(
        ways: List<WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
        lanelets: List<LaneletSnapshot> = emptyList(),
    ): OutboundMessage.Snapshot? {
        val job = beginFull(ways, viewCenter, lanelets)
        return commitFull(job, job.build())
    }

    /**
     * The inputs of one full snapshot, fixed on the EDT. [build] only reads
     * these immutable snapshots, so it may run on any thread.
     */
    class FullJob internal constructor(
        internal val epoch: Int,
        val anchor: Anchor?,
        private val bounds: EnuBounds?,
        private val cullEnabled: Boolean,
        private val ways: List<WaySnapshot>,
        private val lanelets: List<LaneletSnapshot>,
        internal val emptyNeeded: Boolean,
    ) {
        val size: Int get() = ways.size + lanelets.size

        fun build(): List<Pair<ViewerFeature, FeatureSignature>> {
            val a = anchor ?: return emptyList()
            val out = LinkedHashMap<String, Pair<ViewerFeature, FeatureSignature>>(ways.size + lanelets.size)
            for (w in ways) {
                if (w.deleted) continue
                if (!Viewer3dFeatures.wayInCull(w, a, bounds, cullEnabled)) continue
                val feat = Viewer3dFeatures.featureForWay(w, a) ?: continue
                out[feat.id] = feat to Viewer3dFeatures.featureSignature(feat)
            }
            // Lanelets after their bound ways, which the viewer builds surfaces from.
            for (l in lanelets) {
                if (l.deleted) continue
                if (!Viewer3dFeatures.laneletInCull(l, a, bounds, cullEnabled)) continue
                val feat = Viewer3dFeatures.featureForLanelet(l, a) ?: continue
                out[feat.id] = feat to Viewer3dFeatures.featureSignature(feat)
            }
            return out.values.toList()
        }
    }

    /**
     * Start a full snapshot of [ways] / [lanelets]: fixes the anchor and cull
     * box, and clears the dirty marks, which the snapshot covers. Marks made
     * while it builds survive the commit and go out as patches after it.
     */
    fun beginFull(
        ways: List<WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
        lanelets: List<LaneletSnapshot> = emptyList(),
    ): FullJob {
        if (anchor == null) {
            anchor = Viewer3dFeatures.computeAnchor(ways)
        }
        val a = anchor
        val bounds = if (cullEnabled && a != null) {
            Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM).also {
                cullBoundsKey = it?.let { b -> Viewer3dEnu.cullBoundsKey(b) }
            }
        } else {
            cullBoundsKey = null
            null
        }
        val emptyNeeded = forceSnapshot || sent.isNotEmpty()
        forceSnapshot = false
        dirtyWays.clear()
        dirtyLanelets.clear()
        dirtyAll = false
        return FullJob(epoch, a, bounds, cullEnabled, ways, lanelets, emptyNeeded)
    }

    /**
     * Adopt a built snapshot: what was sent is now exactly [built]. Null when
     * the layer changed since [beginFull] (the job is stale) or when there is
     * no map and nothing to clear.
     */
    fun commitFull(job: FullJob, built: List<Pair<ViewerFeature, FeatureSignature>>): OutboundMessage.Snapshot? {
        if (job.epoch != epoch) return null
        val a = job.anchor
        if (a == null) {
            if (!job.emptyNeeded) return null
            sent.clear()
            return OutboundMessage.Snapshot(null, emptyList())
        }
        val feats = built.mapTo(ArrayList(built.size + 1)) { it.first }
        viewportFeature?.let { feats.add(it) }
        sent.clear()
        for ((f, sig) in built) sent[f.id] = sig
        return OutboundMessage.Snapshot(a, feats)
    }

    fun seedRescan(
        ways: List<WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
        lanelets: List<LaneletSnapshot> = emptyList(),
    ): OutboundMessage.Patch? {
        val a = anchor ?: return null
        val bounds = if (cullEnabled) Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) else null
        val currentIds = linkedSetOf<Long>()
        for (w in ways) {
            if (w.deleted) continue
            if (Viewer3dFeatures.wayInCull(w, a, bounds, cullEnabled)) {
                currentIds.add(w.uniqueId)
            }
        }
        val currentLanelets = linkedSetOf<Long>()
        for (l in lanelets) {
            if (!l.deleted && Viewer3dFeatures.laneletInCull(l, a, bounds, cullEnabled)) currentLanelets.add(l.uniqueId)
        }
        val removes = ArrayList<PatchOp.Remove>()
        for (fid in sent.keys.toList()) {
            val wid = wayIdOf(fid)
            val lid = laneletIdOf(fid)
            val gone = (wid != null && wid !in currentIds) || (lid != null && lid !in currentLanelets)
            if (gone) {
                removes.add(PatchOp.Remove(fid))
                sent.remove(fid)
            }
        }
        for (lid in currentLanelets) {
            // New ones get sent; known ones are re-checked (cheap, tags may have changed).
            dirtyLanelets.add(lid)
        }
        var nAdd = 0
        for (wid in currentIds) {
            if ("way/$wid" !in sent) {
                dirtyWays.add(wid)
                nAdd++
            }
        }
        val reverify = removes.isEmpty() && nAdd == 0
        if (reverify) {
            dirtyWays.addAll(currentIds)
        }
        dirtyAll = false
        return if (removes.isEmpty()) null else OutboundMessage.Patch(removes)
    }

    fun computeIncremental(
        waysById: Map<Long, WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
    ): IncrementalResult = computeIncremental({ waysById[it] }, viewCenter)

    /**
     * Diff the dirty ways (at most [Viewer3dConstants.MAX_WAYS_PER_CYCLE]).
     * [wayById] is asked only for those, so one edit costs its own ways rather
     * than a snapshot of the whole dataset.
     */
    fun computeIncremental(
        wayById: (Long) -> WaySnapshot?,
        viewCenter: Pair<Double, Double>?,
        laneletById: (Long) -> LaneletSnapshot? = { null },
    ): IncrementalResult {
        val a = anchor ?: return IncrementalResult(null, false)
        val bounds = if (cullEnabled) Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) else null
        val batch = ArrayList<Long>()
        for (wid in dirtyWays) {
            batch.add(wid)
            if (batch.size >= Viewer3dConstants.MAX_WAYS_PER_CYCLE) break
        }
        for (wid in batch) dirtyWays.remove(wid)
        val more = dirtyWays.isNotEmpty()
        val ops = ArrayList<PatchOp>()
        for (wid in batch) {
            val fid = "way/$wid"
            val w = wayById(wid)
            if (w == null || w.deleted) {
                if (fid in sent) {
                    ops.add(PatchOp.Remove(fid))
                    sent.remove(fid)
                }
                continue
            }
            if (!Viewer3dFeatures.wayInCull(w, a, bounds, cullEnabled)) {
                if (fid in sent) {
                    ops.add(PatchOp.Remove(fid))
                    sent.remove(fid)
                }
                continue
            }
            val feat = Viewer3dFeatures.featureForWay(w, a)
            if (feat == null) {
                if (fid in sent) {
                    ops.add(PatchOp.Remove(fid))
                    sent.remove(fid)
                }
                continue
            }
            val sig = Viewer3dFeatures.featureSignature(feat)
            if (sent[fid] != sig) {
                ops.add(PatchOp.Upsert(feat))
                sent[fid] = sig
            }
        }
        // Lanelets after ways, with their own batch.
        val lbatch = dirtyLanelets.take(Viewer3dConstants.MAX_WAYS_PER_CYCLE)
        lbatch.forEach { dirtyLanelets.remove(it) }
        for (lid in lbatch) {
            val fid = "relation/$lid"
            val l = laneletById(lid)
            val feat = if (l == null || l.deleted || !Viewer3dFeatures.laneletInCull(l, a, bounds, cullEnabled)) {
                null
            } else {
                Viewer3dFeatures.featureForLanelet(l, a)
            }
            if (feat == null) {
                if (sent.remove(fid) != null) ops.add(PatchOp.Remove(fid))
                continue
            }
            val sig = Viewer3dFeatures.featureSignature(feat)
            if (sent[fid] != sig) {
                ops.add(PatchOp.Upsert(feat))
                sent[fid] = sig
            }
        }
        val patch = if (ops.isEmpty()) null else OutboundMessage.Patch(ops)
        return IncrementalResult(patch, more || dirtyLanelets.isNotEmpty())
    }

    fun syncCullVisibility(
        ways: List<WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
        lanelets: List<LaneletSnapshot> = emptyList(),
    ): OutboundMessage.Patch? {
        if (!cullEnabled) return null
        val a = anchor ?: return null
        val bounds = Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) ?: return null
        val key = Viewer3dEnu.cullBoundsKey(bounds)
        if (cullBoundsKey == key) return null
        cullBoundsKey = key
        val visible = linkedMapOf<Long, WaySnapshot>()
        for (w in ways) {
            if (w.deleted) continue
            if (Viewer3dFeatures.wayInCull(w, a, bounds, true)) visible[w.uniqueId] = w
        }
        val visibleIds = visible.keys
        val visibleLanelets = lanelets
            .filter { !it.deleted && Viewer3dFeatures.laneletInCull(it, a, bounds, true) }
            .associateBy { it.uniqueId }
        val ops = ArrayList<PatchOp>()
        for (fid in sent.keys.toList()) {
            val wid = wayIdOf(fid)
            val lid = laneletIdOf(fid)
            if ((wid != null && wid !in visibleIds) || (lid != null && lid !in visibleLanelets)) {
                ops.add(PatchOp.Remove(fid))
                sent.remove(fid)
            }
        }
        var batch = 0
        for (wid in visibleIds) {
            if ("way/$wid" in sent) continue
            val w = visible[wid] ?: continue
            val feat = Viewer3dFeatures.featureForWay(w, a) ?: continue
            val sig = Viewer3dFeatures.featureSignature(feat)
            ops.add(PatchOp.Upsert(feat))
            sent["way/$wid"] = sig
            batch++
            if (batch >= Viewer3dConstants.MAX_WAYS_PER_CYCLE) break
        }
        for (wid in visibleIds) {
            if ("way/$wid" !in sent) dirtyWays.add(wid)
        }
        for (lid in visibleLanelets.keys) {
            if ("relation/$lid" !in sent) dirtyLanelets.add(lid)
        }
        return if (ops.isEmpty()) null else OutboundMessage.Patch(ops)
    }

    fun viewportPatch(
        viewBounds: ViewBounds?,
        viewCenter: Pair<Double, Double>?,
        followCamera: Boolean,
    ): OutboundMessage.Patch? {
        val a = anchor ?: return null
        val viewCenterEnu = viewCenterEnu(viewCenter, a)
        val feat = when {
            cullEnabled -> {
                val bounds = Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) ?: return null
                Viewer3dFeatures.finalizeViewportFeature(
                    Viewer3dFeatures.featureFromEnuBounds(bounds),
                    viewCenterEnu,
                    followCamera,
                )
            }
            viewBounds != null -> Viewer3dFeatures.buildViewportFromMapBounds(
                viewBounds, a, viewCenterEnu, followCamera,
            )
            else -> return null
        }
        viewportFeature = feat
        return OutboundMessage.Patch(listOf(PatchOp.Upsert(feat)))
    }

    private fun viewCenterEnu(viewCenter: Pair<Double, Double>?, anchor: Anchor): EnuPoint? {
        if (cullEnabled) {
            val bounds = Viewer3dFeatures.cullBoundsEnu(viewCenter, anchor, cullRangeM) ?: return null
            return EnuPoint(
                (bounds.minX + bounds.maxX) / 2.0,
                (bounds.minY + bounds.maxY) / 2.0,
                0.0,
            )
        }
        if (viewCenter == null) return null
        val (x, y) = Viewer3dEnu.enu(viewCenter.first, viewCenter.second, anchor.lat, anchor.lon)
        return EnuPoint(x, y, 0.0)
    }

    data class IncrementalResult(val patch: OutboundMessage.Patch?, val morePending: Boolean)
}
