package org.openstreetmap.josm.plugins.lanelet2.notes

import org.openstreetmap.josm.data.osm.Node
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor
import org.openstreetmap.josm.data.projection.ProjectionRegistry
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.gui.dialogs.ToggleDialog
import org.openstreetmap.josm.gui.layer.LayerManager
import org.openstreetmap.josm.gui.layer.MainLayerManager
import org.openstreetmap.josm.gui.widgets.DisableShortcutsOnFocusGainedTextField
import org.openstreetmap.josm.plugins.lanelet2.infra.LaneletUtils
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.tools.ImageProvider
import org.openstreetmap.josm.tools.Shortcut
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.ArrayList
import java.util.Collections
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableModelEvent
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

const val PANEL_NAME = "LL2 Notes"
const val PANEL_ICON = "pin"
const val PANEL_HEIGHT = 220

private const val COL_DONE = 0
private const val COL_TEXT = 1
private const val COL_TYPE = 2
private const val COL_SEVERITY = 3

private val SEVERITY_COLORS = mapOf(
    "minor" to Color(0x2e, 0x7d, 0x32),
    "major" to Color(0xef, 0x6c, 0x00),
    "breaking" to Color(0xc6, 0x28, 0x28),
)

/**
 * Dockable notes panel. Lifecycle matches JOSM [ToggleDialog]: listeners are
 * registered in [showNotify] and removed in [hideNotify] / [destroy].
 */
