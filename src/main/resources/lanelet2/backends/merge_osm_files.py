#!/usr/bin/env python3
"""Losslessly merge a set of Lanelet2 OSM files into a single output file.

Every element is tagged with where it came from (``file_origin``, the
original input file path) and its original id (``original_id``), plus
optional git commit metadata picked up from the input file's git history (if
any). This makes the merge reversible: ``split_merged_osm_file.py`` regroups
the merged map by ``file_origin``, restores each element's original id, and
strips the merge-introduced tags to reconstruct byte-identical copies of the
inputs.

Input is either an explicit list of ``.osm`` files (``--files``, any naming,
no required suffix) or a directory (``--input-dir``, all ``*.osm`` files found
directly inside it, non-recursive). Exactly one of the two must be given.
Output is a single merged ``.osm`` file (``--output``).

Optional ``--base`` names an already-merged map whose existing merge tags
(``file_origin``, ``original_id``, git metadata) and ids are copied through
unchanged. Only the files from ``--files`` / ``--input-dir`` are tagged and
remapped. The base file is skipped if it also appears in that added set.

Standalone script - no dependency on this project's Jython tiers. Requires:
lanelet2.
"""

import argparse
import glob
import logging
import os
import subprocess
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set

import lanelet2

from merge_anchor_utils import (
    COMMIT_INFO_TAGS,
    FILE_ORIGIN_TAG,
    ORIGINAL_ID_TAG,
    fuse_anchors,
    layers_in_order,
)


def _find_git_repo(file_path: str) -> Optional[str]:
    """Walk up from file's directory to find .git. Returns repo path or None."""
    path = os.path.abspath(file_path)
    if os.path.isfile(path):
        path = os.path.dirname(path)
    while path and path != os.path.dirname(path):
        if os.path.isdir(os.path.join(path, ".git")):
            return path
        path = os.path.dirname(path)
    return None


def _get_last_commit_info(file_path: str) -> Dict[str, str]:
    """Get last commit info for file, if it lives in a git repo.

    Returns a dict with keys last_modified (YY-MM-DD-HH-MM-SS), committer,
    commit_message, all_committers (comma-separated, most recent first,
    unique). Values are empty strings if the file is not in a git repo or git
    is unavailable/fails.
    """
    result = {"last_modified": "", "committer": "", "commit_message": "", "all_committers": ""}
    repo_dir = _find_git_repo(file_path)
    if not repo_dir:
        return result
    try:
        rel_path = os.path.relpath(os.path.abspath(file_path), repo_dir)
        proc = subprocess.run(
            ["git", "log", "-1", "--follow", "--format=%ci%n%cn%n%s", "--", rel_path],
            capture_output=True,
            text=True,
            cwd=repo_dir,
            timeout=5,
        )
        if proc.returncode != 0 or not proc.stdout.strip():
            return result
        lines = proc.stdout.strip().split("\n", 2)
        if len(lines) >= 1:
            # line 0: "2024-01-15 10:30:45 +0100"
            date_parts = lines[0].split()
            if len(date_parts) >= 2:
                y, m, d = date_parts[0].split("-")
                h, mi, s = date_parts[1].split(":")
                result["last_modified"] = "%s-%s-%s-%s-%s-%s" % (y[2:], m, d, h, mi, s)
        if len(lines) >= 2:
            result["committer"] = lines[1].strip()
        if len(lines) >= 3:
            result["commit_message"] = lines[2].strip().replace("\n", " ")

        proc_all = subprocess.run(
            ["git", "log", "--follow", "--format=%cn", "--", rel_path],
            capture_output=True,
            text=True,
            cwd=repo_dir,
            timeout=10,
        )
        if proc_all.returncode == 0 and proc_all.stdout.strip():
            seen = set()
            ordered = []
            for line in proc_all.stdout.strip().split("\n"):
                cn = line.strip()
                if cn and cn not in seen:
                    seen.add(cn)
                    ordered.append(cn)
            result["all_committers"] = ", ".join(ordered)
        return result
    except Exception:
        return result


def resolve_input_files(files: Optional[List[str]], input_dir: Optional[str]) -> List[str]:
    """Resolve the final, sorted list of input .osm files.

    Exactly one of ``files``/``input_dir`` is expected to be set (enforced by
    the CLI's mutually-exclusive group); this also tolerates being called
    directly with either.
    """
    if input_dir:
        found = sorted(glob.glob(os.path.join(input_dir, "*.osm")))
        return found
    if files:
        return sorted(files)
    return []


