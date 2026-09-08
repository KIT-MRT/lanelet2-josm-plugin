package org.openstreetmap.josm.plugins.lanelet2.infra

/**
 * Planar (lon, lat) pair used as (x, y) for lanelet geometry.
 * Matches the Jython scripts' `(lon, lat)` tuples (small-area degree approximation).
 */
data class LonLat(val lon: Double, val lat: Double)

/** Unit tangent in (dlon, dlat), matching `way_view_tangent_*`. */
data class Tangent(val dlon: Double, val dlat: Double)
