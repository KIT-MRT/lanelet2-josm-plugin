package org.openstreetmap.josm.plugins.lanelet2.regulatory

import org.openstreetmap.josm.command.ChangeCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.plugins.lanelet2.edit.applySequence
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts

/**
 * Convert selected `type=traffic_light_bikes` ways to `type=traffic_light` with
 * bicycle participants.
 *
 * Tags written (others preserved):
 * - `type=traffic_light`
 * - `participant:vehicle=no`
 * - `participant:bicycle=yes`
 *
 * One SequenceCommand. Port of `convert_traffic_light_bikes_to_traffic_light.py`.
 *
 * **Latent Jython mismatch (replicated):** the create-traffic-light extractor
 * looks for `traffic_light_bicycle`, not `traffic_light_bikes`. Converted ways
 * therefore will not be picked up by [CreateTrafficLightRelation.extractTrafficLights]
 * unless retagged. C++ has no `traffic_light_bikes` linestring type.
 */
object ConvertTrafficLightBikes {
    const val TITLE = "Convert bike traffic lights"
    const val SEQUENCE_NAME = "Convert bike traffic lights to traffic_light + participants"
    const val SOURCE_TYPE = "traffic_light_bikes"

    fun isTrafficLightBikes(way: Way): Boolean {
        val t = way.get("type") ?: return false
        return t.trim().lowercase() == SOURCE_TYPE
    }

    fun extractTargets(selection: Iterable<OsmPrimitive?>): List<Way> {
        val out = ArrayList<Way>()
        for (prim in selection) {
            if (prim != null && prim is Way && isTrafficLightBikes(prim)) {
                out.add(prim)
            }
        }
        return out
    }

    fun convertCopy(way: Way): Way {
        val newWay = Way(way)
        newWay.put("type", "traffic_light")
        newWay.put("participant:vehicle", "no")
        newWay.put("participant:bicycle", "yes")
        return newWay
    }

    fun commandsFor(targets: List<Way>): List<Command> {
        val commands = ArrayList<Command>(targets.size)
        for (w in targets) {
            commands.add(ChangeCommand(w, convertCopy(w)))
        }
        return commands
    }

    fun apply(
        targets: List<Way>,
        layer: OsmDataLayer? = null,
        undo: UndoRedoHandler = LaneletUtils.getUndo(),
    ): Int {
        if (targets.isEmpty()) return 0
        applySequence(SEQUENCE_NAME, commandsFor(targets), layer, undo)
        return targets.size
    }

    fun run(ui: UserPrompts = Dialogs) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            ui.warn("No editable layer selected or visible.", TITLE)
            return
        }
        val targets = extractTargets(layer.data.selected)
        if (targets.isEmpty()) {
            ui.warn("No selected ways with type=$SOURCE_TYPE.", TITLE)
            return
        }
        val n = apply(targets, layer)
        ui.infoAutoClose(
            "Updated $n way(s) to type=traffic_light with participant tags.",
            TITLE,
            2000,
        )
    }
}