def load_lanelet_maps(
    osm_files: List[str],
    projector: "lanelet2.projection.UtmProjector",
) -> Dict[str, "lanelet2.core.LaneletMap"]:
    """Load multiple Lanelet2 maps from OSM files, skipping empty ones."""
    maps = {}
    for osm_file in osm_files:
        logging.info("Loading %s", osm_file)
        if Path(osm_file).stat().st_size == 0:
            logging.warning("File %s is empty, skipping", osm_file)
            continue
        ll2map, loading_errors = lanelet2.io.loadRobust(osm_file, projector)
        for err in loading_errors:
            logging.warning(str(err))
        maps[osm_file] = ll2map
    return maps


class _IdManager:
    """Hands out globally-unique ids for merged map elements."""

    def __init__(self):
        self._current_id = 1

    def seed_after(self, max_id: int) -> None:
        """Next ``get_next_id()`` returns at least ``max_id + 1``."""
        if max_id > self._current_id:
            self._current_id = max_id

    def get_next_id(self) -> int:
        self._current_id += 1
        return self._current_id


def _abs_path(path: str) -> str:
    return os.path.realpath(os.path.abspath(path))


def _max_id(ll2map: "lanelet2.core.LaneletMap") -> int:
    """Highest element id in the map (0 if empty / all non-positive)."""
    max_id = 0
    for layer in layers_in_order(ll2map):
        for elem in layer:
            if elem.id > max_id:
                max_id = elem.id
    return max_id


def _abspath_set(paths: Optional[Iterable[str]]) -> Set[str]:
    if not paths:
        return set()
    return set(_abs_path(p) for p in paths)


def _apply_commit_attrs(attrs, commit_info: Dict[str, str]) -> None:
    """Copy non-empty git commit metadata onto an element's attributes."""
    for key in COMMIT_INFO_TAGS:
        if commit_info.get(key):
            attrs[key] = commit_info[key]


def merge_ll2_maps(
    maps: Dict[str, "lanelet2.core.LaneletMap"],
    output_file: str,
    projector: "lanelet2.projection.UtmProjector",
    fuse_merge_anchors: bool = True,
    preserve_tags_for: Optional[Iterable[str]] = None,
) -> "lanelet2.core.LaneletMap":
    """Merge multiple Lanelet2 maps into one, preserving hierarchical relationships.

    Per source file that is *not* listed in ``preserve_tags_for``, three
    ordered passes are used:
      1. Tag every element (original_id, file_origin, git metadata).
      2. Reassign globally-unique ids.
      3. Add every element to the merged map.

    Files in ``preserve_tags_for`` (typically an already-merged ``--base``
    map) skip tagging and id remapping so existing ``file_origin`` /
    ``original_id`` / git tags stay intact. Their current ids are kept; the
    id allocator for newly tagged files starts after the highest kept id.

    Tagging must happen before any add because ``LaneletMap.add`` auto-adds
    referenced regulatory-elements/lanelets; if those are pulled in before the
    tag loop reaches them, they end up untagged and cannot be split back out.

    When ``fuse_merge_anchors`` is set, merge-anchor duplicate nodes produced
    by a previous split are fused back into single shared nodes (a no-op when
    the inputs contain no merge_anchor tags, so plain merges are unaffected).
    """
    preserve = _abspath_set(preserve_tags_for)
    base_items = []
    added_items = []
    for filename, ll2map in maps.items():
        if _abs_path(filename) in preserve:
            base_items.append((filename, ll2map))
        else:
            added_items.append((filename, ll2map))

    merged_map = lanelet2.core.LaneletMap()
    id_mgr = _IdManager()
    if base_items:
        max_kept = 0
        for _, ll2map in base_items:
            kept = _max_id(ll2map)
            if kept > max_kept:
                max_kept = kept
        id_mgr.seed_after(max_kept)
        logging.info("Preserving tags/ids on %d base map(s); new ids start after %d",
                     len(base_items), max_kept)

    for filename, ll2map in base_items + added_items:
        logging.info("Processing %s", filename)
        if _abs_path(filename) in preserve:
            logging.info("  keeping existing merge tags and ids")
        else:
            commit_info = _get_last_commit_info(filename)
            # Pass 1: tag all elements before any reassignment or add.
            for layer in layers_in_order(ll2map):
                for elem in layer:
                    elem.attributes[ORIGINAL_ID_TAG] = str(elem.id)
                    elem.attributes[FILE_ORIGIN_TAG] = filename
                    _apply_commit_attrs(elem.attributes, commit_info)
            # Pass 2: assign globally-unique ids.
            for layer in layers_in_order(ll2map):
                for elem in layer:
                    elem.id = id_mgr.get_next_id()

        # Pass 3: add to the merged map (referenced elements already tagged).
        for layer in layers_in_order(ll2map):
            for elem in layer:
                merged_map.add(elem)

    if fuse_merge_anchors:
        merged_map, fused = fuse_anchors(merged_map)
        if fused:
            logging.info("Fused %d merge-anchor duplicate node(s)", fused)

    lanelet2.io.write(output_file, merged_map, projector)
    return merged_map


