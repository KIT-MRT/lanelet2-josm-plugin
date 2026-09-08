package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.actions.JosmAction

enum class MenuId { UTILS, MAP }

data class ActionSlot(
    val id: String,
    val action: JosmAction,
    val toolbarLabel: String?,
    val iconName: String?,
    val toolbarGroup: String? = null,
    val menu: MenuId,
) {
    companion object {
        const val GROUP_LANELET_DEFAULT_SUBTYPE = "lanelet_default_subtype"
        const val GROUP_LANELET_DEFAULT_ONEWAY = "lanelet_default_oneway"
    }
}