class NotesToggleDialog : ToggleDialog(
    PANEL_NAME,
    PANEL_ICON,
    "Custom geolocated audit notes for the active map layer.",
    Shortcut.registerShortcut(
        "subwindow:ll2notes",
        "Windows: LL2 Notes",
        KeyEvent.VK_N,
        Shortcut.ALT_SHIFT,
    ),
    PANEL_HEIGHT,
    false,
) {
    private var mgr: NotesManager? = null
    private val anchors = ArrayList<OsmPrimitive>()
    private var loading = false
    private var suppressSel = false
    private var dirty = false
    private var listenersInstalled = false

    private val model = NotesTableModel()
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)
    private val search = DisableShortcutsOnFocusGainedTextField(12)
    private val chkNotDone = JCheckBox("Show only not-done")
    private val saveBtn = JButton()
    private val layerBtn = JButton("Notes layer")
    private val saveTimer = Timer(450) { flushSave() }

    private val layerListener = MainLayerManager.ActiveLayerChangeListener {
        try {
            updateLayerButton()
        } catch (_: Exception) {
        }
    }

    private val notesLayerListener = object : LayerManager.LayerChangeListener {
        override fun layerAdded(e: LayerManager.LayerAddEvent) = Unit

        override fun layerRemoving(e: LayerManager.LayerRemoveEvent) {
            try {
                val m = mgr ?: return
                if (m.layer != null && e.removedLayer === m.layer) {
                    m.layer = null
                }
            } catch (_: Exception) {
            }
        }

        override fun layerOrderChanged(e: LayerManager.LayerOrderChangeEvent) = Unit
    }

    init {
        saveTimer.isRepeats = false
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.rowSorter = sorter
        buildColumns()
        createLayout(buildUi(), false, Collections.emptyList())
        wireEvents()
        setupSaveControls()
        updateSaveButton()
    }

    private fun setupSaveControls() {
        try {
            saveBtn.icon = ImageProvider.get("save", ImageProvider.ImageSizes.SMALLICON)
        } catch (_: Exception) {
            saveBtn.text = "Save"
        }
        saveBtn.toolTipText = "Save notes to the .notes file"
        saveBtn.margin = Insets(2, 4, 2, 4)
        saveBtn.addActionListener { saveNow() }
    }

    private fun buildColumns() {
        table.setDefaultEditor(String::class.java, DefaultCellEditor(DisableShortcutsOnFocusGainedTextField()))
        val cm = table.columnModel
        cm.getColumn(COL_DONE).maxWidth = 50
        val typeCombo = JComboBox(NotesTags.NOTE_TYPES.toTypedArray())
        typeCombo.isEditable = true
        typeCombo.setEditor(ShortcutSafeComboEditor())
        cm.getColumn(COL_TYPE).cellEditor = DefaultCellEditor(typeCombo)
        val sevCombo = JComboBox(NotesTags.SEVERITIES.toTypedArray())
        cm.getColumn(COL_SEVERITY).cellEditor = DefaultCellEditor(sevCombo)
        cm.getColumn(COL_SEVERITY).cellRenderer = StarRenderer()
        cm.getColumn(COL_SEVERITY).maxWidth = 90
        try {
            sorter.setComparator(COL_SEVERITY, Comparator<Any> { a, b ->
                severityRank(a.toString()) - severityRank(b.toString())
            })
        } catch (_: Exception) {
        }
    }

    private fun buildUi(): JPanel {
        val top = JPanel(FlowLayout(FlowLayout.LEFT))
        top.add(JLabel("Search:"))
        top.add(search)
        top.add(chkNotDone)

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT))
        val btnNew = JButton("New Note")
        btnNew.toolTipText = "Create a note from the current selection, or a point at the map center"
        btnNew.addActionListener { newNote() }
        row1.add(btnNew)
        layerBtn.toolTipText = "Switch to the notes layer to edit note geometry"
        layerBtn.addActionListener { switchLayer() }
        row1.add(layerBtn)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT))
        val btnUndo = JButton("undo del")
        btnUndo.toolTipText = "Undo the last note deletion"
        btnUndo.addActionListener { undoDelete() }
        row2.add(btnUndo)
        val btnLoad = JButton("Load")
        btnLoad.toolTipText = "Load notes from another .notes file"
        btnLoad.addActionListener { loadNotesFile() }
        row2.add(btnLoad)
        val btnIo = JButton("I/O")
        btnIo.toolTipText = "Export notes as text, or import notes from text"
        btnIo.addActionListener { ioDialog() }
        row2.add(btnIo)
        row2.add(saveBtn)

        val south = JPanel(BorderLayout())
        south.add(row1, BorderLayout.NORTH)
        south.add(row2, BorderLayout.SOUTH)

        val body = JPanel(BorderLayout())
        body.add(top, BorderLayout.NORTH)
        body.add(JScrollPane(table), BorderLayout.CENTER)
        body.add(south, BorderLayout.SOUTH)
        return body
    }

    private fun wireEvents() {
        model.addTableModelListener { ev -> onModelChanged(ev) }
        table.selectionModel.addListSelectionListener { ev: ListSelectionEvent ->
            if (!ev.valueIsAdjusting) onRowSelection()
        }
        val doc = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        }
        search.document.addDocumentListener(doc)
        chkNotDone.addActionListener { applyFilter() }

        val im = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteNotes")
        table.actionMap.put(
            "deleteNotes",
            object : AbstractAction("act") {
                override fun actionPerformed(e: ActionEvent) = deleteSelected()
            },
        )
    }

    override fun showNotify() {
        if (!ensureManager()) return
        installListeners()
        reloadRows()
        updateLayerButton()
        updateSaveButton()
    }

    override fun hideNotify() {
        try {
            if (saveTimer.isRunning) saveTimer.stop()
        } catch (_: Exception) {
        }
        uninstallListeners()
        if (mgr != null && isNotesLayerActive()) {
            try {
                val base = mgr?.baseLayer
                if (base != null) MainApplication.getLayerManager().activeLayer = base
            } catch (_: Exception) {
            }
            markDirty()
            updateLayerButton()
        }
        flushSave()
    }

    override fun destroy() {
        hideNotify()
        super.destroy()
    }

    private fun installListeners() {
        if (listenersInstalled) return
        try {
            val lm = MainApplication.getLayerManager()
            lm.addActiveLayerChangeListener(layerListener)
            lm.addLayerChangeListener(notesLayerListener)
            listenersInstalled = true
        } catch (_: Exception) {
        }
    }

    private fun uninstallListeners() {
        if (!listenersInstalled) return
        try {
            val lm = MainApplication.getLayerManager()
            lm.removeActiveLayerChangeListener(layerListener)
            lm.removeLayerChangeListener(notesLayerListener)
        } catch (_: Exception) {
        }
        listenersInstalled = false
    }

    fun ensureManager(): Boolean {
        val base = LaneletUtils.getEditLayer()
        if (base == null || !base.isVisible) {
            warn("No editable layer selected or visible.")
            return false
        }
        val name = base.name?.toString().orEmpty()
        if (name.lowercase().endsWith(".notes")) {
            if (mgr != null && mgr?.layer === base) return true
            warn("The active layer is a notes layer. Activate the map layer first.")
            return false
        }
        if (mgr != null && mgr?.baseLayer === base) {
            return ensureNotesLayer()
        }
        val (built, err) = buildManager(base)
        if (built == null) {
            warn(err ?: NotesTags.NO_ACTIVE_LAYER)
            return false
        }
        try {
            built.attachOrCreateLayer()
        } catch (ex: Exception) {
            warn("Failed to open notes layer: ${ex.message}")
            return false
        }
        mgr = built
        markSaved()
        return true
    }

    private fun ensureNotesLayer(): Boolean {
        val m = mgr ?: return false
        val recreated = try {
            m.ensureLayer()
        } catch (ex: Exception) {
            warn("Failed to restore notes layer: ${ex.message}")
            return false
        }
        if (recreated) {
            reloadRows()
            updateLayerButton()
            updateSaveButton()
        }
        return true
    }

    fun reloadRows() {
        val m = mgr ?: return
        loading = true
        try {
            model.rowCount = 0
            anchors.clear()
            for (a in m.anchors()) {
                model.addRow(
                    arrayOf<Any>(
                        NotesDataset.noteDone(a),
                        NotesDataset.noteText(a),
                        NotesDataset.noteType(a),
                        NotesDataset.noteSeverity(a),
                    ),
                )
                anchors.add(a)
            }
        } finally {
            loading = false
        }
        updateTitle()
    }

    private fun addAnchorRow(anchor: OsmPrimitive) {
        loading = true
        try {
            model.addRow(
                arrayOf<Any>(
                    NotesDataset.noteDone(anchor),
                    NotesDataset.noteText(anchor),
                    NotesDataset.noteType(anchor),
                    NotesDataset.noteSeverity(anchor),
                ),
            )
            anchors.add(anchor)
        } finally {
            loading = false
        }
        updateTitle()
    }

    private fun updateTitle() {
        val total = anchors.size
        val done = anchors.count { NotesDataset.noteDone(it) }
        setTitle("$PANEL_NAME - ${total - done} open / $total total")
    }

    private fun onModelChanged(ev: TableModelEvent) {
        val m = mgr
        if (loading || m == null) return
        if (ev.type != TableModelEvent.UPDATE) return
        val row = ev.firstRow
        val col = ev.column
        if (row < 0 || row >= anchors.size) return
        val a = anchors[row]
        when (col) {
            COL_DONE -> {
                val done = asBool(model.getValueAt(row, COL_DONE))
                m.setNoteField(a, NotesTags.TAG_DONE, if (done) "yes" else "no", propagate = true)
                updateTitle()
            }
            COL_TEXT -> m.setNoteField(a, NotesTags.TAG_TEXT, model.getValueAt(row, COL_TEXT)?.toString() ?: "")
            COL_TYPE -> m.setNoteField(
                a,
                NotesTags.TAG_TYPE,
                model.getValueAt(row, COL_TYPE)?.toString() ?: "issue",
                propagate = true,
            )
            COL_SEVERITY -> m.setNoteField(
                a,
                NotesTags.TAG_SEVERITY,
                model.getValueAt(row, COL_SEVERITY)?.toString() ?: "minor",
                propagate = true,
            )
        }
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        updateSaveButton()
        scheduleAutosave()
    }

    private fun markSaved() {
        dirty = false
        updateSaveButton()
    }

    private fun notesFileTooltip(): String {
        val m = mgr ?: return "Save notes to the .notes file"
        val path = m.notesPath
        return if (dirty) "Save unsaved notes to:\n$path" else "Notes file is up to date:\n$path"
    }

    private fun updateSaveButton() {
        try {
            saveBtn.isEnabled = dirty
            saveBtn.toolTipText = notesFileTooltip()
        } catch (_: Exception) {
        }
    }

    private fun saveNow() {
        if (!ensureManager()) return
        flushSave()
    }

    private fun selectedModelRows(): List<Int> {
        val out = ArrayList<Int>()
        for (vr in table.selectedRows) {
            try {
                out.add(table.convertRowIndexToModel(vr))
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun selectedAnchors(): List<OsmPrimitive> =
        selectedModelRows().mapNotNull { r -> anchors.getOrNull(r) }

    private fun selectedGroups(): List<OsmPrimitive> {
        val m = mgr ?: return emptyList()
        val out = ArrayList<OsmPrimitive>()
        val seen = HashSet<Long>()
        for (a in selectedAnchors()) {
            for (p in m.groupFor(a)) {
                val uid = p.uniqueId
                if (uid !in seen) {
                    seen.add(uid)
                    out.add(p)
                }
            }
        }
        return out
    }

    private fun isNotesLayerActive(): Boolean {
        return try {
            MainApplication.getLayerManager().activeLayer === mgr?.layer
        } catch (_: Exception) {
            false
        }
    }

    private fun referencedPrims(sel: List<OsmPrimitive>): List<OsmPrimitive> {
        val base = mgr?.baseLayer ?: return emptyList()
        val data = base.data ?: return emptyList()
        val found = ArrayList<OsmPrimitive>()
        val seen = HashSet<Long>()
        for (a in sel) {
            for (tok in NotesDataset.noteRefs(a).split(Regex("\\s+"))) {
                if (tok.isEmpty()) continue
                val prim = NotesDataset.resolveRef(data, tok) ?: continue
                val uid = prim.uniqueId
                if (uid in seen) continue
                seen.add(uid)
                found.add(prim)
            }
        }
        return found
    }

    private fun selectNoteGroupOnMap(prims: Collection<OsmPrimitive>) {
        val base = mgr?.baseLayer
        try {
            base?.data?.clearSelection()
        } catch (_: Exception) {
        }
        mgr?.selectOnMap(prims)
        zoomToPrimitives(prims)
    }

    private fun selectRefsOnBase(prims: Collection<OsmPrimitive>) {
        val base = mgr?.baseLayer ?: return
        val data = base.data ?: return
        mgr?.clearMapSelection()
        try {
            data.setSelected(ArrayList(prims))
        } catch (_: Exception) {
        }
        zoomToPrimitives(prims)
    }

    private fun onRowSelection() {
        val m = mgr
        if (loading || suppressSel || m == null) return
        val sel = selectedAnchors()
        if (sel.isEmpty()) return
        if (isNotesLayerActive()) {
            val prims = selectedGroups()
            if (prims.isNotEmpty()) selectNoteGroupOnMap(prims)
        } else {
            val refs = referencedPrims(sel)
            if (refs.isNotEmpty()) {
                selectRefsOnBase(refs)
            } else {
                val prims = selectedGroups()
                if (prims.isNotEmpty()) selectNoteGroupOnMap(prims)
            }
        }
    }

    private fun applyFilter() {
        val q = search.text
        val nd = chkNotDone.isSelected
        if (q.isNullOrEmpty() && !nd) {
            sorter.rowFilter = null
        } else {
            sorter.rowFilter = object : RowFilter<DefaultTableModel, Int>() {
                override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                    return try {
                        rowMatchesFilter(
                            q,
                            nd,
                            asBool(entry.getValue(COL_DONE)),
                            entry.getValue(COL_TEXT)?.toString(),
                            entry.getValue(COL_TYPE)?.toString(),
                            entry.getValue(COL_SEVERITY)?.toString(),
                        )
                    } catch (_: Exception) {
                        true
                    }
                }
            }
        }
    }

    private fun newNote() {
        if (!ensureManager()) return
        val m = mgr ?: return
        val base = m.baseLayer
        val selection = try {
            if (base.data != null) ArrayList(base.data.selected) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val prompt = promptNewNote(this) ?: return
        val (text, ntype, severity) = prompt
        val refs = if (selection.isNotEmpty()) {
            selection.map { NotesDataset.refToken(it) }.filter { it.isNotEmpty() }.joinToString(" ")
        } else {
            ""
        }
        val tags = baseTags(text, ntype, severity, refs)

        var anchor: Node? = null
        if (selection.isNotEmpty()) {
            anchor = m.addNoteFromSelection(selection, tags)
        }
        if (anchor == null) {
            val ll = mapCenterLatLon()
            if (ll == null) {
                warn("Could not determine the map center. Open a map first.")
                return
            }
            anchor = m.addPointNote(ll.first, ll.second, tags)
        }
        addAnchorRow(anchor)
        markDirty()
        selectNoteGroupOnMap(m.groupFor(anchor))
        activateBaseLayer()
    }

    private fun switchLayer() {
        if (mgr != null && isNotesLayerActive()) {
            activateBaseLayer()
            return
        }
        if (!ensureManager()) return
        activateNotesLayer()
    }

    private fun activateBaseLayer() {
        val m = mgr ?: return
        if (isNotesLayerActive()) markDirty()
        try {
            MainApplication.getLayerManager().activeLayer = m.baseLayer
        } catch (_: Exception) {
        }
        flushSave()
        updateLayerButton()
    }

    private fun activateNotesLayer() {
        val m = mgr ?: return
        if (!ensureNotesLayer()) return
        val lyr = m.layer ?: return
        try {
            MainApplication.getLayerManager().activeLayer = lyr
        } catch (_: Exception) {
        }
        updateLayerButton()
    }

    private fun updateLayerButton() {
        try {
            if (isNotesLayerActive()) {
                layerBtn.text = "Map layer"
                layerBtn.toolTipText = "Switch to the main map layer"
            } else {
                layerBtn.text = "Notes layer"
                layerBtn.toolTipText = "Switch to the notes layer to edit note geometry"
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteSelected() {
        val m = mgr ?: return
        val sel = selectedAnchors()
        if (sel.isEmpty()) return
        val snaps = ArrayList<NoteSnapshot>()
        val prims = ArrayList<OsmPrimitive>()
        for (a in sel) {
            snaps.addAll(m.snapshotGroup(a))
            prims.addAll(m.groupFor(a))
        }
        m.deletePrimitives(prims)
        m.undoStack.push(snaps)
        reloadRows()
        markDirty()
    }

    private fun undoDelete() {
        val m = mgr ?: return
        val snaps = m.undoStack.pop()
        if (snaps == null) {
            warn("Nothing to undo.")
            return
        }
        m.restorePrims(snaps)
        reloadRows()
        markDirty()
    }

    private fun loadNotesFile() {
        if (!ensureManager()) return
        val m = mgr ?: return
        if (dirty) {
            val res = JOptionPane.showConfirmDialog(
                this,
                "Save unsaved changes before loading another notes file?",
                PANEL_NAME,
                JOptionPane.YES_NO_CANCEL_OPTION,
            )
            if (res == JOptionPane.CANCEL_OPTION) return
            if (res == JOptionPane.YES_OPTION && !flushSave()) return
        }

        var mapDir: File? = null
        val companion = m.defaultNotesPath
        try {
            val assoc = m.baseLayer.associatedFile
            if (assoc != null) {
                mapDir = assoc.parentFile ?: assoc.absoluteFile.parentFile
            }
        } catch (_: Exception) {
        }
        if (mapDir == null && companion.isNotEmpty()) {
            mapDir = File(companion).parentFile
        }

        val fc = JFileChooser(mapDir)
        fc.dialogTitle = "Load notes file"
        fc.fileSelectionMode = JFileChooser.FILES_ONLY
        fc.fileFilter = FileNameExtensionFilter("Lanelet2 notes (*.notes)", "notes")
        if (companion.isNotEmpty() && File(companion).isFile) {
            fc.selectedFile = File(companion)
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val chosen = fc.selectedFile ?: return
        val (ok, err) = m.loadFromPath(chosen.absolutePath)
        if (!ok) {
            warn(err ?: "Load failed.")
            return
        }
        reloadRows()
        markSaved()
        updateLayerButton()
    }

    private fun ioDialog() {
        val choice = JOptionPane.showOptionDialog(
            this,
            "Export notes as plain text, or import notes from pasted text.",
            "Notes I/O",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf("Export", "Import"),
            "Export",
        )
        when (choice) {
            0 -> exportNotes()
            1 -> importNotes()
        }
    }

    private fun exportNotes() {
        var sel = selectedAnchors()
        if (sel.isEmpty()) sel = ArrayList(anchors)
        if (sel.isEmpty()) {
            warn("No notes to export.")
            return
        }
        val notes = sel.map { a ->
            val coord = NotesDataset.anchorCoord(a)
            ExportNote(
                id = NotesDataset.noteId(a).ifEmpty { newNoteId() },
                severity = NotesDataset.noteSeverity(a),
                type = NotesDataset.noteType(a),
                done = NotesDataset.noteDone(a),
                text = NotesDataset.noteText(a),
                lat = coord?.first,
                lon = coord?.second,
                refs = NotesDataset.noteRefs(a),
            )
        }
        showTextDialog(this, "Export notes (copy this text)", exportNotesText(notes), editable = false)
    }

    private fun importNotes() {
        val m = mgr ?: return
        val text = showTextDialog(this, "Import notes (paste text, then OK)", "", editable = true) ?: return
        val existing = HashSet(anchors.map { NotesDataset.noteId(it) }.filter { it.isNotEmpty() })
        var added = 0
        var skipped = 0
        for (block in parseImport(text)) {
            val nid = block.id ?: newNoteId()
            if (nid in existing) {
                skipped += 1
                continue
            }
            val coord = block.coord
            if (coord == null) {
                skipped += 1
                continue
            }
            val tags = linkedMapOf(
                NotesTags.TAG_MARKER to "yes",
                NotesTags.TAG_TEXT to block.text,
                NotesTags.TAG_TYPE to block.type,
                NotesTags.TAG_SEVERITY to block.severity,
                NotesTags.TAG_DONE to block.done,
                NotesTags.TAG_REFS to block.refs,
                NotesTags.TAG_ID to nid,
                NotesTags.TAG_CREATED to nowIso(),
                NotesTags.TAG_AUTHOR to gitAuthor(),
            )
            val anchor = m.addPointNote(coord.first, coord.second, tags)
            addAnchorRow(anchor)
            existing.add(nid)
            added += 1
        }
        if (added > 0) {
            markDirty()
            flushSave()
        }
        Dialogs.infoAutoClose("Imported $added note(s); skipped $skipped.", PANEL_NAME, 2500)
    }

    private fun scheduleAutosave() {
        if (saveTimer.isRunning) saveTimer.restart() else saveTimer.start()
    }

    fun flushSave(): Boolean {
        val m = mgr ?: return true
        try {
            if (saveTimer.isRunning) saveTimer.stop()
        } catch (_: Exception) {
        }
        if (!dirty) return true
        val (ok, err) = m.save()
        if (ok) markSaved() else warn("Save failed: $err")
        return ok
    }

    private fun warn(msg: String) {
        Dialogs.warn(msg, PANEL_NAME)
    }
}

private class NotesTableModel : DefaultTableModel() {
    init {
        setColumnIdentifiers(arrayOf("Done", "Note", "Type", "Severity"))
    }

    override fun getColumnClass(col: Int): Class<*> =
        if (col == COL_DONE) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(row: Int, col: Int): Boolean = true
}

private class StarRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        col: Int,
    ): java.awt.Component {
        val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
        val sev = value?.toString()?.ifEmpty { "minor" } ?: "minor"
        val rank = severityRank(sev)
        (comp as JLabel).text = "*".repeat(rank + 1)
        comp.toolTipText = sev
        try {
            comp.font = comp.font.deriveFont(Font.BOLD, 15.0f)
            if (!isSelected) {
                comp.foreground = SEVERITY_COLORS[sev] ?: Color.GRAY
            }
        } catch (_: Exception) {
        }
        return comp
    }
}

private class ShortcutSafeComboEditor : BasicComboBoxEditor() {
    override fun createEditorComponent(): javax.swing.JTextField = DisableShortcutsOnFocusGainedTextField()
}

internal fun promptNewNote(parent: java.awt.Component): Triple<String, String, String>? {
    val panel = JPanel(BorderLayout())
    val textArea = JTextArea(4, 30)
    textArea.lineWrap = true
    textArea.wrapStyleWord = true
    val textScroll = JScrollPane(textArea)
    textScroll.border = BorderFactory.createTitledBorder("Note text")

    val fields = JPanel(FlowLayout(FlowLayout.LEFT))
    val typeCombo = JComboBox(NotesTags.NOTE_TYPES.toTypedArray())
    typeCombo.isEditable = true
    val sevCombo = JComboBox(NotesTags.SEVERITIES.toTypedArray())
    fields.add(JLabel("Type:"))
    fields.add(typeCombo)
    fields.add(JLabel("Severity:"))
    fields.add(sevCombo)

    panel.add(textScroll, BorderLayout.CENTER)
    panel.add(fields, BorderLayout.SOUTH)
    panel.preferredSize = Dimension(420, 200)

    val res = JOptionPane.showConfirmDialog(
        parent,
        panel,
        "New Note",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE,
    )
    if (res != JOptionPane.OK_OPTION) return null
    val text = textArea.text.trim()
    val ntype = (typeCombo.selectedItem?.toString() ?: "issue").trim().ifEmpty { "issue" }
    val severity = sevCombo.selectedItem?.toString() ?: "minor"
    return Triple(text, ntype, severity)
}

internal fun showTextDialog(parent: java.awt.Component, title: String, content: String, editable: Boolean): String? {
    val area = JTextArea(content, 18, 60)
    area.isEditable = editable
    area.lineWrap = false
    val scroll = JScrollPane(area)
    scroll.preferredSize = Dimension(560, 360)
    val opt = if (editable) JOptionPane.OK_CANCEL_OPTION else JOptionPane.DEFAULT_OPTION
    val res = JOptionPane.showConfirmDialog(parent, scroll, title, opt, JOptionPane.PLAIN_MESSAGE)
    if (editable) {
        if (res != JOptionPane.OK_OPTION) return null
        return area.text
    }
    return content
}

internal fun mapCenterLatLon(): Pair<Double, Double>? {
    return try {
        val mv = MainApplication.getMap().mapView
        val en = mv.center
        val ll = ProjectionRegistry.getProjection().eastNorth2latlon(en)
        ll.lat() to ll.lon()
    } catch (_: Exception) {
        null
    }
}

internal fun zoomToPrimitives(prims: Collection<OsmPrimitive>) {
    if (prims.isEmpty()) return
    try {
        val mv = MainApplication.getMap() ?: return
        if (mv.mapView == null) return
        val visitor = BoundingXYVisitor()
        visitor.computeBoundingBox(ArrayList(prims))
        val bounds = visitor.bounds ?: return
        visitor.enlargeBoundingBox()
        mv.mapView.zoomTo(bounds.center)
        mv.mapView.repaint()
    } catch (_: Exception) {
    }
}

internal fun activateNotesPanel(dlg: NotesToggleDialog, mapframe: org.openstreetmap.josm.gui.MapFrame) {
    try {
        mapframe.setDialogsPanelVisible(true)
    } catch (_: Exception) {
    }
    try {
        dlg.unfurlDialog()
    } catch (_: Exception) {
        try {
            if (!dlg.isDialogShowing) dlg.showDialog()
        } catch (_: Exception) {
        }
    }
    try {
        dlg.button?.isSelected = true
    } catch (_: Exception) {
    }
}
