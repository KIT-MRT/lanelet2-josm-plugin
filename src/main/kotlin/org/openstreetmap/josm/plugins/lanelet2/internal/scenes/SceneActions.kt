package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/** "KITScenes Sensor Data…": not a Jython port, so it has no registry entry there. */
object SceneActions {
    const val SLOT_ID = "ll2_kitscenes"
    const val DISPLAY_NAME = "KITScenes Sensor Data…"
    const val TOOLBAR_LABEL = "KIT"
    const val ICON_PATH = "kitscenes.svg"

    fun slot(): ActionSlot {
        val action = object : LaneletAction(
            DISPLAY_NAME, ICON_PATH,
            "Show recorded KITScenes drives (LiDAR, cameras) in the 3D viewer, on the map open in JOSM", null,
        ) {
            override fun actionPerformed(e: ActionEvent) = SceneServerWindow.run()
        }
        return ActionSlot(
            id = SLOT_ID,
            action = action,
            toolbarLabel = TOOLBAR_LABEL,
            iconName = ICON_PATH,
            toolbarHighlight = { SceneServerWindow.server.isAlive() },
            menu = MenuId.UTILS,
        )
    }

    /**
     * After the 3D viewer and street imagery in the utilities menu and toolbar:
     * on the viewer's own anchor, registered after both (insertions do not
     * chain, so the viewer's id cannot be the anchor).
     */
    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        registry.register(slot())
        registry.insertAfter("hooks.zoom_filter_window", listOf(SLOT_ID))
    }
}
