package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/**
 * Pure scene diff engine for the 3D bridge. Tracks sent way signatures and
 * emits [OutboundMessage] snapshots/patches. The `"viewport"` overlay is
 * deliberately **not** tracked in [sent], so the way-diff never emits a
 * `remove` for it (Jython quirk).
 *
 * The hook **never sends `clear`** despite the protocol defining it; an emptied
 * layer is a [OutboundMessage.Snapshot] with `features: []`.
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
    val sent: MutableMap<String, FeatureSignature> = linkedMapOf()

    fun resetForLayerChange() {
        anchor = null
        sent.clear()
        viewportFeature = null
        cullBoundsKey = null
        forceSnapshot = true
        dirtyWays.clear()
        dirtyAll = true
    }

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
    ): OutboundMessage.Snapshot? {
        if (anchor == null) {
            anchor = Viewer3dFeatures.computeAnchor(ways)
        }
        val a = anchor
        if (a == null) {
            if (forceSnapshot || sent.isNotEmpty()) {
                sent.clear()
                forceSnapshot = false
                dirtyWays.clear()
                dirtyAll = false
                return OutboundMessage.Snapshot(null, emptyList())
            }
            return null
        }
        val bounds = if (cullEnabled) {
            Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM).also {
                cullBoundsKey = it?.let { b -> Viewer3dEnu.cullBoundsKey(b) }
            }
        } else {
            cullBoundsKey = null
            null
        }
        val current = linkedMapOf<String, Pair<ViewerFeature, FeatureSignature>>()
        for (w in ways) {
            if (w.deleted) continue
            if (!Viewer3dFeatures.wayInCull(w, a, bounds, cullEnabled)) continue
            val feat = Viewer3dFeatures.featureForWay(w, a) ?: continue
            current[feat.id] = feat to Viewer3dFeatures.featureSignature(feat)
        }
        val feats = current.values.map { it.first }.toMutableList()
        viewportFeature?.let { feats.add(it) }
        sent.clear()
        sent.putAll(current.mapValues { it.value.second })
        forceSnapshot = false
        dirtyWays.clear()
        dirtyAll = false
        return OutboundMessage.Snapshot(a, feats)
    }

    fun seedRescan(ways: List<WaySnapshot>, viewCenter: Pair<Double, Double>?): OutboundMessage.Patch? {
        val a = anchor ?: return null
        val bounds = if (cullEnabled) Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) else null
        val currentIds = linkedSetOf<Long>()
        for (w in ways) {
            if (w.deleted) continue
            if (Viewer3dFeatures.wayInCull(w, a, bounds, cullEnabled)) {
                currentIds.add(w.uniqueId)
            }
        }
        val removes = ArrayList<PatchOp.Remove>()
        for (fid in sent.keys.toList()) {
            if (fid == Viewer3dConstants.VIEWPORT_ID) continue
            val wid = fid.substringAfter("way/").toLongOrNull() ?: continue
            if (wid !in currentIds) {
                removes.add(PatchOp.Remove(fid))
                sent.remove(fid)
            }
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
            val w = waysById[wid]
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
        val patch = if (ops.isEmpty()) null else OutboundMessage.Patch(ops)
        return IncrementalResult(patch, more)
    }

    fun syncCullVisibility(
        ways: List<WaySnapshot>,
        viewCenter: Pair<Double, Double>?,
    ): OutboundMessage.Patch? {
        if (!cullEnabled) return null
        val a = anchor ?: return null
        val bounds = Viewer3dFeatures.cullBoundsEnu(viewCenter, a, cullRangeM) ?: return null
        val key = Viewer3dEnu.cullBoundsKey(bounds)
        if (cullBoundsKey == key) return null
        cullBoundsKey = key
        val visibleIds = linkedSetOf<Long>()
        for (w in ways) {
            if (w.deleted) continue
            if (Viewer3dFeatures.wayInCull(w, a, bounds, true)) visibleIds.add(w.uniqueId)
        }
        val ops = ArrayList<PatchOp>()
        for (fid in sent.keys.toList()) {
            if (fid == Viewer3dConstants.VIEWPORT_ID) continue
            val wid = fid.substringAfter("way/").toLongOrNull() ?: continue
            if (wid !in visibleIds) {
                ops.add(PatchOp.Remove(fid))
                sent.remove(fid)
            }
        }
        var batch = 0
        for (wid in visibleIds) {
            if ("way/$wid" in sent) continue
            val w = ways.firstOrNull { it.uniqueId == wid } ?: continue
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
