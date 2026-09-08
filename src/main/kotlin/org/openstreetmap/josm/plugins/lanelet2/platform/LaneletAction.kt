package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.actions.JosmAction
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
    shortcutFor(name, tooltip, shortcutKey),
    false,
    null,
    false,
)

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
