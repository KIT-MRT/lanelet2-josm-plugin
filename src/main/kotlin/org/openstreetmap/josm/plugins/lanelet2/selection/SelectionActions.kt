package org.openstreetmap.josm.plugins.lanelet2.selection

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers selection actions into [ActionRegistry].
 *
 * Sibling of [org.openstreetmap.josm.plugins.lanelet2.edit.EditActions]; do not
 * fold these slots into that object.
 *
 * Slot ids are the canonical strings from [ActionRegistry.BASE_MAP_ORDER].
 * Metadata matches `launcher/registry.py`.
 */
object SelectionActions {
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "selection.select_lanelets_from_linestrings" to MenuId.MAP,
        "selection.select_relations_from_linestrings" to MenuId.MAP,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = "selection.select_lanelets_from_linestrings",
            displayName = "Select Lanelets from Linestrings",
            shortcutKey = "L",
            toolbarLabel = "Sel LL",
            iconPath = "icons/sel_lanelets.svg",
            menu = MenuId.MAP,
        ) { SelectLaneletsFromLinestrings.run() },
        slot(
            id = "selection.select_relations_from_linestrings",
            displayName = "Select Relations from Linestrings",
            shortcutKey = "T",
            toolbarLabel = "Sel Rel",
            iconPath = "icons/sel_relations.svg",
            menu = MenuId.MAP,
        ) { SelectRelationsFromLinestrings.run() },
    )

    private fun slot(
        id: String,
        displayName: String,
        shortcutKey: String?,
        toolbarLabel: String?,
        iconPath: String?,
        menu: MenuId,
        run: () -> Unit,
    ): ActionSlot {
        val action = object : LaneletAction(displayName, iconPath, displayName, shortcutKey) {
            override fun actionPerformed(e: ActionEvent) = run()
        }
        return ActionSlot(
            id = id,
            action = action,
            toolbarLabel = toolbarLabel,
            iconName = iconPath,
            menu = menu,
        )
    }
}
