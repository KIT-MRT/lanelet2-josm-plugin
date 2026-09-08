package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers the two hook configuration slots into [ActionRegistry].
 *
 * Slot ids already exist in [ActionRegistry.BASE_UTILS_ORDER]. Metadata
 * matches `core/launcher/registry.py`.
 */
object HooksActions {
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "hooks.autotag_new_elements" to MenuId.UTILS,
        "hooks.zoom_filter_window" to MenuId.UTILS,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = "hooks.autotag_new_elements",
            displayName = "Autotag New Elements (set file_origin etc.)",
            shortcutKey = null,
            toolbarLabel = "AT",
            iconPath = "icons/autotag.svg",
            menu = MenuId.UTILS,
        ) { AutotagNewElements.run() },
        slot(
            id = "hooks.zoom_filter_window",
            displayName = "Zoom Filter Hook (auto-filter at high zoom)",
            shortcutKey = null,
            toolbarLabel = "ZFi",
            iconPath = "icons/zoom_filter.svg",
            menu = MenuId.UTILS,
        ) { ZoomFilterWindow.run() },
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
