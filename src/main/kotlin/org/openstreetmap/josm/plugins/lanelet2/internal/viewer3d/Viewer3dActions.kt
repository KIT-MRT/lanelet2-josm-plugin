package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers `ll2_viewer3d_window` from `internal/internal_script_registry.py`.
 * Spliced after `hooks.zoom_filter_window` per `internal/order_extensions.py`.
 */
object Viewer3dActions {
    const val SLOT_ID = "ll2_viewer3d_window"
    const val DISPLAY_NAME = "Live 3D Viewer (browser, Three.js)"
    const val TOOLBAR_LABEL = "3D"
    const val ICON_PATH = "icons/viewer3d.svg"

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        registry.register(slot())
        registry.insertAfter("hooks.zoom_filter_window", listOf(SLOT_ID))
    }

    fun slot(): ActionSlot {
        val action = object : LaneletAction(DISPLAY_NAME, ICON_PATH, DISPLAY_NAME, null) {
            override fun actionPerformed(e: ActionEvent) = Viewer3dWindow.run()
        }
        return ActionSlot(
            id = SLOT_ID,
            action = action,
            toolbarLabel = TOOLBAR_LABEL,
            iconName = ICON_PATH,
            toolbarHighlight = { Viewer3dSettings.isEnabled() },
            menu = MenuId.UTILS,
        )
    }
}
