package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings

/** Settings for the live 3D viewer bridge (`viewer3d.*` keys, `"1"`/`"0"` booleans). */
object Viewer3dSettings {
    const val KEY_ENABLED = "viewer3d.enabled"
    const val KEY_HOST = "viewer3d.host"
    const val KEY_PORT = "viewer3d.port"
    const val KEY_HTTP_PORT = "viewer3d.http_port"
    const val KEY_PROFILE = "viewer3d.profile"
    const val KEY_CULL_ENABLED = "viewer3d.cull.enabled"
    const val KEY_CULL_RANGE_M = "viewer3d.cull.range_m"
    const val KEY_FOLLOW_VIEW = "viewer3d.follow_view"
    const val KEY_DRIVE_VIEW = "viewer3d.drive_view"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_INGEST_PORT = 8766
    const val DEFAULT_HTTP_PORT = 8765
    const val DEFAULT_CULL_RANGE_M = 300

    fun isEnabled(default: Boolean = false): Boolean =
        LaneletSettings.getBoolean(KEY_ENABLED, default)

    fun getHost(default: String = DEFAULT_HOST): String =
        LaneletSettings.get(KEY_HOST, default).ifBlank { default }

    fun getIngestPort(default: Int = DEFAULT_INGEST_PORT): Int =
        LaneletSettings.getInt(KEY_PORT, default)

    fun getHttpPort(default: Int = DEFAULT_HTTP_PORT): Int =
        LaneletSettings.getInt(KEY_HTTP_PORT, default)

    fun profileEnabled(default: Boolean = false): Boolean =
        LaneletSettings.getBoolean(KEY_PROFILE, default)

    fun cullEnabled(default: Boolean = false): Boolean =
        LaneletSettings.getBoolean(KEY_CULL_ENABLED, default)

    fun getCullRangeM(default: Int = DEFAULT_CULL_RANGE_M): Int {
        val raw = LaneletSettings.getInt(KEY_CULL_RANGE_M, default).toDouble()
        return raw.coerceAtLeast(10.0).toInt()
    }

    /** Defaults **off** in the Jython settings file. */
    fun followViewEnabled(default: Boolean = false): Boolean =
        LaneletSettings.getBoolean(KEY_FOLLOW_VIEW, default)

    /** Defaults **on** in the Jython settings file. */
    fun driveViewEnabled(default: Boolean = true): Boolean =
        LaneletSettings.getBoolean(KEY_DRIVE_VIEW, default)

    fun saveConfig(
        enabled: Boolean,
        host: String,
        ingestPort: Int,
        httpPort: Int = getHttpPort(),
        profile: Boolean? = null,
        cullOn: Boolean? = null,
        cullRangeM: Int? = null,
        followView: Boolean? = null,
        driveView: Boolean? = null,
    ) {
        LaneletSettings.putBoolean(KEY_ENABLED, enabled)
        LaneletSettings.put(KEY_HOST, host.ifBlank { DEFAULT_HOST })
        LaneletSettings.putInt(KEY_PORT, ingestPort)
        LaneletSettings.putInt(KEY_HTTP_PORT, httpPort)
        if (profile != null) LaneletSettings.putBoolean(KEY_PROFILE, profile)
        if (cullOn != null) LaneletSettings.putBoolean(KEY_CULL_ENABLED, cullOn)
        if (cullRangeM != null) LaneletSettings.putInt(KEY_CULL_RANGE_M, cullRangeM.coerceAtLeast(10))
        if (followView != null) LaneletSettings.putBoolean(KEY_FOLLOW_VIEW, followView)
        if (driveView != null) LaneletSettings.putBoolean(KEY_DRIVE_VIEW, driveView)
    }
}
