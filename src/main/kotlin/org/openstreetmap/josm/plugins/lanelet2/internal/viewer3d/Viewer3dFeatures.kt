package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

/** Build viewer features and signatures from headless way snapshots. */
object Viewer3dFeatures {
    fun nodeEleMetres(eleTag: String?): Double {
        if (eleTag.isNullOrEmpty()) return 0.0
        return eleTag.toDoubleOrNull() ?: 0.0
    }

    fun featureForWay(way: WaySnapshot, anchor: Anchor): ViewerFeature? {
        val pts = ArrayList<List<Double>>()
        val nodeIds = ArrayList<String>()
        for (n in way.nodes) {
            val lat = n.lat ?: continue
            val lon = n.lon ?: continue
            val (x, y) = Viewer3dEnu.enu(lat, lon, anchor.lat, anchor.lon)
            val z = Viewer3dEnu.roundCoord(nodeEleMetres(n.eleTag))
            pts.add(
                listOf(
                    Viewer3dEnu.roundCoord(x),
                    Viewer3dEnu.roundCoord(y),
                    z,
                ),
            )
            nodeIds.add("node/${n.uniqueId}")
        }
        if (pts.size < 2) return null
        val tags = linkedMapOf<String, String>()
        way.type?.let { tags["type"] = it }
        way.subtype?.let { tags["subtype"] = it }
        way.participantBicycle?.let { tags["participant:bicycle"] = it }
        return ViewerFeature(
            id = "way/${way.uniqueId}",
            kind = "line",
            tags = tags,
            points = pts,
            nodes = nodeIds,
        )
    }

    fun featureSignature(feat: ViewerFeature): FeatureSignature {
        val pts = feat.points.map { p ->
            Triple(p[0], p[1], p.getOrElse(2) { 0.0 })
        }
        return FeatureSignature(
            nodes = feat.nodes.toList(),
            points = pts,
            type = feat.tags["type"],
            subtype = feat.tags["subtype"],
            participantBicycle = feat.tags["participant:bicycle"],
        )
    }

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
        val pts = listOf(
            listOf(bounds.minX, bounds.minY, 0.0),
            listOf(bounds.minX, bounds.maxY, 0.0),
            listOf(bounds.maxX, bounds.maxY, 0.0),
            listOf(bounds.maxX, bounds.minY, 0.0),
            listOf(bounds.minX, bounds.minY, 0.0),
        ).map { p ->
            listOf(
                Viewer3dEnu.roundCoord(p[0]),
                Viewer3dEnu.roundCoord(p[1]),
                Viewer3dEnu.roundCoord(p[2]),
            )
        }
        return ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            points = pts,
        )
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
        val pts = corners.map { (la, lo) ->
            val (x, y) = Viewer3dEnu.enu(la, lo, anchor.lat, anchor.lon)
            listOf(
                Viewer3dEnu.roundCoord(x),
                Viewer3dEnu.roundCoord(y),
                0.0,
            )
        }
        val base = ViewerFeature(
            id = Viewer3dConstants.VIEWPORT_ID,
            kind = "viewport",
            tags = emptyMap(),
            points = pts,
        )
        return finalizeViewportFeature(base, viewCenterEnu, followCamera)
    }
}
