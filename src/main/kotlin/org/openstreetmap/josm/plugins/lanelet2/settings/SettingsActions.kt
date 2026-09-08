package org.openstreetmap.josm.plugins.lanelet2.settings

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Settings window and lanelet-default toolbar toggle slots.
 * Metadata matches `core/launcher/registry.py`.
 */
object SettingsActions {
    val SLOT_SPECS: List<Pair<String, MenuId>> = listOf(
        "settings_ui.lanelet2_settings_window" to MenuId.UTILS,
        "settings_ui.set_lanelet_default_subtype::road" to MenuId.UTILS,
        "settings_ui.set_lanelet_default_subtype::bicycle_lane" to MenuId.UTILS,
        "settings_ui.set_lanelet_default_subtype::crosswalk" to MenuId.UTILS,
        "settings_ui.set_lanelet_default_one_way::yes" to MenuId.UTILS,
        "settings_ui.set_lanelet_default_one_way::no" to MenuId.UTILS,
    )

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
    }

    fun allSlots(): List<ActionSlot> = listOf(
        settingsWindowSlot(),
        subtypeSlot(
            id = "settings_ui.set_lanelet_default_subtype::road",
            displayName = "Default subtype: road (new lanelets)",
            toolbarLabel = "Rd",
            iconPath = "icons/default_subtype_road.svg",
            subtype = "road",
        ),
        subtypeSlot(
            id = "settings_ui.set_lanelet_default_subtype::bicycle_lane",
            displayName = "Default subtype: bicycle_lane (new lanelets)",
            toolbarLabel = "Bike",
            iconPath = "icons/default_subtype_bicycle_lane.svg",
            subtype = "bicycle_lane",
        ),
        subtypeSlot(
            id = "settings_ui.set_lanelet_default_subtype::crosswalk",
            displayName = "Default subtype: crosswalk (new lanelets)",
            toolbarLabel = "Xwalk",
            iconPath = "icons/default_subtype_crosswalk.svg",
            subtype = "crosswalk",
        ),
        oneWaySlot(
            id = "settings_ui.set_lanelet_default_one_way::yes",
            displayName = "Default one_way: one-way (tag omitted)",
            toolbarLabel = "1wy",
            iconPath = "icons/default_oneway_yes.svg",
            oneWay = LaneletSettings.ONE_WAY_YES,
        ),
        oneWaySlot(
            id = "settings_ui.set_lanelet_default_one_way::no",
            displayName = "Default one_way: bidirectional (one_way=no)",
            toolbarLabel = "2wy",
            iconPath = "icons/default_oneway_no.svg",
            oneWay = LaneletSettings.ONE_WAY_NO,
        ),
    )

    private fun settingsWindowSlot(): ActionSlot {
        val action = object : LaneletAction(
            SettingsWindow.TITLE,
            "icons/settings.svg",
            SettingsWindow.TITLE,
            null,
        ) {
            override fun actionPerformed(e: ActionEvent) = SettingsWindow.show()
        }
        return ActionSlot(
            id = "settings_ui.lanelet2_settings_window",
            action = action,
            toolbarLabel = "Settings",
            iconName = "icons/settings.svg",
            menu = MenuId.UTILS,
        )
    }

    private fun subtypeSlot(
        id: String,
        displayName: String,
        toolbarLabel: String,
        iconPath: String,
        subtype: String,
    ): ActionSlot {
        val action = object : LaneletAction(displayName, iconPath, displayName, null) {
            override fun actionPerformed(e: ActionEvent) = SetLaneletDefaultSubtype.run(subtype)
        }
        return ActionSlot(
            id = id,
            action = action,
            toolbarLabel = toolbarLabel,
            iconName = iconPath,
            toolbarGroup = ActionSlot.GROUP_LANELET_DEFAULT_SUBTYPE,
            menu = MenuId.UTILS,
        )
    }

    private fun oneWaySlot(
        id: String,
        displayName: String,
        toolbarLabel: String,
        iconPath: String,
        oneWay: String,
    ): ActionSlot {
        val action = object : LaneletAction(displayName, iconPath, displayName, null) {
            override fun actionPerformed(e: ActionEvent) = SetLaneletDefaultOneWay.run(oneWay)
        }
        return ActionSlot(
            id = id,
            action = action,
            toolbarLabel = toolbarLabel,
            iconName = iconPath,
            toolbarGroup = ActionSlot.GROUP_LANELET_DEFAULT_ONEWAY,
            menu = MenuId.UTILS,
        )
    }
}

/** Port of `core/settings_ui/set_lanelet_default_subtype.py`. */
internal object SetLaneletDefaultSubtype {
    fun run(subtype: String) {
        var s = subtype.trim()
        if (s !in LaneletSettings.LANELET_SUBTYPES) s = "road"
        if (!LaneletSettings.setLaneletDefaultSubtype(s)) return
        Dialogs.infoAutoClose(
            "Default lanelet subtype for new lanelets: $s",
            "Lanelet2 default subtype",
            1500,
        )
    }
}

/** Port of `core/settings_ui/set_lanelet_default_one_way.py`. */
internal object SetLaneletDefaultOneWay {
    fun run(oneWay: String) {
        var v = oneWay.trim().lowercase()
        if (v !in LaneletSettings.LANELET_ONE_WAY_VALUES) v = LaneletSettings.ONE_WAY_YES
        if (!LaneletSettings.setLaneletDefaultOneWay(v)) return
        val msg = if (v == LaneletSettings.ONE_WAY_NO) {
            "Default: bidirectional (new lanelets get one_way=no)."
        } else {
            "Default: one-way (new lanelets omit one_way tag)."
        }
        Dialogs.infoAutoClose(msg, "Lanelet2 default one_way", 1500)
    }
}
