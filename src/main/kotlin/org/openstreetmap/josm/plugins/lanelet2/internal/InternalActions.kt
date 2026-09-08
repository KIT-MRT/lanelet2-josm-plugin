package org.openstreetmap.josm.plugins.lanelet2.internal

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import java.awt.event.ActionEvent

/**
 * Registers the in-scope `internal/` actions (filter-broken and git commit).
 *
 * Sibling of [org.openstreetmap.josm.plugins.lanelet2.edit.EditActions] /
 * [org.openstreetmap.josm.plugins.lanelet2.dependent.DependentActions]. Do not
 * fold these slots into those objects. The 3D viewer bridge is a separate
 * in-scope internal feature and is **not** registered here.
 *
 * Slot ids and metadata match `internal/internal_script_registry.py` (not
 * `core/launcher/registry.py`). Placement matches `internal/order_extensions.py`:
 * filter-broken after `lanelet_edit.check_lanelet_borders`, git commit after
 * `scripts.ll2_make_positive_ids`. Those ids are spliced via [ActionRegistry.insertAfter]
 * rather than added to [ActionRegistry.BASE_UTILS_ORDER].
 */
object InternalActions {
    const val FILTER_ID = "ll2_filter_broken_lanelets_regElements"
    const val GIT_ID = "ll2_git_commit"

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
            id = FILTER_ID,
            displayName = "Filter Broken Lanelets and Regulatory Elements",
            shortcutKey = null,
            toolbarLabel = "Filter",
            iconPath = "icons/filter_broken.svg",
            menu = MenuId.UTILS,
            run = { FilterBroken.run() },
        ),
        SlotMeta(
            id = GIT_ID,
            displayName = "Git Commit (current file)",
            shortcutKey = null,
            toolbarLabel = "Commit",
            iconPath = "icons/git_commit.svg",
            menu = MenuId.UTILS,
            run = { GitCommit.run() },
        ),
    )

    val SLOT_SPECS: List<Pair<String, MenuId>> = SLOT_META.map { it.id to it.menu }

    fun registerAll(registry: ActionRegistry = ActionRegistry.INSTANCE) {
        for (slot in allSlots()) {
            registry.register(slot)
        }
        registry.insertAfter("lanelet_edit.check_lanelet_borders", listOf(FILTER_ID))
        registry.insertAfter("scripts.ll2_make_positive_ids", listOf(GIT_ID))
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
