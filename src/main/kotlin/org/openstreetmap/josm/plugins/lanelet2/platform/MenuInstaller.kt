package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.tools.QuickTagModal
import org.openstreetmap.josm.tools.Logging
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import javax.swing.UIManager

object MenuInstaller {
    const val TOOLBAR_CONTAINER_NAME = "Lanelet2ToolbarContainer"
    const val UTILS_MENU_TITLE = "Lanelet2 Utils"
    const val MAP_MENU_TITLE = "Lanelet2"

    private val menuTitles = setOf(UTILS_MENU_TITLE, MAP_MENU_TITLE)

    private var defaultUiListener: Runnable? = null
    private val toggleGroups = mutableListOf<ToggleGroupState>()
    private val highlightButtons = mutableListOf<HighlightButton>()
    private var installed = false

    fun install() {
        uninstall()
        installMenus()
        installToolbar()
        QuickTagModal.installShortcut()
        installed = true
    }

    fun uninstall() {
        unregisterDefaultUiListener()
        toggleGroups.clear()
        highlightButtons.clear()
        removeMenus()
        removeToolbar()
        installed = false
    }

    /**
     * Rebuild menus after a script [org.openstreetmap.josm.plugins.lanelet2.api.Lanelet2Extensions.register]s
     * a new slot. No-op when menus are not up (plugin init, headless tests).
     */
    fun refreshIfInstalled() {
        if (!installed) return
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        try {
            install()
        } catch (e: Exception) {
            Logging.debug("lanelet2: menu refresh skipped: {0}", e.message)
        }
    }

    private fun installMenus() {
        val mainMenu = MainApplication.getMenu() ?: return
        removeMenus()
        val utilsMenu = mainMenu.addMenu(
            UTILS_MENU_TITLE,
            UTILS_MENU_TITLE,
            KeyEvent.VK_U,
            mainMenu.defaultMenuPos,
            null,
        )
        addMenuItems(utilsMenu, ActionRegistry.INSTANCE.build(MenuId.UTILS))
        val mapMenu = mainMenu.addMenu(
            MAP_MENU_TITLE,
            MAP_MENU_TITLE,
            KeyEvent.VK_L,
            mainMenu.defaultMenuPos,
            null,
        )
        addMenuItems(mapMenu, ActionRegistry.INSTANCE.build(MenuId.MAP))
    }

    private fun addMenuItems(menu: JMenu, slots: List<ActionSlot?>) {
        for (slot in slots) {
            if (slot == null) {
                menu.addSeparator()
            } else {
                (slot.action as? LaneletAction)?.bindShortcutToWindow()
                menu.add(JMenuItem(slot.action))
            }
        }
    }

    private fun removeMenus() {
        val menuBar = MainApplication.getMenu() ?: return
        for (i in menuBar.menuCount - 1 downTo 0) {
            val m = menuBar.getMenu(i)
            if (m != null && m.text in menuTitles) {
                menuBar.remove(m)
            }
        }
    }

    private fun installToolbar() {
        val frame = MainApplication.getMainFrame() ?: return
        val cp = frame.contentPane
        val layout = cp.layout
        if (layout !is BorderLayout) return
        val north = layout.getLayoutComponent(cp, BorderLayout.NORTH) ?: return

        val tb1 = JToolBar(UTILS_MENU_TITLE)
        tb1.isFloatable = false
        tb1.name = "Lanelet2Utils"
        addButtonsToToolbar(tb1, ActionRegistry.INSTANCE.build(MenuId.UTILS))

        val tb2 = JToolBar(MAP_MENU_TITLE)
        tb2.isFloatable = false
        tb2.name = "Lanelet2"
        addButtonsToToolbar(tb2, ActionRegistry.INSTANCE.build(MenuId.MAP))

        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.name = TOOLBAR_CONTAINER_NAME
        cp.remove(north)
        wrapper.add(north)
        wrapper.add(tb1)
        wrapper.add(tb2)
        cp.add(wrapper, BorderLayout.NORTH)
        cp.revalidate()
        cp.repaint()

        applyToolbarSelectionFromSettings()
        registerDefaultUiListener()
    }

