package org.openstreetmap.josm.plugins.lanelet2.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane

class SettingsWindowTest {

    @Test
    fun dialogSizeCapsToTheScreenAndStaysUsable() {
        val short = SettingsWindow.dialogSize(Dimension(1280, 720))
        assertEquals(SettingsWindow.PREFERRED_WIDTH, short.width)
        assertEquals((720 * 0.85).toInt(), short.height)
        assertTrue(short.height >= SettingsWindow.MIN_HEIGHT)

        val tall = SettingsWindow.dialogSize(Dimension(1920, 1200))
        assertEquals(SettingsWindow.PREFERRED_WIDTH, tall.width)
        assertEquals(900, tall.height)
    }

    @Test
    fun formIsWrappedInAVerticalScrollPane() {
        val pane = SettingsWindow.scrollPane(JPanel())
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, pane.verticalScrollBarPolicy)
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
        assertTrue(pane.verticalScrollBar.unitIncrement > 1)
    }
}
