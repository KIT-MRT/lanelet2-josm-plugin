package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.tools.Logging

/**
 * Entry point for the backends setup wizard. Opened from [Sidecar.ensureUsable]
 * when the user confirms, and from the routing settings panel.
 *
 * GUI implementation lives in [BackendSetupDialog]; this object stays
 * headless-safe and test-injectable.
 */
object BackendSetupWizard {
    const val TITLE = "Set up Lanelet2 backends"

    @Volatile
    var showHandler: ((UserPrompts) -> Unit)? = null

    fun show(ui: UserPrompts = Dialogs) {
        val handler = showHandler
        if (handler != null) {
            handler(ui)
            return
        }
        if (Dialogs.isHeadless()) {
            Logging.info("lanelet2: setup wizard skipped (headless)")
            ui.warn(
                "Set up Lanelet2 backends is a graphical wizard and is not available headless.",
                TITLE,
            )
            return
        }
        Logging.info("lanelet2: opening setup wizard")
        BackendSetupDialog.open(ui)
    }
}
