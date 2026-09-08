package org.openstreetmap.josm.plugins.lanelet2.api

import org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry
import org.openstreetmap.josm.plugins.lanelet2.platform.ActionSlot
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletAction
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuId
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuInstaller
import org.openstreetmap.josm.tools.Logging
import java.awt.event.ActionEvent

/**
 * Stable entry point for Jython 2.7 (and other JSR-223) scripts that want to
 * call into this plugin.
 *
 * Scripts import this object after the plugin has injected its classloader
 * into the Scripting plugin:
 *
 * ```
 * from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions
 * ```
 *
 * The surface is deliberately small. Prefer this facade over reaching into
 * `platform` / `infra` types that may move.
 */
object Lanelet2Extensions {

    private val settingsAccess = SettingsAccess()
    private val geometryAccess = GeometryAccess()
    private val laneletAccess = LaneletAccess()

    /**
     * Register [action] as a menu item.
     *
     * [action] is a SAM interface so a Jython function can be passed directly
     * (Jython coerces a zero-argument callable into [ScriptAction]).
     *
     * [anchor] is a stable slot-id from [anchors], typically produced by
     * [after] or [before]. Re-registering the same [slotId] replaces the
     * action but keeps the original placement.
     */
    @JvmStatic
    fun register(slotId: String, title: String, action: ScriptAction, anchor: Anchor) {
        registerInto(ActionRegistry.INSTANCE, slotId, title, action, anchor)
        MenuInstaller.refreshIfInstalled()
    }

    /**
     * Remove a previously [register]ed slot. No-op if [slotId] is unknown.
     */
    @JvmStatic
    fun unregister(slotId: String) {
        ActionRegistry.INSTANCE.unregister(slotId)
        MenuInstaller.refreshIfInstalled()
    }

    /** Place the new item immediately after the slot named [slotId]. */
    @JvmStatic
    fun after(slotId: String): Anchor = Anchor.after(slotId)

    /** Place the new item immediately before the slot named [slotId]. */
    @JvmStatic
    fun before(slotId: String): Anchor = Anchor.before(slotId)

    /**
     * Stable slot-ids that scripts can pass to [after] / [before].
     *
     * These are the first-party menu entries; script-registered ids are not
     * listed here so this list stays a versioned contract.
     */
    @JvmStatic
    fun anchors(): List<String> =
        (ActionRegistry.BASE_UTILS_ORDER + ActionRegistry.BASE_MAP_ORDER)
            .filterNotNull()
            .distinct()

    /**
     * Plugin settings. Booleans are stored as `"1"` / `"0"` (legacy file
     * compatible). Defaults are applied here, never passed into JOSM's
     * `IPreferences.get`.
     */
    @JvmStatic
    fun settings(): SettingsAccess = settingsAccess

    /** Centerline and related geometry over lanelet bounds. */
    @JvmStatic
    fun geometry(): GeometryAccess = geometryAccess

    /** Lanelet model over JOSM primitives, plus selection helpers. */
    @JvmStatic
    fun lanelets(): LaneletAccess = laneletAccess

    internal fun registerInto(
        registry: ActionRegistry,
        slotId: String,
        title: String,
        action: ScriptAction,
        anchor: Anchor,
    ) {
        val id = slotId.trim()
        require(id.isNotEmpty()) { "slotId must not be empty" }
        val name = title.trim().ifEmpty { id }
        val replacing = registry.get(id) != null
        val josmAction = object : LaneletAction(name, null, name, null) {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    action.run()
                } catch (ex: Exception) {
                    Logging.error("lanelet2: script action ''{0}'' failed", id)
                    Logging.error(ex)
                }
            }
        }
        registry.register(
            ActionSlot(
                id = id,
                action = josmAction,
                toolbarLabel = null,
                iconName = null,
                menu = menuForAnchor(anchor.slotId, registry),
            ),
        )
        if (!replacing) {
            when (anchor.placement) {
                Anchor.Placement.AFTER -> registry.insertAfter(anchor.slotId, listOf(id))
                Anchor.Placement.BEFORE -> registry.insertBefore(anchor.slotId, listOf(id))
            }
        }
    }

    private fun menuForAnchor(anchorId: String, registry: ActionRegistry): MenuId {
        registry.get(anchorId)?.menu?.let { return it }
        if (anchorId in ActionRegistry.BASE_MAP_ORDER.filterNotNull()) return MenuId.MAP
        return MenuId.UTILS
    }
}

/**
 * Zero-argument callback. Jython functions match this SAM automatically.
 */
fun interface ScriptAction {
    fun run()
}

/**
 * Where a [Lanelet2Extensions.register]ed item is inserted relative to a
 * first-party slot id from [Lanelet2Extensions.anchors].
 */
class Anchor private constructor(
    val slotId: String,
    val placement: Placement,
) {
    enum class Placement { BEFORE, AFTER }

    companion object {
        /** Insert immediately after [slotId]. */
        @JvmStatic
        fun after(slotId: String): Anchor = Anchor(slotId, Placement.AFTER)

        /** Insert immediately before [slotId]. */
        @JvmStatic
        fun before(slotId: String): Anchor = Anchor(slotId, Placement.BEFORE)
    }
}
