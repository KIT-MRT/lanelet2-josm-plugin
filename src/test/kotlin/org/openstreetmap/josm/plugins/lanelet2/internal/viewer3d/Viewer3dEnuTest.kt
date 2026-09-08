package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.plugins.lanelet2.edit.SmoothCenter

class Viewer3dEnuTest {
    @Test
    fun roundCoordUsesPython2HalfAwayFromZero() {
        assertEquals(1.0, Viewer3dEnu.roundCoord(0.5, 0), 1e-12)
        assertEquals(-1.0, Viewer3dEnu.roundCoord(-0.5, 0), 1e-12)
        assertEquals(2.675, Viewer3dEnu.roundCoord(2.6754, 3), 1e-12)
    }

    @Test
    fun enuRoundTripNearOrigin() {
        val lat0 = 49.0
        val lon0 = 8.4
        val (x, y) = Viewer3dEnu.enu(49.001, 8.401, lat0, lon0)
        val (lat, lon) = Viewer3dEnu.enuToLatLon(x, y, lat0, lon0)
        assertEquals(49.001, lat, 1e-6)
        assertEquals(8.401, lon, 1e-6)
    }

    @Test
    fun formatEleGCanEmitScientificNotation() {
        assertEquals("1e-05", Viewer3dEnu.formatEleG(0.00001))
        assertEquals("1", Viewer3dEnu.formatEleG(1.0))
    }

    @Test
    fun py2RoundMatchesSmoothCenterHelper() {
        assertEquals(
            SmoothCenter.py2Round(2.5).toDouble(),
            Viewer3dEnu.roundCoord(2.5, 0),
            1e-12,
        )
    }
}
