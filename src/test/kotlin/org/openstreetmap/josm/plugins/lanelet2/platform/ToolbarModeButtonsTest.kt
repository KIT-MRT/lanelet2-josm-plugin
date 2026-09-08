package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.actions.JosmAction
import java.awt.event.ActionEvent
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder

/**
 * The default subtype / one-way buttons are a mode selector, so the active mode
 * has to be visible. They shipped as icon-bearing radio buttons, where the icon
 * takes the place of the radio bullet, leaving no selection indicator at all.
 *
 * Icons are left off here on purpose: loading them pulls in JOSM's
 * ImageProvider, which cannot initialise in a headless test.
 */
class ToolbarModeButtonsTest {

    private class DummyAction : JosmAction(false) {
        override fun actionPerformed(e: ActionEvent) {}
    }

    private fun modeSlots(): List<ActionSlot> =
        listOf("road", "bicycle_lane", "crosswalk").map { value ->
            ActionSlot(
                id = "settings_ui.set_lanelet_default_subtype::$value",
                action = DummyAction(),
                toolbarLabel = value.take(4),
                iconName = null,
                toolbarGroup = ActionSlot.GROUP_LANELET_DEFAULT_SUBTYPE,
                menu = MenuId.UTILS,
            )
        }

    @Test
    fun everyModeGetsAButtonKeyedByItsTagValue() {
        val group = MenuInstaller.buildToggleButtons(modeSlots())

        assertEquals(3, group.buttons.size)
        assertEquals(setOf("road", "bicycle_lane", "crosswalk"), group.byValue.keys.toSet())
        for (btn in group.buttons) {
            assertTrue(btn.text.isNotBlank(), "mode buttons keep a short toolbar label")
        }
    }

    @Test
    fun selectingAModeDeselectsThePrevious() {
        val group = MenuInstaller.buildToggleButtons(modeSlots())

        group.byValue.getValue("road").isSelected = true
        group.byValue.getValue("crosswalk").isSelected = true

        assertEquals(1, group.buttons.count { it.isSelected })
        assertTrue(group.byValue.getValue("crosswalk").isSelected)
    }

    @Test
    fun theSelectedModeLooksDifferentFromTheIdleOnes() {
        val group = MenuInstaller.buildToggleButtons(modeSlots())
        val road = group.byValue.getValue("road")
        val bike = group.byValue.getValue("bicycle_lane")

        assertTrue(road.border is EmptyBorder, "idle modes start plain")
        road.isSelected = true

        assertTrue(road.border is CompoundBorder, "the active mode needs an outlined border")
        assertTrue(bike.border is EmptyBorder, "idle modes stay plain")
        assertTrue(road.isOpaque, "the active mode paints its accent background")
        assertNotNull(road.background)
        assertNotEquals(road.background, bike.background)
    }

    @Test
    fun deselectingRestoresTheIdleLook() {
        val group = MenuInstaller.buildToggleButtons(modeSlots())
        val road = group.byValue.getValue("road")

        road.isSelected = true
        group.byValue.getValue("bicycle_lane").isSelected = true

        assertTrue(road.border is EmptyBorder, "the previous mode must drop its highlight")
        assertTrue(!road.isOpaque)
    }
}
