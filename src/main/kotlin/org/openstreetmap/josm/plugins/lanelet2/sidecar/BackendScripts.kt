package org.openstreetmap.josm.plugins.lanelet2.sidecar

/**
 * Names of the Python backend scripts shipped as jar resources under
 * `lanelet2/backends/` and extracted to the plugin user-data directory.
 *
 * Invoked as `python <script.py> <args>` (script-directory `sys.path`), not
 * `python -m ...`, matching the Jython `backend_runner` and the golden corpus.
 */
object BackendScripts {
    const val RESOURCE_DIR = "lanelet2/backends"
    const val VERSION_FILE = ".shipped_version"
    const val EXTRACT_SUBDIR = "backends"
    const val VENV_SUBDIR = "venv"
    const val SCRATCH_SUBDIR = "routing"

    /** Application directory under the XDG data home that holds the venv. */
    const val VENV_APP_DIR = "josm-lanelet2"

    const val POSITIVE_IDS = "positive_ids"
    const val MERGE_OSM_FILES = "merge_osm_files"
    const val SPLIT_MERGED_OSM_FILE = "split_merged_osm_file"
    const val DEBUG_ROUTING_GRAPH = "server_create_debug_routing_graph_dataset"

    /**
     * Every file copied out of the jar. Helpers (`merge_anchor_utils`,
     * `split_safety_utils`) are imported by the merge/split scripts via
     * script-directory `sys.path`, so they must sit next to them.
     */
    val SHIPPED_FILES: List<String> = listOf(
        "__init__.py",
        "positive_ids.py",
        "merge_osm_files.py",
        "merge_anchor_utils.py",
        "split_merged_osm_file.py",
        "split_safety_utils.py",
        "server_create_debug_routing_graph_dataset.py",
        "requirements.txt",
    )
}
