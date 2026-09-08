package org.openstreetmap.josm.plugins.lanelet2.dependent

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.gui.progress.NullProgressMonitor
import org.openstreetmap.josm.io.OsmReader
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.FileInputStream

internal object OsmIo {
    fun parse(file: File): DataSet? {
        return try {
            FileInputStream(file).use { fis ->
                OsmReader.parseDataSet(fis, NullProgressMonitor.INSTANCE)
            }
        } catch (e: Exception) {
            Logging.warn("lanelet2: failed to parse {0}: {1}", file.absolutePath, e.message)
            null
        }
    }

    fun addLayer(file: File, layerName: String): OsmDataLayer? {
        val ds = parse(file) ?: return null
        return try {
            val layer = OsmDataLayer(ds, layerName, file)
            val lm = MainApplication.getLayerManager()
            lm.addLayer(layer)
            lm.activeLayer = layer
            try {
                layer.invalidate()
            } catch (_: Exception) {
            }
            try {
                MainApplication.getMap()?.mapView?.repaint()
            } catch (_: Exception) {
            }
            layer
        } catch (e: Exception) {
            Logging.warn("lanelet2: failed to add layer: {0}", e.message)
            null
        }
    }

    /**
     * Replace [old] with a new layer loaded from [file].
     *
     * The Jython tried `lm.addLayer(new, old_idx)` intending a positional
     * insert, but [org.openstreetmap.josm.gui.layer.LayerManager.addLayer]
     * has no index overload — the second argument is `initialZoom: Boolean`.
     * Jython coerces the int: zoom-to-data iff `old_idx != 0`. We replicate
     * that coercion rather than inventing `moveLayer`.
     */
    fun reloadLayerFromFile(old: OsmDataLayer, file: File): Boolean {
        val ds = parse(file) ?: return false
        return try {
            val lm = MainApplication.getLayerManager()
            val layers = lm.layers
            var oldIdx = -1
            for (i in layers.indices) {
                if (layers[i] === old) {
                    oldIdx = i
                    break
                }
            }
            val name = old.name?.ifEmpty { file.name } ?: file.name
            val newLayer = OsmDataLayer(ds, name, file)
            lm.removeLayer(old)
            if (oldIdx >= 0) {
                lm.addLayer(newLayer, oldIdx != 0)
            } else {
                lm.addLayer(newLayer)
            }
            lm.activeLayer = newLayer
            try {
                newLayer.invalidate()
            } catch (_: Exception) {
            }
            try {
                MainApplication.getMap()?.mapView?.repaint()
            } catch (_: Exception) {
            }
            true
        } catch (e: Exception) {
            Logging.warn("lanelet2: failed to reload layer: {0}", e.message)
            false
        }
    }

    fun tryEnableWireframe() {
        try {
            Config.getPref()?.put(
                "mappaint.renderer-class-name",
                "org.openstreetmap.josm.data.osm.visitor.paint.WireframeMapRenderer",
            )
            MainApplication.getMap()?.mapView?.repaint()
        } catch (_: Exception) {
        }
    }
}
