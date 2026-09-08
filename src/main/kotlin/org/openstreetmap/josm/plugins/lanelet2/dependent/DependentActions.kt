package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers the four lanelet2-library-dependent actions (plus the small
 * routing-graph variant) into [ActionRegistry].
 *
 * Sibling of [org.openstreetmap.josm.plugins.lanelet2.edit.EditActions] /
 * [org.openstreetmap.josm.plugins.lanelet2.regulatory.RegulatoryActions]; do
 * not fold these slots into those objects.
 *
 * Slot ids and metadata match `ll2_dependent/ll2_script_registry.py` (the
 * Jython source of truth; `core/launcher/registry.py` does not list these).
 */
object DependentActions {
    data class SlotMeta(
        val id: String,
        val displayName: String,
        val shortcutKey: String?,
        val toolbarLabel: String?,
        val iconPath: String?,
        val menu: MenuId,
        val run: () -> Unit,
    )

    val SLOT_META: List<SlotMeta> = listOf(
        SlotMeta(
            id = "scripts.ll2_debug_routing_graph",
            displayName = "Generate Debug Routing Graph",
            shortcutKey = "H",
            toolbarLabel = "RoutG",
            iconPath = "icons/routing_graph.svg",
            menu = MenuId.UTILS,
            run = { DebugRoutingGraph.run(small = false) },
        ),
        SlotMeta(
            id = "scripts.ll2_debug_routing_graph::small",
            displayName = "Generate Small Debug Routing Graph",
            shortcutKey = null,
            toolbarLabel = "sRG",
            iconPath = null,
            menu = MenuId.UTILS,
            run = { DebugRoutingGraph.run(small = true) },
        ),
        SlotMeta(
            id = "scripts.ll2_make_positive_ids",
            displayName = "Make Positive IDs",
            shortcutKey = "I",
            toolbarLabel = "Pos IDs",
            iconPath = "icons/positive_ids.svg",
            menu = MenuId.UTILS,
            run = { MakePositiveIds.run() },
        ),
        SlotMeta(
            id = "scripts.ll2_merge_input_osm_files_launcher",
            displayName = "Merge OSM Files",
            shortcutKey = "M",
            toolbarLabel = "Merge",
            iconPath = "icons/merge_input.svg",
            menu = MenuId.UTILS,
            run = { MergeOsmFiles.run() },
        ),
        SlotMeta(
            id = "scripts.ll2_split_merged_osm_files_launcher",
            displayName = "Split Merged OSM File",
            shortcutKey = null,
            toolbarLabel = "Split",
            iconPath = "icons/split_way.svg",
            menu = MenuId.UTILS,
            run = { SplitMergedOsmFile.run() },
        ),
    )

    val SLOT_SPECS: List<Pair<String, MenuId>> = SLOT_META.map { it.id to it.menu }

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = SLOT_META.map { meta ->
        val action = object : LaneletAction(meta.displayName, meta.iconPath, meta.displayName, meta.shortcutKey) {
            override fun actionPerformed(e: ActionEvent) = meta.run()
        }
        ActionSlot(
            id = meta.id,
            action = action,
            toolbarLabel = meta.toolbarLabel,
            iconName = meta.iconPath,
            menu = meta.menu,
        )
    }
}
