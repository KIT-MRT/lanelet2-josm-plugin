package org.openstreetmap.josm.plugins.lanelet2

import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.Plugin
import org.openstreetmap.josm.plugins.PluginInformation
import org.openstreetmap.josm.tools.Logging

class Lanelet2Plugin(info: PluginInformation) : Plugin(info) {

    init {
        Logging.info("lanelet2: plugin loaded, version ${info.version}")
    }

    override fun mapFrameInitialized(oldFrame: MapFrame?, newFrame: MapFrame?) {
        Logging.info("lanelet2: mapFrameInitialized old=$oldFrame new=$newFrame")
    }
}
