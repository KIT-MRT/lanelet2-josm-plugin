package org.openstreetmap.josm.plugins.lanelet2.platform

import org.openstreetmap.josm.tools.ImageProvider
import org.openstreetmap.josm.tools.Logging
import javax.swing.ImageIcon

object Icons {
    fun icon(name: String?): ImageIcon? {
        if (name.isNullOrBlank()) return null
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return try {
            val loaded = ImageProvider("lanelet2", base)
                .setOptional(true)
                .setSuppressWarnings(true)
                .get()
            if (loaded == null) {
                Logging.warn("lanelet2: missing icon ''{0}''", base)
            }
            loaded
        } catch (e: Exception) {
            Logging.warn("lanelet2: failed to load icon ''{0}''", base)
            Logging.warn(e)
            null
        }
    }
}
