package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.plugins.lanelet2.edit.SmoothCenter
import java.util.Locale
import kotlin.math.cos

/** Local tangent-plane ENU projection used by the 3D bridge. Pure / headless. */
object Viewer3dEnu {
    const val EARTH_R = 6378137.0

    fun enu(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val x = Math.toRadians(lon - lon0) * cos(Math.toRadians(lat0)) * EARTH_R
        val y = Math.toRadians(lat - lat0) * EARTH_R
        return x to y
    }

    fun enuToLatLon(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val lat = lat0 + Math.toDegrees(y / EARTH_R)
        var cos0 = cos(Math.toRadians(lat0))
        if (kotlin.math.abs(cos0) < 1e-12) cos0 = 1e-12
        val lon = lon0 + Math.toDegrees(x / (EARTH_R * cos0))
        return lat to lon
    }

    /**
     * Python 2 `round(x, ndigits)` — half away from zero. Kotlin `round` and
     * Python 3 use banker's rounding; the Jython hook relied on Python 2.
     */
    fun roundCoord(value: Double, decimals: Int = 3): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return SmoothCenter.py2Round(value * factor).toDouble() / factor
    }

    /**
     * Jython `_fmt_num`: `("%g" % float(v))`, which can emit scientific notation
     * (e.g. `1e-05` for very small elevations).
     */
    fun formatEleG(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == 0.0) return "0"
        val abs = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        if (abs >= 1e-4 && abs < 1e6) {
            var s = String.format(Locale.US, "%.6f", value)
            s = s.trimEnd('0').trimEnd('.')
            if (s == "-0") return "0"
            return s
        }
        var exp = Math.floor(Math.log10(abs)).toInt()
        var mantissa = abs / Math.pow(10.0, exp.toDouble())
        var ms = String.format(Locale.US, "%.5g", mantissa).trimEnd('0').trimEnd('.')
        val expStr = if (exp >= 0) {
            "+${exp.toString().padStart(2, '0')}"
        } else {
            "-${kotlin.math.abs(exp).toString().padStart(2, '0')}"
        }
        return "$sign${ms}e$expStr"
    }

    fun cullBoundsKey(bounds: EnuBounds): List<Double> =
        bounds.toList().map { roundCoord(it, 1) }
}

data class EnuBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun toList(): List<Double> = listOf(minX, minY, maxX, maxY)

    fun intersects(other: EnuBounds): Boolean =
        !(maxX < other.minX || minX > other.maxX || maxY < other.minY || minY > other.maxY)
}

data class Anchor(val lat: Double, val lon: Double)

data class EnuPoint(val x: Double, val y: Double, val z: Double = 0.0)

data class ViewBounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun center(): Pair<Double, Double> =
        ((minLat + maxLat) / 2.0) to ((minLon + maxLon) / 2.0)
}
