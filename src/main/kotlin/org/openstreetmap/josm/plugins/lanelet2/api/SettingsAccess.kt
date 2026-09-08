package org.openstreetmap.josm.plugins.lanelet2.api

import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings

/**
 * Script-facing view of [LaneletSettings].
 *
 * Keys may be written with or without the `lanelet2.` prefix; it is added if
 * missing. Boolean values are stored as `"1"` / `"0"` so they stay compatible
 * with the legacy `~/.lanelet2_settings` file. JOSM's own `getBoolean` treats
 * only `"true"` as true, so scripts must use this class rather than
 * `Config.getPref().getBoolean`.
 *
 * Every read applies [default] in this class after calling JOSM with a null
 * default. Do not pass a real default into `IPreferences.get` for these keys.
 */
class SettingsAccess internal constructor() {

    /** String setting, or [default] when unset. */
    fun get(key: String, default: String): String = LaneletSettings.get(key, default)

    /** Write a string setting. `null` is stored as an empty string. */
    fun put(key: String, value: String?) {
        LaneletSettings.put(key, value)
    }

    /**
     * Boolean setting. Stored values `"1"` / `"true"` / `"yes"` are true;
     * `"0"` / `"false"` / `"no"` are false; anything else yields [default].
     */
    fun getBoolean(key: String, default: Boolean): Boolean =
        LaneletSettings.getBoolean(key, default)

    /** Persist a boolean as `"1"` or `"0"`. */
    fun putBoolean(key: String, value: Boolean) {
        LaneletSettings.putBoolean(key, value)
    }

    /** Integer setting, or [default] when unset or unparsable. */
    fun getInt(key: String, default: Int): Int = LaneletSettings.getInt(key, default)

    /** Persist an integer. */
    fun putInt(key: String, value: Int) {
        LaneletSettings.putInt(key, value)
    }
}
