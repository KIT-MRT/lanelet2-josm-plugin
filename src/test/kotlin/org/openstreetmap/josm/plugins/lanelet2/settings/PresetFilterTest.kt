package org.openstreetmap.josm.plugins.lanelet2.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresetFilterTest {

    @Test
    fun blobMatchesFilterFindsSubstring() {
        val blob = buildPresetFilterBlob("Stop line", "Stop line", "Lanelet2/Stop line")
        assertTrue(blobMatchesFilter(blob, "stop"))
        assertFalse(blobMatchesFilter(blob, "bike"))
    }

    @Test
    fun emptyFilterMatchesAll() {
        val blob = buildPresetFilterBlob("Anything", "Anything", "Lanelet2/Anything")
        assertTrue(blobMatchesFilter(blob, ""))
    }

    @Test
    fun buildPresetFilterBlobIncludesRawName() {
        val blob = buildPresetFilterBlob("Display", "Bike dashed", "Lanelet2/Bike dashed")
        assertTrue(blob.contains("bike dashed"))
    }
}
