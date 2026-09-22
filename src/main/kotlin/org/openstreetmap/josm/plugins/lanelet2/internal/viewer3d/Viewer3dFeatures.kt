package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/** Build viewer features and signatures from headless way snapshots. */
object Viewer3dFeatures {
    fun nodeEleMetres(eleTag: String?): Double {
        if (eleTag.isNullOrEmpty()) return 0.0
        return eleTag.toDoubleOrNull() ?: 0.0
    }

    fun featureForWay(way: WaySnapshot, anchor: Anchor): ViewerFeature? {
        val n = way.nodes.size
        var pts = DoubleArray(n * 3)
        var nodeIds = LongArray(n)
        var k = 0
        for (node in way.nodes) {
            val lat = node.lat ?: continue
            val lon = node.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            pts[k * 3] = Viewer3dEnu.roundCoord(x)
            pts[k * 3 + 1] = Viewer3dEnu.roundCoord(y)
            pts[k * 3 + 2] = Viewer3dEnu.roundCoord(nodeEleMetres(node.eleTag))
            nodeIds[k] = node.uniqueId
            k++
        }
        if (k < 2) return null
        if (k < n) {
            pts = pts.copyOf(k * 3)
            nodeIds = nodeIds.copyOf(k)
        }
        val tags = linkedMapOf<String, String>()
        way.type?.let { tags["type"] = it }
        way.subtype?.let { tags["subtype"] = it }
        way.participantBicycle?.let { tags["participant:bicycle"] = it }
        return ViewerFeature(
            id = "way/${way.uniqueId}",
            kind = "line",
            tags = tags,
            pts = pts,
            nodeIds = nodeIds,
        )
    }

    fun featureSignature(feat: ViewerFeature): FeatureSignature =
        FeatureSignature(
            nodeIds = feat.nodeIds,
            pts = feat.pts,
            type = feat.tags["type"],
            subtype = feat.tags["subtype"],
            participantBicycle = feat.tags["participant:bicycle"],
        )

    fun wayBBoxEnu(way: WaySnapshot, anchor: Anchor): EnuBounds? {
        var wminX = 1.0e18
        var wminY = 1.0e18
        var wmaxX = -1.0e18
        var wmaxY = -1.0e18
        var found = false
        for (n in way.nodes) {
            val lat = n.lat ?: continue
            val lon = n.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            found = true
            if (x < wminX) wminX = x
            if (x > wmaxX) wmaxX = x
            if (y < wminY) wminY = y
            if (y > wmaxY) wmaxY = y
        }
        if (!found) return null
        return EnuBounds(wminX, wminY, wmaxX, wmaxY)
    }

    fun wayInCull(way: WaySnapshot, anchor: Anchor, bounds: EnuBounds?, cullOn: Boolean): Boolean {
        if (!cullOn) return true
        if (bounds == null) return false
        val wb = wayBBoxEnu(way, anchor) ?: return false
        return bounds.intersects(wb)
    }

    fun computeAnchor(ways: Collection<WaySnapshot>): Anchor? {
        var minLat = 1.0e9
        var maxLat = -1.0e9
        var minLon = 1.0e9
        var maxLon = -1.0e9
        var found = false
        for (w in ways) {
            for (n in w.nodes) {
                val lat = n.lat ?: continue
                val lon = n.lon ?: continue
                found = true
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
            }
        }
        if (!found) return null
        return Anchor((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    }

    fun cullBoundsEnu(viewCenter: Pair<Double, Double>?, anchor: Anchor, rangeM: Double): EnuBounds? {
        if (viewCenter == null) return null
        val (cx, cy) = Viewer3dEnu.enu(viewCenter.first, viewCenter.second, anchor.lat, anchor.lon)
        val half = rangeM / 2.0
        return EnuBounds(cx - half, cy - half, cx + half, cy + half)
    }

    fun featureFromEnuBounds(bounds: EnuBounds): ViewerFeature {
        val corners = listOf(
            bounds.minX to bounds.minY,
            bounds.minX to bounds.maxY,
            bounds.maxX to bounds.maxY,
            bounds.maxX to bounds.minY,
            bounds.minX to bounds.minY,
        )
        return ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            pts = floorLoop(corners),
        )
    }

    /** Closed loop on the floor (z = 0), rounded like every streamed coordinate. */
    private fun floorLoop(corners: List<Pair<Double, Double>>): DoubleArray {
        val pts = DoubleArray(corners.size * 3)
        corners.forEachIndexed { i, (x, y) ->
            pts[i * 3] = Viewer3dEnu.roundCoord(x)
            pts[i * 3 + 1] = Viewer3dEnu.roundCoord(y)
        }
        return pts
    }

    /**
     * Lat/lon box (minLon, minLat, maxLon, maxLat) that contains [bounds], for
     * JOSM's spatial index. Slightly generous; callers still apply the exact
     * ENU test ([wayInCull]).
     */
    fun latLonBoxOf(bounds: EnuBounds, anchor: Anchor, marginM: Double = 1.0): DoubleArray {
        val (lat0, lon0) = Viewer3dEnu.enuToLatLon(bounds.minX - marginM, bounds.minY - marginM, anchor.lat, anchor.lon)
        val (lat1, lon1) = Viewer3dEnu.enuToLatLon(bounds.maxX + marginM, bounds.maxY + marginM, anchor.lat, anchor.lon)
        return doubleArrayOf(minOf(lon0, lon1), minOf(lat0, lat1), maxOf(lon0, lon1), maxOf(lat0, lat1))
    }

    fun finalizeViewportFeature(
        feat: ViewerFeature,
        viewCenterEnu: EnuPoint?,
        followCamera: Boolean,
    ): ViewerFeature {
        var out = feat
        if (viewCenterEnu != null) {
            out = out.copy(
                center = listOf(
                    Viewer3dEnu.roundCoord(viewCenterEnu.x),
                    Viewer3dEnu.roundCoord(viewCenterEnu.y),
                    Viewer3dEnu.roundCoord(viewCenterEnu.z),
                ),
            )
        }
        if (followCamera) {
            val tags = LinkedHashMap(out.tags)
            tags["follow_camera"] = "1"
            out = out.copy(tags = tags)
        }
        return out
    }

    fun buildViewportFromMapBounds(
        bounds: ViewBounds,
        anchor: Anchor,
        viewCenterEnu: EnuPoint?,
        followCamera: Boolean,
    ): ViewerFeature {
        val corners = listOf(
            bounds.minLat to bounds.minLon,
            bounds.minLat to bounds.maxLon,
            bounds.maxLat to bounds.maxLon,
            bounds.maxLat to bounds.minLon,
            bounds.minLat to bounds.minLon,
        )
        val base = ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            pts = floorLoop(corners.map { (la, lo) -> Viewer3dEnu.enu(la, lo, anchor.lat, anchor.lon) }),
        )
        return finalizeViewportFeature(base, viewCenterEnu, followCamera)
    }
}
