package org.openstreetmap.josm.plugins.lanelet2.edit

import org.openstreetmap.josm.plugins.lanelet2.infra.RegulatoryElements
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts

/**
 * Type/subtype choice used by delete/purge. The Jython dialogs are ported as
 * a modal chooser; this object holds the lists and the resolution rules so
 * tests do not need Swing.
 */
object TypeSubtype {
    val DELETE_RELATION_TYPES: List<String> = listOf(
        "regulatory_element", "lanelet", "multipolygon", "area", "Custom",
    )

    val PURGE_RELATION_TYPES: List<String> = listOf(
        "lanelet", "regulatory_element", "multipolygon", "area", "Custom",
    )

    val LANELET_SUBTYPES: List<String> = listOf(
        "(any)", "lane", "road", "highway", "play_street", "emergency_lane", "bus_lane",
        "bicycle_lane", "exit", "walkway", "shared_walkway", "crosswalk", "stairs", "Custom",
    )

    fun subtypesFor(relType: String): List<String> = when (relType) {
        "regulatory_element" -> RegulatoryElements.SUBTYPES
        "lanelet" -> LANELET_SUBTYPES
        else -> listOf("(any)", "Custom")
    }

    data class Choice(val type: String, val subtype: String?)

    /**
     * @return null when Custom type is selected but [customType] is blank
     *   (the Jython dialog refuses to continue in that case).
     */
    fun resolve(
        selectedType: String,
        customType: String,
        selectedSubtype: String?,
        customSubtype: String,
    ): Choice? {
        val type = if (selectedType == "Custom") {
            val t = customType.trim()
            if (t.isEmpty()) return null
            t
        } else {
            selectedType
        }
        val subtype = when {
            selectedSubtype == "Custom" -> customSubtype.trim().ifEmpty { null }
            selectedSubtype == null || selectedSubtype == "(any)" -> null
            else -> selectedSubtype
        }
        return Choice(type, subtype)
    }

    fun prompt(ui: UserPrompts, types: List<String>, title: String): Choice? {
        val typeSel = ui.pick(title, "Type:", types) ?: return null
        val subtypeSel = ui.pick(title, "Subtype:", subtypesFor(typeSel)) ?: return null
        val customType = if (typeSel == "Custom") {
            ui.ask(title, "Custom type:") ?: return null
        } else {
            ""
        }
        val customSubtype = if (subtypeSel == "Custom") {
            ui.ask(title, "Custom subtype:") ?: ""
        } else {
            ""
        }
        val choice = resolve(typeSel, customType, subtypeSel, customSubtype)
        if (choice == null) {
            ui.warn("Enter a custom type.", title)
        }
        return choice
    }
}