def get_osm_stats(ll2map: "lanelet2.core.LaneletMap") -> Dict[str, int]:
    """Basic element-count statistics for a Lanelet2 map."""
    return {
        "nodes_n": len(ll2map.pointLayer),
        "ways_n": len(ll2map.lineStringLayer),
        "regulatoryElements_n": len(ll2map.regulatoryElementLayer),
        "lanelets_n": len(ll2map.laneletLayer),
        "areas_n": len(ll2map.areaLayer),
        "polygons_n": len(ll2map.polygonLayer),
    }


def setup_logging():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Losslessly merge a set of Lanelet2 OSM files into one output file."
    )
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument(
        "--files",
        type=str,
        nargs="+",
        help="Explicit list of .osm files to merge (any file naming).",
    )
    src.add_argument(
        "--input-dir",
        type=str,
        help="Merge every *.osm file found directly inside this directory (non-recursive).",
    )
    parser.add_argument(
        "--base",
        type=str,
        default=None,
        help="Already-merged .osm file to append onto. Its file_origin / "
             "original_id / git tags and ids are kept; only --files / "
             "--input-dir are newly tagged and remapped.",
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Path to write the merged .osm file to (parent directories are created).",
    )
    parser.add_argument(
        "--lat",
        type=float,
        default=49.0,
        help="Latitude reference point for the lanelet2 UTM projection (default 49.0). "
             "Only needs to be roughly near the mapped area and consistent between "
             "merge and split of the same map.",
    )
    parser.add_argument(
        "--lon",
        type=float,
        default=8.0,
        help="Longitude reference point for the lanelet2 UTM projection (default 8.0).",
    )
    parser.add_argument(
        "--no-fuse-anchors",
        dest="fuse_anchors",
        action="store_false",
        help="Do not fuse merge-anchor duplicate nodes (keep split boundary duplicates separate).",
    )
    parser.set_defaults(fuse_anchors=True)
    return parser.parse_args()


def main():
    args = parse_args()
    setup_logging()

    osm_files = resolve_input_files(args.files, args.input_dir)
    if not osm_files:
        raise SystemExit(
            "No .osm files to merge (input-dir=%r, files=%r)" % (args.input_dir, args.files)
        )

    base_path = None
    if args.base:
        base_path = _abs_path(args.base)
        if not os.path.isfile(base_path):
            raise SystemExit("Base file not found: %s" % args.base)

    added_files = []
    for f in osm_files:
        if base_path and _abs_path(f) == base_path:
            logging.info("Skipping %s (same as --base)", f)
            continue
        added_files.append(f)
    if not added_files:
        raise SystemExit(
            "No .osm files to append (input-dir=%r, files=%r, base=%r)"
            % (args.input_dir, args.files, args.base)
        )

    if base_path:
        logging.info("Appending %d file(s) onto base %s:", len(added_files), base_path)
    else:
        logging.info("Merging %d file(s):", len(added_files))
    for f in added_files:
        logging.info("  %s", f)

    output_file = Path(args.output).resolve()
    output_file.parent.mkdir(parents=True, exist_ok=True)

    projector = lanelet2.projection.UtmProjector(lanelet2.io.Origin(args.lat, args.lon))
    load_list = ([base_path] + added_files) if base_path else added_files
    maps = load_lanelet_maps(load_list, projector)
    if not maps:
        raise SystemExit("No maps could be loaded from the given input files")
    if base_path:
        loaded_abs = set(_abs_path(k) for k in maps)
        if base_path not in loaded_abs:
            raise SystemExit("Base map could not be loaded: %s" % base_path)
        if not any(_abs_path(k) != base_path for k in maps):
            raise SystemExit("No appended maps could be loaded")

    merged_map = merge_ll2_maps(
        maps,
        str(output_file),
        projector,
        fuse_merge_anchors=args.fuse_anchors,
        preserve_tags_for=[base_path] if base_path else None,
    )

    stats = get_osm_stats(merged_map)
    logging.info("Merged map statistics:")
    for key, value in stats.items():
        logging.info("  %s: %s", key, value)
    print("MERGE_OUTPUT=%s" % output_file)


if __name__ == "__main__":
    main()
