package org.openstreetmap.josm.plugins.lanelet2.platform

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.gui.MapFrame
import org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin

/**
 * JOSM has no map frame until a data layer is opened. The menus must already
 * be installable then, and closing the last layer must not take them away.
 */
class MenuInstallerLifecycleTest {

    @Test
    fun installMenusDoesNotNeedAMapFrame() {
        MenuInstaller.uninstall()
        MenuInstaller.installMenus()
        // Headless JOSM has no menu bar, so this is a no-op rather than a throw.
        assertFalse(MenuInstaller.toolbarIsInstalled())
    }

    @Test
    fun uninstallToolbarLeavesTheMenusFlagAlone() {
        MenuInstaller.uninstall()
        MenuInstaller.installMenus()
        val menus = MenuInstaller.menusAreInstalled()
        MenuInstaller.uninstallToolbar()
        assertFalse(MenuInstaller.toolbarIsInstalled())
        assertTrue(
            MenuInstaller.menusAreInstalled() == menus,
            "closing the map frame must not clear the menus",
        )
    }

    @Test
    fun mapFrameTeardownOnlyDropsTheToolbar() {
        MenuInstaller.uninstall()
        val plugin = allocate(Lanelet2Plugin::class.java)
        val frame = allocate(MapFrame::class.java)
        plugin.mapFrameInitialized(frame, null)
        assertFalse(MenuInstaller.toolbarIsInstalled())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, type) as T
    }
}
