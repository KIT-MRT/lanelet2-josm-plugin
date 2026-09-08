package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.actions.JosmAction
import org.openstreetmap.josm.gui.MainApplication
import org.openstreetmap.josm.tools.ImageProvider
import org.openstreetmap.josm.tools.Logging
import org.openstreetmap.josm.tools.Shortcut
import java.awt.event.KeyEvent

abstract class LaneletAction(
    name: String,
    iconName: String?,
    tooltip: String?,
    shortcutKey: String?,
) : JosmAction(
    name,
    imageProviderFor(iconName),
    tooltip,
    // Pass null here: JosmAction.registerActionShortcut needs
    // MainApplication.contentPanePrivate, which is absent in headless tests
    // and can still be null during plugin construction. We bind [sc] below
    // and MenuInstaller retries once the UI exists.
    null,
    false,
    null,
    false,
) {
    init {
        val shortcut = shortcutFor(name, tooltip, shortcutKey)
        if (shortcut != null) {
            sc = shortcut
            bindShortcutToWindow()
            setTooltip(tooltip)
        }
    }

    fun bindShortcutToWindow() {
        val shortcut = sc ?: return
        // JosmAction's constructor skips automatic shortcuts (the user cannot
        // rebind those, and JOSM deliberately leaves them out of the input map).
        if (shortcut.isAutomatic) return
        try {
            MainApplication.registerActionShortcut(this, shortcut)
        } catch (e: Exception) {
            Logging.debug("lanelet2: shortcut not bound yet: {0}", e.message)
        }
    }
}

private fun imageProviderFor(iconName: String?): ImageProvider? {
    if (iconName.isNullOrBlank()) return null
    val base = iconName.substringAfterLast('/').substringAfterLast('\\')
    val provider = ImageProvider("lanelet2", base)
        .setOptional(true)
        .setSuppressWarnings(true)
    if (provider.resource == null) {
        Logging.warn("lanelet2: missing icon ''{0}''", base)
        return null
    }
    return provider
}

private fun shortcutFor(name: String, tooltip: String?, shortcutKey: String?): Shortcut? {
    if (shortcutKey.isNullOrBlank()) return null
    val keyCode = KeyEvent.getExtendedKeyCodeForChar(shortcutKey.trim().uppercase()[0].code)
    if (keyCode == KeyEvent.VK_UNDEFINED) return null
    return Shortcut.registerShortcut(
        "lanelet2:$name",
        tooltip ?: name,
        keyCode,
        Shortcut.CTRL_SHIFT,
    )
}