    private fun removeToolbar() {
        val frame = MainApplication.getMainFrame() ?: return
        val cp = frame.contentPane
        val layout = cp.layout
        if (layout !is BorderLayout) return
        val north = layout.getLayoutComponent(cp, BorderLayout.NORTH) ?: return
        val northName = north.name ?: return
        if (TOOLBAR_CONTAINER_NAME !in northName) return
        if (north !is java.awt.Container || north.componentCount == 0) return
        val originalToolbar = north.getComponent(0)
        cp.remove(north)
        north.removeAll()
        cp.add(originalToolbar, BorderLayout.NORTH)
        cp.revalidate()
        cp.repaint()
    }

    private fun addButtonsToToolbar(tb: JToolBar, items: List<ActionSlot?>) {
        val toggleBuf = mutableListOf<ActionSlot>()
        var toggleGroup: String? = null

        fun flushToggle() {
            if (toggleBuf.isEmpty()) return
            addToggleGroupToToolbar(tb, toggleBuf.toList(), toggleGroup)
            toggleBuf.clear()
        }

        for (item in items) {
            if (item == null) {
                flushToggle()
                toggleGroup = null
                tb.addSeparator()
                continue
            }
            if (item.toolbarLabel == null && item.iconName == null) {
                continue
            }
            if (item.toolbarHighlight != null) {
                flushToggle()
                toggleGroup = null
                (item.action as? LaneletAction)?.bindShortcutToWindow()
                val btn = buildHighlightButton(item)
                highlightButtons.add(HighlightButton(btn, item.toolbarHighlight))
                tb.add(btn)
                continue
            }
            val gid = item.toolbarGroup
            if (gid != null) {
                if (toggleGroup != gid) {
                    flushToggle()
                    toggleGroup = gid
                }
                toggleBuf.add(item)
                continue
            }
            flushToggle()
            toggleGroup = null
            (item.action as? LaneletAction)?.bindShortcutToWindow()
            val btn = JButton(item.action)
            btn.text = item.toolbarLabel
            Icons.icon(item.iconName)?.let { btn.icon = it }
            btn.toolTipText = tooltipFor(item)
            tb.add(btn)
        }
        flushToggle()
    }

    private fun addToggleGroupToToolbar(tb: JToolBar, bufferItems: List<ActionSlot>, groupKind: String?) {
        val group = buildToggleButtons(bufferItems)
        for (btn in group.buttons) tb.add(btn)
        if (groupKind != null) {
            toggleGroups.add(ToggleGroupState(group.buttonGroup, group.byValue, groupKind))
        }
    }

    /**
     * Build one mutually exclusive group of toolbar mode buttons.
     *
     * A JRadioButton draws its bullet with the button's default icon, so giving
     * it a slot icon leaves the active mode indistinguishable. Toggle buttons
     * keep the icon and carry their state in the border and background instead.
     */
    /**
     * Independent on/off button (autotag, zoom filter, 3D). Clicking opens the
     * config dialog; the highlight is then re-read from settings so Cancel does
     * not leave a stuck selected look.
     */
    internal fun buildHighlightButton(item: ActionSlot): JToggleButton {
        val btn = JToggleButton()
        btn.text = item.toolbarLabel
        Icons.icon(item.iconName)?.let { btn.icon = it }
        btn.toolTipText = tooltipFor(item)
        markSelectionVisibly(btn)
        val highlight = item.toolbarHighlight
        btn.addActionListener {
            try {
                item.action.actionPerformed(
                    java.awt.event.ActionEvent(btn, java.awt.event.ActionEvent.ACTION_PERFORMED, item.id),
                )
            } finally {
                btn.isSelected = highlight?.invoke() == true
            }
        }
        btn.isSelected = highlight?.invoke() == true
        return btn
    }

