package org.openstreetmap.josm.plugins.lanelet2.mrt

import org.openstreetmap.josm.plugins.Plugin
import org.openstreetmap.josm.plugins.PluginInformation
import org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin
import org.openstreetmap.josm.tools.Logging

class Lanelet2MrtPlugin(info: PluginInformation) : Plugin(info) {

    init {
        // Touching a core-plugin class proves the Plugin-Requires classloader
        // chain resolved; if it did not, this throws NoClassDefFoundError.
        Logging.info(
            "lanelet2-mrt: plugin loaded, version ${info.version}, " +
                "core=${Lanelet2Plugin::class.java.name}"
        )
    }
}
