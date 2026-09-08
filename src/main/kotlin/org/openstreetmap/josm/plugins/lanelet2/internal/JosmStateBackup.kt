package org.openstreetmap.josm.plugins.lanelet2.internal

import java.io.File

/**
 * Sidecar backup written next to a layer `.osm` before a destructive rewrite.
 *
 * Port of `ll2_git_commit.py`: `backup_filename = "." + current_osm_filename + "backup"`.
 * For `map.osm` that is `.map.osmbackup` (not `.map.osm.osmbackup`). Filter-broken
 * uses the same scheme as the approved divergence from the Jython, which overwrote
 * in place with no backup.
 */
internal object JosmStateBackup {
    fun pathFor(file: File): File = File(file.parentFile, "." + file.name + "backup")

    fun copy(file: File): File {
        val dest = pathFor(file)
        file.copyTo(dest, overwrite = true)
        return dest
    }
}
