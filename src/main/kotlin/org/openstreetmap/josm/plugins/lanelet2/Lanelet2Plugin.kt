package org.openstreetmap.josm.plugins.lanelet2

import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.Plugin
import org.openstreetmap.josm.plugins.PluginInformation
import org.openstreetmap.josm.plugins.lanelet2.dependent.DependentActions
import org.openstreetmap.josm.plugins.lanelet2.settings.SettingsActions
import org.openstreetmap.josm.plugins.lanelet2.edit.EditActions
import org.openstreetmap.josm.plugins.lanelet2.edit.SmoothSplitActions
import org.openstreetmap.josm.plugins.lanelet2.hooks.AutotagHook
import org.openstreetmap.josm.plugins.lanelet2.hooks.HooksActions
import org.openstreetmap.josm.plugins.lanelet2.hooks.RoutingRefreshHook
import org.openstreetmap.josm.plugins.lanelet2.hooks.ZoomFilterHook
import org.openstreetmap.josm.plugins.lanelet2.internal.CommitReminder
import org.openstreetmap.josm.plugins.lanelet2.internal.InternalActions
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dActions
import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dHook
import org.openstreetmap.josm.plugins.lanelet2.notes.NotesActions
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.regulatory.RegulatoryActions
import org.openstreetmap.josm.plugins.lanelet2.selection.SelectionActions
import org.openstreetmap.josm.plugins.lanelet2.tools.ToolsActions
import org.openstreetmap.josm.plugins.lanelet2.platform.MapStyles
import org.openstreetmap.josm.plugins.lanelet2.platform.MenuInstaller
import org.openstreetmap.josm.plugins.lanelet2.platform.TaggingPresetsInstaller
import org.openstreetmap.josm.plugins.lanelet2.scripting.ExampleScript
import org.openstreetmap.josm.plugins.lanelet2.scripting.ScriptingVisibility
import org.openstreetmap.josm.tools.Logging

class Lanelet2Plugin(info: PluginInformation) : Plugin(info) {

    init {
        Logging.info("lanelet2: plugin loaded, version ${info.version}")
        try {
            LaneletSettings.migrateLegacyFileOnce()
            MapStyles.installOnLaunch()
            TaggingPresetsInstaller.installOnLaunch()
            // Register actions before mapFrameInitialized installs menus.
            EditActions.registerAll()
            SmoothSplitActions.registerAll()
            RegulatoryActions.registerAll()
            SelectionActions.registerAll()
            ToolsActions.registerAll()
            NotesActions.registerAll()
            HooksActions.registerAll()
            DependentActions.registerAll()
            SettingsActions.registerAll()
            InternalActions.registerAll()
            Viewer3dActions.registerAll()
            CommitReminder.install()
            Viewer3dHook.installIfEnabled()
            AutotagHook.installIfEnabled()
            AutotagHook.installDeleteOverride()
            AutotagHook.installAnchorProtection()
            ZoomFilterHook.installIfEnabled()
            RoutingRefreshHook.install()
            ExampleScript.registerAll()
            // Scripting plugin may already be loaded; injection is idempotent.
            ScriptingVisibility.exposeOurClassesToScriptingPlugin()
        } catch (e: Exception) {
            Logging.error("lanelet2: initialization failed")
            Logging.error(e)
        }
    }

    override fun mapFrameInitialized(oldFrame: MapFrame?, newFrame: MapFrame?) {
        // Log presence, not the frames: MapFrame.toString() dumps the whole
        // Swing hierarchy and throws headless (Component.getObjectLock() null).
        Logging.info(
            "lanelet2: mapFrameInitialized oldPresent=${oldFrame != null} newPresent=${newFrame != null}",
        )
        try {
            // All plugins have loaded by the first map-frame callback, so this
            // is the reliable point to reach into the Scripting plugin.
            ScriptingVisibility.exposeOurClassesToScriptingPlugin()
            if (newFrame != null) {
                MenuInstaller.install()
                NotesActions.installDialog(newFrame)
            } else {
                MenuInstaller.uninstall()
                Viewer3dHook.uninstall()
                NotesActions.uninstallDialog()
                // Autotag / zoom-filter / routing-refresh persist for the
                // session (Jython core_hooks.install once). Do not uninstall
                // them here: JOSM fires newFrame==null when the last layer
                // closes, then a new frame when a file is opened.
            }
        } catch (e: Exception) {
            Logging.error("lanelet2: menu/toolbar update failed")
            Logging.error(e)
        }
    }
}
