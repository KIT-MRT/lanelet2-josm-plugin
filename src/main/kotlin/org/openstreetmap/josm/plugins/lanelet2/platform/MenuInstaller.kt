package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.tools.QuickTagModal
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener
import org.openstreetmap.josm.tools.Logging
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import javax.swing.UIManager

object MenuInstaller {
    const val TOOLBAR_CONTAINER_NAME = "Lanelet2ToolbarContainer"
    const val MAIN_TOGGLE_NAME = "Lanelet2ExtraToolbarToggle"
    const val UTILS_MENU_TITLE = "Lanelet2 Utils"
    const val MAP_MENU_TITLE = "Lanelet2"
    const val EXTRA_TOOLBAR_MENU_LABEL = "Show Lanelet2 toolbars"

    private val menuTitles = setOf(UTILS_MENU_TITLE, MAP_MENU_TITLE)

    private var defaultUiListener: Runnable? = null
    private val toggleGroups = mutableListOf<ToggleGroupState>()
    private val highlightButtons = mutableListOf<HighlightButton>()
    private var menusInstalled = false
    private var toolbarInstalled = false
    private var extraUtilsBar: JToolBar? = null
    private var extraMapBar: JToolBar? = null
    private var mainToggle: JToggleButton? = null
    private var extraToolbarMenuItem: JCheckBoxMenuItem? = null
    private var josmToolbarRebuildListener: PreferenceChangedListener? = null

    /** Menus + toolbar + Space shortcut. Used once a map frame exists. */
    fun install() {
        installMenus()
        installToolbar()
        QuickTagModal.installShortcut()
    }