    internal fun buildToggleButtons(items: List<ActionSlot>): ToggleButtons {
        val bg = ButtonGroup()
        val buttons = ArrayList<JToggleButton>(items.size)
        val byValue = LinkedHashMap<String, JToggleButton>()
        for (item in items) {
            val btn = JToggleButton(item.action)
            btn.text = item.toolbarLabel
            Icons.icon(item.iconName)?.let { btn.icon = it }
            btn.toolTipText = tooltipFor(item)
            markSelectionVisibly(btn)
            bg.add(btn)
            buttons.add(btn)
            groupValue(item.id)?.let { byValue[it] = btn }
        }
        return ToggleButtons(bg, buttons, byValue)
    }

    internal data class ToggleButtons(
        val buttonGroup: ButtonGroup,
        val buttons: List<JToggleButton>,
        val byValue: Map<String, JToggleButton>,
    )

    /**
     * Some look and feels render a selected toolbar toggle almost identically to
     * an idle one, which is the whole point of these buttons, so the active mode
     * also gets an accent outline and a filled background.
     */
    private fun markSelectionVisibly(btn: JToggleButton) {
        val accent = UIManager.getColor("List.selectionBackground") ?: Color(0x2D, 0x7F, 0xF9)
        val idleBorder = BorderFactory.createEmptyBorder(3, 3, 3, 3)
        val activeBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent, 2),
            BorderFactory.createEmptyBorder(1, 1, 1, 1),
        )
        fun refresh() {
            btn.border = if (btn.isSelected) activeBorder else idleBorder
            btn.isOpaque = btn.isSelected
            btn.background = if (btn.isSelected) accentWash(accent) else null
        }
        btn.isContentAreaFilled = true
        btn.addItemListener { refresh() }
        refresh()
    }

    /** A tint of [accent] light enough to keep the icon and label readable. */
    private fun accentWash(accent: Color): Color {
        val base = UIManager.getColor("Panel.background") ?: Color.LIGHT_GRAY
        fun blend(a: Int, b: Int) = ((a * 0.30) + (b * 0.70)).toInt().coerceIn(0, 255)
        return Color(
            blend(accent.red, base.red),
            blend(accent.green, base.green),
            blend(accent.blue, base.blue),
        )
    }

    private fun tooltipFor(slot: ActionSlot): String {
        val fromAction = slot.action.getValue(Action.SHORT_DESCRIPTION) as? String
        if (!fromAction.isNullOrBlank()) return fromAction
        return slot.action.getValue(Action.NAME) as? String ?: slot.id
    }

    private fun groupValue(id: String): String? {
        val i = id.indexOf("::")
        return if (i >= 0) id.substring(i + 2) else null
    }

    private fun applyToolbarSelectionFromSettings() {
        for (group in toggleGroups) {
            when (group.kind) {
                ActionSlot.GROUP_LANELET_DEFAULT_SUBTYPE -> {
                    val cur = LaneletSettings.getLaneletDefaultSubtype()
                    if (cur in LaneletSettings.SUBTYPE_TOOLBAR_PRESETS) {
                        group.byValue[cur]?.isSelected = true
                    } else {
                        group.buttonGroup.clearSelection()
                    }
                }
                ActionSlot.GROUP_LANELET_DEFAULT_ONEWAY -> {
                    val cur = LaneletSettings.getLaneletDefaultOneWay()
                    group.byValue[cur]?.isSelected = true
                }
            }
        }
        for (hb in highlightButtons) {
            hb.button.isSelected = hb.isActive()
        }
    }

    private fun registerDefaultUiListener() {
        unregisterDefaultUiListener()
        val listener = Runnable {
            SwingUtilities.invokeLater {
                try {
                    applyToolbarSelectionFromSettings()
                } catch (_: Exception) {
                }
            }
        }
        defaultUiListener = listener
        LaneletSettings.registerLaneletDefaultUiListener(listener)
    }

    private fun unregisterDefaultUiListener() {
        val listener = defaultUiListener ?: return
        LaneletSettings.unregisterLaneletDefaultUiListener(listener)
        defaultUiListener = null
    }

    private data class ToggleGroupState(
        val buttonGroup: ButtonGroup,
        val byValue: Map<String, JToggleButton>,
        val kind: String,
    )

    private data class HighlightButton(
        val button: JToggleButton,
        val isActive: () -> Boolean,
    )
}
