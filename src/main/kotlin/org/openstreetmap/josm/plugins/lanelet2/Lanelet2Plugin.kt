package org.openstreetmap.josm.plugins.lanelet2

import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.Plugin
import org.openstreetmap.josm.plugins.PluginInformation
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.platform.MapStyles
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuInstaller
import org.openstreetmap.josm.plugins.lanelet2.platform.TaggingPresetsInstaller
import org.openstreetmap.josm.tools.Logging

class Lanelet2Plugin(info: PluginInformation) : Plugin(info) {

    init {
        Logging.info("lanelet2: plugin loaded, version ${info.version}")
        try {
            LaneletSettings.migrateLegacyFileOnce()
            MapStyles.installOnLaunch()
            TaggingPresetsInstaller.installOnLaunch()
        } catch (e: Exception) {
            Logging.error("lanelet2: initialization failed")
            Logging.error(e)
        }
    }

    override fun mapFrameInitialized(oldFrame: MapFrame?, newFrame: MapFrame?) {
        Logging.info("lanelet2: mapFrameInitialized old=$oldFrame new=$newFrame")
        try {
            if (newFrame != null) {
                MenuInstaller.install()
            } else {
                MenuInstaller.uninstall()
            }
        } catch (e: Exception) {
            Logging.error("lanelet2: menu/toolbar update failed")
            Logging.error(e)
        }
    }
}
