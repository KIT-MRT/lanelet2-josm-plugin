package org.openstreetmap.josm.plugins.lanelet2.notes

import org.openstreetmap.josm.data.osm.DataSet
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.layer.OsmDataLayer
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles
import org.openstreetmap.josm.gui.mappaint.mapcss.MapCSSStyleSource
import org.openstreetmap.josm.plugins.lanelet2.dependent.OsmIo
import java.io.File

/**
 * Owns the notes [OsmDataLayer], file path and MapCSS for one base map layer.
 * Dataset mutations live in [NotesDataset]; this class is the JOSM/layer side.
 */
class NotesManager(
    var baseLayer: OsmDataLayer,
    var defaultNotesPath: String,
    var notesPath: String,
    var layerName: String,
) {
    var layer: OsmDataLayer? = null
    val store = NotesDataset(DataSet(), onRefresh = { refreshMap() })

    var dataset: DataSet
        get() = store.dataset
        set(value) {
            store.dataset = value
        }

    val undoStack: NotesUndoStack get() = store.undoStack

    fun isLayerRegistered(): Boolean {
        val lyr = layer ?: return false
        return try {
            lyr in MainApplication.getLayerManager().layers
        } catch (_: Exception) {
            false
        }
    }

    fun hasNoteContent(): Boolean = try {
        store.anchors().isNotEmpty()
    } catch (_: Exception) {
        false
    }

    fun loadDatasetFromFile(path: String? = notesPath): DataSet? {
        val p = path ?: return null
        val f = File(p)
        if (!f.isFile) return null
        return try {
            OsmIo.parse(f)
        } catch (_: Exception) {
            null
        }
    }

    fun resolveDataset(): DataSet {
        if (hasNoteContent()) return store.dataset
        val loaded = loadDatasetFromFile()
        if (loaded != null) return loaded
        return store.dataset
    }

    fun createLayerForDataset(ds: DataSet) {
        if (ds !== store.dataset) {
            store.idMap.clear()
        }
        val lyr = OsmDataLayer(ds, layerName, File(notesPath))
        try {
            lyr.isUploadDiscouraged = true
        } catch (_: Exception) {
        }
        layer = lyr
        store.dataset = ds
        addLayerBelowActive(lyr, baseLayer)
        store.seedIdMap()
        applyNotesStyle(store)
    }

    fun ensureLayer(): Boolean {
        if (isLayerRegistered()) return false
        layer = null
        createLayerForDataset(resolveDataset())
        return true
    }

    fun attachOrCreateLayer() {
        try {
            for (l in MainApplication.getLayerManager().layers) {
                if (l is OsmDataLayer && l.name == layerName) {
                    layer = l
                    store.dataset = l.data
                    store.seedIdMap()
                    applyNotesStyle(store)
                    return
                }
            }
        } catch (_: Exception) {
        }
        createLayerForDataset(resolveDataset())
    }

    fun loadFromPath(path: String?): Pair<Boolean, String?> {
        if (path.isNullOrEmpty()) return false to "No file selected."
        val abs = File(path).absoluteFile
        if (!abs.isFile) return false to "File not found: ${abs.path}"
        val loaded = loadDatasetFromFile(abs.path)
            ?: return false to "Could not read notes file:\n${abs.path}"

        if (isLayerRegistered()) {
            try {
                val lm = MainApplication.getLayerManager()
                if (lm.activeLayer === layer) {
                    lm.activeLayer = baseLayer
                }
                lm.removeLayer(layer)
            } catch (_: Exception) {
            }
        }
        layer = null

        notesPath = abs.path
        layerName = abs.name
        store.idMap.clear()
        try {
            store.undoStack.clear()
        } catch (_: Exception) {
        }
        store.dataset = DataSet()
        createLayerForDataset(loaded)
        return true to null
    }

    fun anchors() = store.anchors()
    fun groupFor(marker: OsmPrimitive) = store.groupFor(marker)
    fun setNoteField(marker: OsmPrimitive, key: String, value: String?, propagate: Boolean = false) =
        store.setNoteField(marker, key, value, propagate)

    fun addPointNote(lat: Double, lon: Double, tags: Map<String, String>) =
        store.addPointNote(lat, lon, tags)

    fun addNoteFromSelection(selection: Collection<OsmPrimitive>, tags: Map<String, String>) =
        store.addNoteFromSelection(selection, tags)

    fun snapshotGroup(marker: OsmPrimitive) = store.snapshotGroup(marker)
    fun deletePrimitives(prims: Collection<OsmPrimitive>) = store.deletePrimitives(prims)
    fun restorePrims(snaps: List<NoteSnapshot>) = store.restorePrims(snaps)
    fun selectOnMap(prims: Collection<OsmPrimitive>) = store.selectOnMap(prims)
    fun clearMapSelection() = store.clearMapSelection()

    fun save(): Pair<Boolean, String?> {
        return try {
            val text = store.serialize()
            atomicWriteUtf8(File(notesPath), text)
            true to null
        } catch (ex: Exception) {
            false to (ex.message ?: ex.toString())
        }
    }

    fun refreshMap() {
        try {
            applyNotesStyle(store)
        } catch (_: Exception) {
        }
        try {
            layer?.invalidate()
        } catch (_: Exception) {
        }
        repaintMap()
    }
}

fun applyNotesStyle(store: NotesDataset) {
    try {
        for (src in MapPaintStyles.getStyles().styleSources.toList()) {
            try {
                if (src.title == NotesTags.STYLE_TITLE) {
                    MapPaintStyles.removeStyle(src)
                }
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }
    try {
        val source = MapCSSStyleSource(generateMapCss(store.discoveredTypes()))
        source.name = NotesTags.STYLE_NAME
        source.title = NotesTags.STYLE_TITLE
        MapPaintStyles.addStyle(source)
    } catch (_: Exception) {
    }
}

fun addLayerBelowActive(newLayer: OsmDataLayer, baseLayer: OsmDataLayer?) {
    val lm = MainApplication.getLayerManager()
    lm.addLayer(newLayer, false)
    if (baseLayer != null) {
        try {
            lm.activeLayer = baseLayer
            val layers = lm.layers
            var baseIdx = -1
            var newIdx = -1
            for (i in layers.indices) {
                if (layers[i] === baseLayer) baseIdx = i
                if (layers[i] === newLayer) newIdx = i
            }
            if (baseIdx >= 0 && newIdx >= 0) {
                val target = minOf(baseIdx, layers.size - 1)
                if (target >= 0 && newIdx != target) {
                    lm.moveLayer(newLayer, target)
                }
            }
        } catch (_: Exception) {
        }
    }
    newLayer.invalidate()
    repaintMap()
}

fun repaintMap() {
    try {
        MainApplication.getMap()?.mapView?.repaint()
    } catch (_: Exception) {
    }
}

fun buildManager(baseLayer: OsmDataLayer?): Pair<NotesManager?, String?> {
    if (baseLayer == null) return null to NotesTags.NO_ACTIVE_LAYER
    val assoc = try {
        baseLayer.associatedFile
    } catch (_: Exception) {
        null
    }
    val derived = deriveNotesPath(assoc?.absolutePath)
        ?: return null to NotesTags.NO_LAYER_FILE
    return NotesManager(baseLayer, derived.first, derived.first, derived.second) to null
}
