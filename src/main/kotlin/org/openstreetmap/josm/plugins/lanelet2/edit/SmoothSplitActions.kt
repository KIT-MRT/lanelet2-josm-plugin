package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers batch-B lanelet_edit actions (create / smooth / split) into
 * [ActionRegistry]. Sibling of [EditActions]; do not fold these slots into that
 * object.
 *
 * Slot ids are the canonical strings from [ActionRegistry.BASE_MAP_ORDER].
 * Metadata matches `launcher/registry.py`.
 */
object SmoothSplitActions {
    /** Slot id plus which menu it belongs to. Used by tests to catch typos. */
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "lanelet_edit.create_lanelet_relation" to MenuId.MAP,
        "lanelet_edit.split_bidirectional_lanelets_on_centerline" to MenuId.MAP,
        "lanelet_edit.smooth_center_lanelet" to MenuId.MAP,
        "lanelet_edit.smooth_center_from_center" to MenuId.MAP,
        "lanelet_edit.split_way_at_selected_nodes" to MenuId.MAP,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        slot(
            id = "lanelet_edit.create_lanelet_relation",
            displayName = "Create Lanelet(s) (starting w. 2 l/r linestrings)",
            shortcutKey = "K",
            toolbarLabel = "c LL",
            iconPath = "icons/create_lanelet.svg",
            menu = MenuId.MAP,
        ) { CreateLaneletRelation.run() },
        slot(
            id = "lanelet_edit.split_bidirectional_lanelets_on_centerline",
            displayName = "Split bidirectional lanelets on virtual centerline",
            shortcutKey = null,
            toolbarLabel = "Spl Bi",
            iconPath = "icons/split_bidirectional_centerline.svg",
            menu = MenuId.MAP,
        ) { SplitBidirectional.run() },
        slot(
            id = "lanelet_edit.smooth_center_lanelet",
            displayName = "Smooth Center Lanelet (batch)",
            shortcutKey = "7",
            toolbarLabel = "Smth",
            iconPath = "icons/smooth_batch.svg",
            menu = MenuId.MAP,
        ) { SmoothCenterLanelet.run() },
        slot(
            id = "lanelet_edit.smooth_center_from_center",
            displayName = "Smooth Center from Selection (1 center)",
            shortcutKey = "9",
            toolbarLabel = "Smth1",
            iconPath = "icons/smooth_one.svg",
            menu = MenuId.MAP,
        ) { SmoothCenterFromCenter.run() },
        slot(
            id = "lanelet_edit.split_way_at_selected_nodes",
            displayName = "Split Ways at Selected Nodes",
            shortcutKey = "Y",
            toolbarLabel = "Split",
            iconPath = "icons/split_way.svg",
            menu = MenuId.MAP,
        ) { SplitWayAtSelectedNodes.run() },
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
