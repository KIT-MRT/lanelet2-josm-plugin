"""Open-source Lanelet2 backend scripts for the JOSM Lanelet2 editing scripts.

These are standalone Python 3 scripts that use the upstream ``lanelet2`` Python
bindings. They are invoked as subprocesses by the Jython launchers in the
``ll2_dependent/`` tier of the parent project (e.g. the debug routing graph and
make-positive-IDs features). They can also be run directly from the command line.

Staged now:
    - ``positive_ids`` -- rewrite negative OSM IDs to positive IDs in place.
    - ``server_create_debug_routing_graph_dataset`` -- build a debug routing
      graph LaneletMap and write it as .osm (``--once`` mode for JOSM).
    - ``merge_osm_files`` -- losslessly merge a set of Lanelet2 OSM files
      (an explicit list, or every ``*.osm`` in a directory) into one file;
      ``--base`` appends onto an already-merged map without retagging it.
    - ``split_merged_osm_file`` -- reverse a merge: split a merged file back
      into its original per-file maps.
    - ``merge_anchor_utils`` / ``split_safety_utils`` -- shared helpers for
      the merge/split pair (tag schema, boundary-node fusion, git-aware
      in-place-write safety checks).
"""

__version__ = "0.1.0"
