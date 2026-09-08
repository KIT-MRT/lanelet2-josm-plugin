package org.openstreetmap.josm.plugins.lanelet2.testutil

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Access to the Jython 2.7 collection these ports are checked against.
 *
 * Several parity tests read the original `*_registry.py` files directly, which
 * is deliberate: comparing against the live source beats copying its tuples
 * into a test fixture that then silently rots. But that collection is a sibling
 * checkout rather than part of this repo, so the reads are guarded — where it is
 * absent (CI with only this repo checked out) the parity tests skip instead of
 * failing. Override the location with `-Dlanelet2.jythonSource=...` or
 * `LL2_JYTHON_SOURCE`.
 */
object JythonSources {

    private val root: File = File(
        System.getProperty("lanelet2.jythonSource")
            ?: System.getenv("LL2_JYTHON_SOURCE")
            ?: "/ll2_tooling_root/JOSM_lanelet2_editing_scripts",
    )

    /** Reads [relPath] from the collection, skipping the test if it is missing. */
    fun readText(relPath: String): String {
        val file = File(root, relPath)
        assumeTrue(file.isFile, "Jython source not available: $file")
        return file.readText()
    }
}