    /**
     * Lanelet2 / Lanelet2 Utils in the menu bar, with no toolbar.
     *
     * JOSM has no map frame until a data layer is opened; waiting for that
     * left both menus empty at startup. The menu bar exists earlier.
     */
    fun installMenus() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        try {
            installMenusNow()
        } catch (e: Exception) {
            Logging.debug("lanelet2: menu install skipped: {0}", e.message)
        }
    }

    fun uninstall() {
        uninstallToolbar()
        removeMainToolbarToggle()
        unregisterJosmToolbarRebuildListener()
        removeMenus()
        menusInstalled = false
    }

    /**
     * Drop the extra toolbars when the last layer closes. Keep the menus so
     * Settings / 3D / hooks stay reachable with no dataset open.
     */
    fun uninstallToolbar() {
        unregisterDefaultUiListener()
        toggleGroups.clear()
        highlightButtons.clear()
        extraUtilsBar = null
        extraMapBar = null
        removeToolbar()
        toolbarInstalled = false
    }

    fun setExtraToolbarVisible(visible: Boolean) {
        LaneletSettings.setExtraToolbarVisible(visible)
        applyExtraToolbarVisibility()
    }

    fun applyExtraToolbarVisibility() {
        val show = LaneletSettings.isExtraToolbarVisible()
        extraUtilsBar?.isVisible = show
        extraMapBar?.isVisible = show
        mainToggle?.isSelected = show
        extraToolbarMenuItem?.isSelected = show
        extraUtilsBar?.parent?.revalidate()
        extraUtilsBar?.parent?.repaint()
        mainToggle?.parent?.revalidate()
    }

    internal fun menusAreInstalled(): Boolean = menusInstalled

    internal fun toolbarIsInstalled(): Boolean = toolbarInstalled

    /**
     * Rebuild menus after a script [org.openstreetmap.josm.plugins.lanelet2.api.Lanelet2Extensions.register]s
     * a new slot. No-op when menus are not up (plugin init, headless tests).
     */
    fun refreshIfInstalled() {
        if (!menusInstalled) return
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        try {
            if (toolbarInstalled) install() else installMenus()
        } catch (e: Exception) {
            Logging.debug("lanelet2: menu refresh skipped: {0}", e.message)
        }
    }

    private fun installMenusNow() {
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
        extraToolbarMenuItem = JCheckBoxMenuItem(EXTRA_TOOLBAR_MENU_LABEL).also { item ->
            item.isSelected = LaneletSettings.isExtraToolbarVisible()
            item.addActionListener { setExtraToolbarVisible(item.isSelected) }
            utilsMenu.insert(item, 0)
            utilsMenu.insertSeparator(1)
        }
        menusInstalled = true
        installMainToolbarToggle()
        registerJosmToolbarRebuildListener()
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
        extraToolbarMenuItem = null
        val menuBar = MainApplication.getMenu() ?: return
        for (i in menuBar.menuCount - 1 downTo 0) {
            val m = menuBar.getMenu(i)
            if (m != null && m.text in menuTitles) {
                menuBar.remove(m)
            }
        }
    }

    private fun installToolbar() {
        if (toolbarInstalled) uninstallToolbar()
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
        extraUtilsBar = tb1
        extraMapBar = tb2
        val show = LaneletSettings.isExtraToolbarVisible()
        tb1.isVisible = show
        tb2.isVisible = show

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
        installMainToolbarToggle()
        toolbarInstalled = true
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
        applyExtraToolbarVisibility()
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

    /**
     * A highlighted toggle on JOSM's own toolbar (presets row). It must not
     * live on the extra Lanelet2 rows — those are what it hides.
     */
    private fun installMainToolbarToggle() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        try {
            val tb = findJosmToolBar() ?: return
            val existing = tb.components.firstOrNull { it.name == MAIN_TOGGLE_NAME } as? JToggleButton
            val btn = existing ?: buildMainToolbarToggle().also { tb.add(it) }
            mainToggle = btn
            btn.isSelected = LaneletSettings.isExtraToolbarVisible()
            tb.revalidate()
            tb.repaint()
        } catch (e: Exception) {
            Logging.debug("lanelet2: main toolbar toggle skipped: {0}", e.message)
        }
    }

    internal fun buildMainToolbarToggle(): JToggleButton {
        val btn = JToggleButton()
        btn.name = MAIN_TOGGLE_NAME
        btn.text = "LL2"
        Icons.icon("icons/extra_toolbar.svg")?.let { btn.icon = it }
        btn.toolTipText = "Show or hide the Lanelet2 toolbars"
        markSelectionVisibly(btn)
        btn.addActionListener {
            setExtraToolbarVisible(btn.isSelected)
        }
        btn.isSelected = LaneletSettings.isExtraToolbarVisible()
        return btn
    }

    private fun removeMainToolbarToggle() {
        val btn = mainToggle
        mainToggle = null
        if (btn == null) return
        try {
            (btn.parent as? Container)?.remove(btn)
        } catch (_: Exception) {
        }
    }

    private fun findJosmToolBar(): JToolBar? {
        val frame = MainApplication.getMainFrame() ?: return null
        val cp = frame.contentPane
        val layout = cp.layout
        if (layout !is BorderLayout) return null
        val north = layout.getLayoutComponent(cp, BorderLayout.NORTH) ?: return null
        return findJosmToolBar(north)
    }

    private fun findJosmToolBar(root: Component): JToolBar? {
        if (root is JToolBar && root.name != "Lanelet2Utils" && root.name != "Lanelet2") {
            return root
        }
        if (root is Container) {
            if (root.name?.contains(TOOLBAR_CONTAINER_NAME) == true && root.componentCount > 0) {
                return findJosmToolBar(root.getComponent(0))
            }
            for (child in root.components) {
                findJosmToolBar(child)?.let { return it }
            }
        }
        return null
    }

    private fun registerJosmToolbarRebuildListener() {
        if (josmToolbarRebuildListener != null) return
        val listener = PreferenceChangedListener {
            SwingUtilities.invokeLater {
                try {
                    installMainToolbarToggle()
                } catch (_: Exception) {
                }
            }
        }
        josmToolbarRebuildListener = listener
        try {
            Config.getPref().addKeyPreferenceChangeListener("toolbar", listener)
        } catch (_: Exception) {
        }
    }

    private fun unregisterJosmToolbarRebuildListener() {
        val listener = josmToolbarRebuildListener ?: return
        try {
            Config.getPref().removeKeyPreferenceChangeListener("toolbar", listener)
        } catch (_: Exception) {
        }
        josmToolbarRebuildListener = null
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
