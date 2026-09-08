package org.openstreetmap.josm.plugins.lanelet2.hooks

import org.openstreetmap.josm.command.DeleteCommand
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.gui.MapFrameListener
import org.openstreetmap.josm.gui.MapView
import org.openstreetmap.josm.plugins.lanelet2.edit.requireVisibleEditLayer
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Delete-key override from `autotag_hook.py`. Always installed; the action
 * self-gates on [LaneletSettings.getDeleteTaggedNodes].
 */
internal object AutotagDeleteOverride {
    const val ACTION_KEY = "lanelet2_delete_tagged_nodes"

    private var mapFrameListener: MapFrameListener? = null
    private var action: AbstractAction? = null

    fun install() {
        if (mapFrameListener == null) {
            val listener = MapFrameListener { _: MapFrame?, newFrame: MapFrame? ->
                try {
                    if (newFrame != null) bind(newFrame.mapView)
                } catch (_: Exception) {
                }
            }
            mapFrameListener = listener
            try {
                MainApplication.addMapFrameListener(listener)
            } catch (_: Exception) {
            }
        }
        try {
            val current = MainApplication.getMap()
            if (current != null) bind(current.mapView)
        } catch (_: Exception) {
        }
    }

    fun uninstall() {
        val listener = mapFrameListener
        if (listener != null) {
            try {
                MainApplication.removeMapFrameListener(listener)
            } catch (_: Exception) {
            }
            mapFrameListener = null
        }
        try {
            val mv = MainApplication.getMap()?.mapView
            if (mv != null) unbind(mv)
        } catch (_: Exception) {
        }
    }

    fun listenerInstalled(): Boolean = mapFrameListener != null

    private fun bind(mapView: MapView?) {
        if (mapView == null) return
        try {
            if (action == null) {
                action = object : AbstractAction() {
                    override fun actionPerformed(event: ActionEvent) {
                        try {
                            if (LaneletSettings.getDeleteTaggedNodes()) {
                                customDelete(event)
                            } else {
                                fallbackDelete(event)
                            }
                        } catch (_: Exception) {
                            fallbackDelete(event)
                        }
                    }
                }
            }
            val inputMap = mapView.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            val actionMap = mapView.actionMap
            inputMap.put(KeyStroke.getKeyStroke("DELETE"), ACTION_KEY)
            actionMap.put(ACTION_KEY, action)
        } catch (_: Exception) {
        }
    }

    private fun unbind(mapView: MapView) {
        try {
            val inputMap = mapView.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            val actionMap = mapView.actionMap
            inputMap.remove(KeyStroke.getKeyStroke("DELETE"))
            actionMap.remove(ACTION_KEY)
        } catch (_: Exception) {
        }
    }

    internal fun customDelete(event: ActionEvent) {
        val layer = requireVisibleEditLayer()
        if (layer == null) {
            fallbackDelete(event)
            return
        }
        val dataset = try {
            layer.data
        } catch (_: Exception) {
            null
        }
        if (dataset == null) {
            fallbackDelete(event)
            return
        }
        val selected = try {
            dataset.selected.toList()
        } catch (_: Exception) {
            emptyList()
        }
        val toDelete = AutotagLogic.collectDeleteSet(
            selected,
            LaneletSettings.getProtectMergeAnchors(),
        )
        if (toDelete == null) {
            fallbackDelete(event)
            return
        }
        val cmd = try {
            DeleteCommand.delete(toDelete, false, false)
        } catch (_: Exception) {
            null
        }
        if (cmd == null) return
        try {
            LaneletUtils.getUndo().add(cmd)
            layer.invalidate()
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }

    private fun fallbackDelete(event: ActionEvent) {
        try {
            MainApplication.getMenu().delete.actionPerformed(event)
        } catch (_: Exception) {
        }
    }
}
