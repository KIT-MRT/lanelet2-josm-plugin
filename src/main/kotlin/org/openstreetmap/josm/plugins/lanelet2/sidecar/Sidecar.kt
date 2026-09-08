package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.UserPrompts
import org.openstreetmap.josm.tools.Logging
import java.io.File

/**
 * Facade for the managed Python sidecar: extraction, interpreter resolution,
 * health check, and the "warn and do nothing" gate used by the four
 * lanelet2-library-dependent actions.
 *
 * If the sidecar is unhealthy the rest of the plugin must keep working —
 * [ensureUsable] never throws.
 */
object Sidecar {
    @Volatile
    var runnerOverride: BackendRunner? = null

    @Volatile
    var healthOverride: HealthReport? = null

    @Volatile
    var extractDirOverride: File? = null

    fun resetOverrides() {
        runnerOverride = null
        healthOverride = null
        extractDirOverride = null
    }

    fun backendsDir(): File {
        extractDirOverride?.let { return it }
        return BackendRunner.resolveBackendsDir()
    }

    fun runner(): BackendRunner = runnerOverride ?: BackendRunner()

    /**
     * Extract shipped scripts if needed and probe the configured interpreter.
     * Never throws.
     */
    fun health(): HealthReport {
        healthOverride?.let { return it }
        val extracted = try {
            val dir = extractDirOverride ?: BackendStore.defaultExtractDir()
            BackendStore.ensureExtracted(dir)
            true
        } catch (e: Exception) {
            Logging.warn("lanelet2: sidecar extraction failed: {0}", e.message)
            return HealthReport(
                SidecarProblem.BACKENDS_MISSING,
                detail = e.message ?: e.toString(),
                python = BackendRunner.resolvePython(),
            )
        }
        if (!extracted) {
            return HealthReport(SidecarProblem.BACKENDS_MISSING, detail = "extraction skipped")
        }
        val dir = backendsDir()
        val script = BackendStore.resolveScript(BackendScripts.POSITIVE_IDS, dir)
        if (script == null) {
            return HealthReport(
                SidecarProblem.BACKENDS_MISSING,
                detail = "positive_ids.py not found in ${dir.absolutePath}",
                python = BackendRunner.resolvePython(),
            )
        }
        val py = BackendRunner.resolvePython()
        return SidecarHealth.probe(py)
    }

    /**
     * If unhealthy, warn (naming the problem and pointing at the setup wizard)
     * and return false. Never throws.
     */
    fun ensureUsable(title: String, ui: UserPrompts = Dialogs): Boolean {
        return try {
            val report = health()
            if (report.healthy) return true
            ui.warn(report.userMessage(), title)
            if (ui.confirm("Open the Set up Lanelet2 backends wizard now?", title)) {
                try {
                    BackendSetupWizard.show(ui)
                } catch (e: Exception) {
                    Logging.warn("lanelet2: setup wizard failed to open: {0}", e.message)
                    ui.warn(
                        "Could not open the setup wizard:\n${e.message}\n\n" +
                            "Open Lanelet2 Settings → Set up Lanelet2 backends.",
                        title,
                    )
                }
            }
            false
        } catch (e: Exception) {
            Logging.warn("lanelet2: sidecar health check failed: {0}", e.message)
            try {
                ui.warn(
                    "Lanelet2 backends are unavailable (${e.message}).\n\n" +
                        "Open Lanelet2 Settings and run “Set up Lanelet2 backends”.",
                    title,
                )
            } catch (_: Exception) {
            }
            false
        }
    }
}
