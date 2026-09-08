package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.actions.JosmAction
import java.awt.event.ActionEvent

class ActionRegistryTest {

    private class DummyAction : JosmAction(false) {
        override fun actionPerformed(e: ActionEvent) {}
    }

    private fun slot(id: String, menu: MenuId = MenuId.UTILS): ActionSlot = ActionSlot(
        id = id,
        action = DummyAction(),
        toolbarLabel = id,
        iconName = null,
        menu = menu,
    )

    private fun ids(built: List<ActionSlot?>): List<String?> = built.map { it?.id }

    @Test
    fun skipsUnregisteredIds() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", "missing", "c"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("c"))
        assertEquals(listOf("a", "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun collapsesAdjacentSeparatorsFromSkippedIds() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", null, "skip1", null, null, "b", null, "skip2", null, "c", null),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("b"))
        registry.register(slot("c"))
        assertEquals(listOf("a", null, "b", null, "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun dropsLeadingAndTrailingSeparators() {
        val registry = ActionRegistry(
            utilsOrder = listOf(null, null, "only", null, null),
            mapOrder = emptyList(),
        )
        registry.register(slot("only"))
        assertEquals(listOf("only"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun emptyWhenNothingRegistered() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", null, "b"),
            mapOrder = listOf("m", null),
        )
        assertTrue(registry.build(MenuId.UTILS).isEmpty())
        assertTrue(registry.build(MenuId.MAP).isEmpty())
    }

    @Test
    fun insertAfterAnchorPreservesOrder() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", "b", "c"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("b"))
        registry.register(slot("c"))
        registry.register(slot("x"))
        registry.register(slot("y"))
        registry.insertAfter("a", listOf("x", "y"))
        assertEquals(listOf("a", "x", "y", "b", "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun insertBeforeAnchorPreservesOrder() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", "b", "c"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("b"))
        registry.register(slot("c"))
        registry.register(slot("pre"))
        registry.insertBefore("b", listOf("pre"))
        assertEquals(listOf("a", "pre", "b", "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun multipleInsertionsAtSameAnchorApplyInCallOrder() {
        val registry = ActionRegistry(
            utilsOrder = listOf("anchor"),
            mapOrder = emptyList(),
        )
        registry.register(slot("anchor"))
        registry.register(slot("first"))
        registry.register(slot("second"))
        registry.insertAfter("anchor", listOf("first"))
        registry.insertAfter("anchor", listOf("second"))
        assertEquals(listOf("anchor", "first", "second"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun insertBeforeAndAfterSameAnchor() {
        val registry = ActionRegistry(
            utilsOrder = listOf("anchor"),
            mapOrder = emptyList(),
        )
        registry.register(slot("anchor"))
        registry.register(slot("pre"))
        registry.register(slot("post"))
        registry.insertBefore("anchor", listOf("pre"))
        registry.insertAfter("anchor", listOf("post"))
        assertEquals(listOf("pre", "anchor", "post"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun missingAnchorIsNoOp() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", "b"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("b"))
        registry.register(slot("ghost"))
        registry.insertAfter("not-in-order", listOf("ghost"))
        registry.insertBefore("also-missing", listOf("ghost"))
        assertEquals(listOf("a", "b"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun insertionsRelativeToUnregisteredBaseIdsStillLand() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", "unregistered-anchor", "c"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("c"))
        registry.register(slot("extra"))
        registry.insertAfter("unregistered-anchor", listOf("extra"))
        assertEquals(listOf("a", "extra", "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun insertedUnregisteredIdsAreSkippedAndSeparatorsCollapsed() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a", null, "c"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("c"))
        registry.insertAfter("a", listOf("missing-ext"))
        assertEquals(listOf("a", null, "c"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun mapAndUtilsOrdersAreIndependent() {
        val registry = ActionRegistry(
            utilsOrder = listOf("u1", null, "u2"),
            mapOrder = listOf("m1", "m2"),
        )
        registry.register(slot("u1", MenuId.UTILS))
        registry.register(slot("u2", MenuId.UTILS))
        registry.register(slot("m1", MenuId.MAP))
        registry.register(slot("m2", MenuId.MAP))
        registry.register(slot("ext", MenuId.MAP))
        registry.insertAfter("m1", listOf("ext"))
        assertEquals(listOf("u1", null, "u2"), ids(registry.build(MenuId.UTILS)))
        assertEquals(listOf("m1", "ext", "m2"), ids(registry.build(MenuId.MAP)))
    }

    @Test
    fun insertionsDoNotUseInsertedIdsAsAnchorsInTheSamePass() {
        val registry = ActionRegistry(
            utilsOrder = listOf("a"),
            mapOrder = emptyList(),
        )
        registry.register(slot("a"))
        registry.register(slot("mid"))
        registry.register(slot("nested"))
        registry.insertAfter("a", listOf("mid"))
        registry.insertAfter("mid", listOf("nested"))
        assertEquals(listOf("a", "mid"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun realBaseOrderSkipsUnregisteredAndCollapsesSeparators() {
        val registry = ActionRegistry()
        registry.register(slot("lanelet_edit.check_lanelet_borders"))
        registry.register(slot("settings_ui.lanelet2_settings_window"))
        assertEquals(
            listOf(
                "lanelet_edit.check_lanelet_borders",
                null,
                "settings_ui.lanelet2_settings_window",
            ),
            ids(registry.build(MenuId.UTILS)),
        )
        assertTrue(registry.build(MenuId.MAP).isEmpty())
    }

    @Test
    fun realMapOrderWithSparseRegistration() {
        val registry = ActionRegistry()
        registry.register(slot("selection.select_lanelets_from_linestrings", MenuId.MAP))
        registry.register(slot("lanelet_edit.split_way_at_selected_nodes", MenuId.MAP))
        assertEquals(
            listOf(
                "selection.select_lanelets_from_linestrings",
                null,
                "lanelet_edit.split_way_at_selected_nodes",
            ),
            ids(registry.build(MenuId.MAP)),
        )
    }

    @Test
    fun registerReplacesExistingSlot() {
        val registry = ActionRegistry(utilsOrder = listOf("a"), mapOrder = emptyList())
        val first = slot("a")
        val second = slot("a")
        registry.register(first)
        registry.register(second)
        val built = registry.build(MenuId.UTILS)
        assertEquals(1, built.size)
        assertSame(second.action, built[0]!!.action)
    }

    @Test
    fun unregisterRemovesSlot() {
        val registry = ActionRegistry(utilsOrder = listOf("a", "b"), mapOrder = emptyList())
        registry.register(slot("a"))
        registry.register(slot("b"))
        assertSame(registry.get("a")?.action, registry.unregister("a")?.action)
        assertNull(registry.get("a"))
        assertEquals(listOf("b"), ids(registry.build(MenuId.UTILS)))
    }

    @Test
    fun emptyInsertionsAreIgnored() {
        val registry = ActionRegistry(utilsOrder = listOf("a"), mapOrder = emptyList())
        registry.register(slot("a"))
        registry.insertAfter("a", emptyList())
        registry.insertBefore("a", emptyList())
        assertEquals(listOf("a"), ids(registry.build(MenuId.UTILS)))
    }
}
