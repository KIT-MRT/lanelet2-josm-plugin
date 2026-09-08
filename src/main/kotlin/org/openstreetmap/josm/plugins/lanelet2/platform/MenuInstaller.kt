package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.plugins.lanelet2.tools.QuickTagModal
import org.openstreetmap.josm.tools.Logging
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JToolBar
import javax.swing.SwingUtilities

object MenuInstaller {
    const val TOOLBAR_CONTAINER_NAME = "Lanelet2ToolbarContainer"
    const val UTILS_MENU_TITLE = "Lanelet2 Utils"
    const val MAP_MENU_TITLE = "Lanelet2"

    private val menuTitles = setOf(UTILS_MENU_TITLE, MAP_MENU_TITLE)

    private var defaultUiListener: Runnable? = null
    private val toggleGroups = mutableListOf<ToggleGroupState>()
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
        val bg = ButtonGroup()
        val byValue = LinkedHashMap<String, JRadioButton>()
        for (item in bufferItems) {
            val rb = JRadioButton(item.action)
            rb.isOpaque = false
            rb.text = item.toolbarLabel
            Icons.icon(item.iconName)?.let { rb.icon = it }
            rb.toolTipText = tooltipFor(item)
            bg.add(rb)
            tb.add(rb)
            groupValue(item.id)?.let { byValue[it] = rb }
        }
        if (groupKind != null) {
            toggleGroups.add(ToggleGroupState(bg, byValue, groupKind))
        }
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
        val byValue: Map<String, JRadioButton>,
        val kind: String,
    )
}
