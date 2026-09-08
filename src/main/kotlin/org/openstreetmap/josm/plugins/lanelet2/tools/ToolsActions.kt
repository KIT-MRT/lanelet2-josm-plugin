package org.openstreetmap.josm.plugins.lanelet2.tools

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers josm_tools actions into [ActionRegistry].
 *
 * Sibling of [org.openstreetmap.josm.plugins.lanelet2.selection.SelectionActions];
 * do not fold these slots into that object.
 *
 * Slot ids are the canonical strings from [ActionRegistry.BASE_UTILS_ORDER].
 * Metadata matches `core/launcher/registry.py`.
 */
object ToolsActions {
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "josm_tools.git_history_loader" to MenuId.UTILS,
        "josm_tools.highlight_file_boundaries" to MenuId.UTILS,
        "josm_tools.quick_tag_modal" to MenuId.UTILS,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = "josm_tools.git_history_loader",
            displayName = "Git History - Load Older Version",
            shortcutKey = null,
            toolbarLabel = "Git Hist",
            iconPath = "icons/git_history.svg",
            menu = MenuId.UTILS,
        ) { GitHistoryLoader.run() },
        slot(
            id = "josm_tools.highlight_file_boundaries",
            displayName = "Highlight File Boundaries (per file_origin hulls)",
            shortcutKey = null,
            toolbarLabel = "FileBds",
            iconPath = "icons/file_boundaries.svg",
            menu = MenuId.UTILS,
        ) { HighlightFileBoundaries.run() },
        slot(
            id = "josm_tools.quick_tag_modal",
            displayName = "Quick Tag Modal (Space)",
            shortcutKey = null,
            toolbarLabel = null,
            iconPath = null,
            menu = MenuId.UTILS,
        ) { QuickTagModal.run() },
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
