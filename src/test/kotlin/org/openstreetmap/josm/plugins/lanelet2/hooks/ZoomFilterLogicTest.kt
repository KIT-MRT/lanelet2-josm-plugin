package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ZoomFilterLogicTest {

    @Test
    fun defaultThresholdIsSeventeenNotNineteen() {
        assertEquals(17.0, ZoomFilterLogic.DEFAULT_THRESHOLD, 0.0)
        assertEquals(156543.03392, ZoomFilterLogic.R0, 0.0)
    }

    @Test
    fun zoomZeroAtEquatorWhenGroundResIsR0() {
        val z = ZoomFilterLogic.zoomFromGroundRes(ZoomFilterLogic.R0, 0.0)!!
        assertEquals(0.0, z, 1e-12)
        val fromDist = ZoomFilterLogic.zoomFromDist100(ZoomFilterLogic.R0 * 100.0, 0.0)!!
        assertEquals(0.0, fromDist, 1e-12)
    }

    @Test
    fun zoomRejectsNonPositiveResolution() {
        assertNull(ZoomFilterLogic.zoomFromGroundRes(0.0, 0.0))
        assertNull(ZoomFilterLogic.zoomFromDist100(-1.0, 0.0))
        assertNull(ZoomFilterLogic.zoomFromGroundRes(1.0, 180.0))
    }

    @Test
    fun filtersActiveAtOrBelowThreshold() {
        assertTrue(ZoomFilterLogic.shouldFiltersBeActive(17.0, 17.0))
        assertTrue(ZoomFilterLogic.shouldFiltersBeActive(10.0, 17.0))
        assertFalse(ZoomFilterLogic.shouldFiltersBeActive(17.0001, 17.0))
    }

    @Test
    fun parseThresholdFallsBack() {
        assertEquals(17.0, ZoomFilterLogic.parseThreshold(null), 0.0)
        assertEquals(19.0, ZoomFilterLogic.parseThreshold("19"), 0.0)
        assertEquals(17.0, ZoomFilterLogic.parseThreshold("nope"), 0.0)
    }

    @Test
    fun jsonRoundTripAndDefaultOnGarbage() {
        val specs = listOf(
            ZoomFilterLogic.ZoomFilterSpec(true, true, false, "type:node"),
            ZoomFilterLogic.ZoomFilterSpec(false, false, true, "highway=\"res\""),
        )
        val json = ZoomFilterLogic.encodeFiltersJson(specs)
        assertTrue(json.contains("\"enabled\": true"))
        assertEquals(specs, ZoomFilterLogic.parseFiltersJson(json))
        assertEquals(ZoomFilterLogic.DEFAULT_FILTERS, ZoomFilterLogic.parseFiltersJson("not-json"))
        assertEquals(ZoomFilterLogic.DEFAULT_FILTERS, ZoomFilterLogic.parseFiltersJson(""))
        assertTrue(ZoomFilterLogic.parseFiltersJson("[]").isEmpty())
    }

    @Test
    fun managedSpecsSkipDisabledAndBlank() {
        val managed = ZoomFilterLogic.managedSpecs(
            listOf(
                ZoomFilterLogic.ZoomFilterSpec(true, true, false, "  type:node  "),
                ZoomFilterLogic.ZoomFilterSpec(false, true, false, "type:way"),
                ZoomFilterLogic.ZoomFilterSpec(true, true, false, "   "),
            ),
        )
        assertEquals(1, managed.size)
        assertEquals("type:node", managed[0].text)
    }

    @Test
    fun latitudeCosineMatchesManual() {
        val lat = 49.0
        val ground = 2.0
        val expected = kotlin.math.ln(
            ZoomFilterLogic.R0 * kotlin.math.cos(Math.toRadians(lat)) / ground,
        ) / kotlin.math.ln(2.0)
        val got = ZoomFilterLogic.zoomFromGroundRes(ground, lat)!!
        assertTrue(abs(expected - got) < 1e-12)
    }
}
