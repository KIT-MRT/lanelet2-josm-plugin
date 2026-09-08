package org.openstreetmap.josm.plugins.lanelet2.platform

class ActionRegistry(
    utilsOrder: List<String?> = BASE_UTILS_ORDER,
    mapOrder: List<String?> = BASE_MAP_ORDER,
) {
    private val utilsOrder = utilsOrder.toList()
    private val mapOrder = mapOrder.toList()
    private val slots = LinkedHashMap<String, ActionSlot>()
    private val insertionsBefore = LinkedHashMap<String, MutableList<String>>()
    private val insertionsAfter = LinkedHashMap<String, MutableList<String>>()

    fun register(slot: ActionSlot) {
        slots[slot.id] = slot
    }

    fun unregister(id: String): ActionSlot? = slots.remove(id)

    fun get(id: String): ActionSlot? = slots[id]

    fun insertBefore(anchorId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        insertionsBefore.getOrPut(anchorId) { mutableListOf() }.addAll(ids)
    }

    fun insertAfter(anchorId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        insertionsAfter.getOrPut(anchorId) { mutableListOf() }.addAll(ids)
    }

    fun build(menu: MenuId): List<ActionSlot?> {
        val orderedIds = applyInsertions(baseOrder(menu))
        val raw = ArrayList<ActionSlot?>(orderedIds.size)
        for (id in orderedIds) {
            if (id == null) {
                raw.add(null)
            } else {
                val slot = slots[id]
                if (slot != null) {
                    raw.add(slot)
                }
            }
        }
        return collapseSeparators(raw)
    }

    private fun baseOrder(menu: MenuId): List<String?> = when (menu) {
        MenuId.UTILS -> utilsOrder
        MenuId.MAP -> mapOrder
    }

    private fun applyInsertions(base: List<String?>): List<String?> {
        val result = ArrayList<String?>(base.size)
        for (item in base) {
            if (item == null) {
                result.add(null)
                continue
            }
            insertionsBefore[item]?.let { result.addAll(it) }
            result.add(item)
            insertionsAfter[item]?.let { result.addAll(it) }
        }
        return result
    }

    companion object {
        val BASE_UTILS_ORDER: List<String?> = listOf(
            "lanelet_edit.check_lanelet_borders",
            null,
            "scripts.ll2_debug_routing_graph",
            "scripts.ll2_debug_routing_graph::small",
            "josm_tools.walk_lanelet_via_routing_graph",
            null,
            "scripts.ll2_make_positive_ids",
            "josm_tools.git_history_loader",
            null,
            "scripts.ll2_merge_input_osm_files_launcher",
            "scripts.ll2_split_merged_osm_files_launcher",
            "josm_tools.highlight_file_boundaries",
            "hooks.autotag_new_elements",
            "hooks.zoom_filter_window",
            "josm_tools.tag_value_search",
            "notes.notes_dialog",
            "regulatory.convert_traffic_light_bikes_to_traffic_light",
            "lanelet_edit.merge_lanelets_swapped_borders",
            "josm_tools.quick_tag_modal",
            null,
            "settings_ui.lanelet2_settings_window",
            "scripting.copy_example_script",
            "settings_ui.set_lanelet_default_subtype::road",
            "settings_ui.set_lanelet_default_subtype::bicycle_lane",
            "settings_ui.set_lanelet_default_subtype::crosswalk",
            "settings_ui.set_lanelet_default_one_way::yes",
            "settings_ui.set_lanelet_default_one_way::no",
        )

        val BASE_MAP_ORDER: List<String?> = listOf(
            "selection.select_lanelets_from_linestrings",
            "selection.select_relations_from_linestrings",
            null,
            "lanelet_edit.add_reg_elem_to_lanelets",
            "regulatory.create_traffic_light_relation",
            "regulatory.create_traffic_sign_relation",
            "regulatory.create_speed_limit_relation",
            "regulatory.create_right_of_way_relation",
            null,
            "lanelet_edit.create_lanelet_relation",
            "lanelet_edit.merge_shared_border_lanelets",
            "lanelet_edit.split_bidirectional_lanelets_on_centerline",
            "lanelet_edit.revert_lanelet_direction",
            null,
            "lanelet_edit.smooth_center_lanelet",
            "lanelet_edit.smooth_center_from_center",
            null,
            "lanelet_edit.delete_relations_with_membership_removal",
            "lanelet_edit.purge_relations_with_members",
            null,
            "regulatory.debug_regulatory_element_connections",
            "regulatory.debug_right_of_way_wizard",
            null,
            "lanelet_edit.split_way_at_selected_nodes",
        )

        @JvmField
        val INSTANCE = ActionRegistry()
    }
}

internal fun collapseSeparators(raw: List<ActionSlot?>): List<ActionSlot?> {
    val result = ArrayList<ActionSlot?>(raw.size)
    for (item in raw) {
        if (item == null) {
            if (result.isNotEmpty() && result.last() != null) {
                result.add(null)
            }
        } else {
            result.add(item)
        }
    }
    if (result.isNotEmpty() && result.last() == null) {
        result.removeAt(result.lastIndex)
    }
    return result
}
