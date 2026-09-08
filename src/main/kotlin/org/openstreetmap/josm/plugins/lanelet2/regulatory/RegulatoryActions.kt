package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers regulatory-element actions into [ActionRegistry].
 *
 * Sibling of [org.openstreetmap.josm.plugins.lanelet2.edit.EditActions] /
 * [org.openstreetmap.josm.plugins.lanelet2.edit.SmoothSplitActions]; do not fold
 * these slots into those objects.
 *
 * Slot ids are the canonical strings from [ActionRegistry.BASE_UTILS_ORDER] /
 * [ActionRegistry.BASE_MAP_ORDER]. Metadata matches `launcher/registry.py`.
 */
object RegulatoryActions {
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "regulatory.convert_traffic_light_bikes_to_traffic_light" to MenuId.UTILS,
        "regulatory.create_traffic_light_relation" to MenuId.MAP,
        "regulatory.create_traffic_sign_relation" to MenuId.MAP,
        "regulatory.create_speed_limit_relation" to MenuId.MAP,
        "regulatory.create_right_of_way_relation" to MenuId.MAP,
        "regulatory.debug_regulatory_element_connections" to MenuId.MAP,
        "regulatory.debug_right_of_way_wizard" to MenuId.MAP,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = "regulatory.convert_traffic_light_bikes_to_traffic_light",
            displayName = "Convert bike traffic lights to traffic_light + participants",
            shortcutKey = null,
            toolbarLabel = null,
            iconPath = null,
            menu = MenuId.UTILS,
        ) { ConvertTrafficLightBikes.run() },
        slot(
            id = "regulatory.create_traffic_light_relation",
            displayName = "Create Traffic Light Relation",
            shortcutKey = "1",
            toolbarLabel = "TL",
            iconPath = "icons/traffic_light.svg",
            menu = MenuId.MAP,
        ) { CreateTrafficLightRelation.run() },
        slot(
            id = "regulatory.create_traffic_sign_relation",
            displayName = "Create Traffic Sign Relation",
            shortcutKey = "2",
            toolbarLabel = "TS",
            iconPath = "icons/traffic_sign.svg",
            menu = MenuId.MAP,
        ) { CreateTrafficSignRelation.run() },
        slot(
            id = "regulatory.create_speed_limit_relation",
            displayName = "Create Speed Limit Relation",
            shortcutKey = "3",
            toolbarLabel = "SL",
            iconPath = "icons/speed_limit.svg",
            menu = MenuId.MAP,
        ) { CreateSpeedLimitRelation.run() },
        slot(
            id = "regulatory.create_right_of_way_relation",
            displayName = "Create Right of Way Relation",
            shortcutKey = "4",
            toolbarLabel = "RoW",
            iconPath = "icons/right_of_way.svg",
            menu = MenuId.MAP,
        ) { CreateRightOfWayRelation.run() },
        slot(
            id = "regulatory.debug_regulatory_element_connections",
            displayName = "Visualize Regulatory Element Connections",
            shortcutKey = "G",
            toolbarLabel = "Viz RegE",
            iconPath = "icons/viz_reg_elem.svg",
            menu = MenuId.MAP,
        ) { DebugRegulatoryConnections.run() },
        slot(
            id = "regulatory.debug_right_of_way_wizard",
            displayName = "Visualize Right of Way Wizard",
            shortcutKey = "Z",
            toolbarLabel = "Viz RoW",
            iconPath = "icons/viz_row.svg",
            menu = MenuId.MAP,
        ) { DebugRightOfWayWizard.run() },
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
