#!/usr/bin/env python3
"""Split a merged Lanelet2 OSM map back into its original per-file maps.

This is the inverse of ``merge_osm_files.py``. The merge tags every element
with ``file_origin`` (the source file path at merge time) and ``original_id``
(its id in that source file), plus optional git commit metadata. This script
regroups the merged map by ``file_origin``, restores each element's original
id, strips all merge-introduced tags, and writes one canonical lanelet2 OSM
file per source.

Because the lanelet2 OSM writer sorts output by id, restoring the original
ids reproduces the exact byte order of the canonical originals, so the result
is a lossless reconstruction (``diff`` against the normalized originals is
empty) -- as long as the input was actually produced by ``merge_osm_files.py``
(i.e. its elements carry ``file_origin`` tags; see the ``--dry-run`` warning
below for files that don't).

Standalone script - no dependency on this project's Jython tiers. Requires:
lanelet2.
"""

import argparse
import logging
import os
from collections import OrderedDict
from pathlib import Path
from typing import Dict, List, Optional

import lanelet2

# Single source of truth for the merge-introduced tags and anchor helpers.
from merge_anchor_utils import (
    FILE_ORIGIN_TAG,
    MERGE_ARTIFICIAL_TAGS,
    NODE_ID_B_TAG,
    ORIGINAL_ID_TAG,
    PEER_B_TAG,
    attr_get,
    attr_set_many,
    basename,
    build_anchor_metadata,
    detect_anchor_points,
    layers_in_order,
)
from split_safety_utils import (
    backup_files,
    find_git_repo,
    paths_dirty_in_git,
    resolve_backup_root,
)

NUM_LAYERS = 6
UNASSIGNED_KEY = "__unassigned__"


def _count_bucket_elements(layers_lists) -> int:
    return sum(len(layers_lists[i]) for i in range(NUM_LAYERS))


def _debug_split_buckets(buckets, output_mode: str, output_dir: Path, merged_stem: str) -> None:
    """Minimal debug: bucket keys, element counts, in-place target paths and on-disk status."""
    logging.info("[split debug] FILE_ORIGIN_TAG=%r output_mode=%s", FILE_ORIGIN_TAG, output_mode)
    logging.info("[split debug] buckets=%d (includes __unassigned__ if any untagged elements)", len(buckets))
    for origin_key in buckets.keys():
        n_elem = _count_bucket_elements(buckets[origin_key])
        out_path = _output_path_for(origin_key, output_dir, merged_stem, output_mode)
        on_disk = os.path.isfile(out_path) if origin_key != UNASSIGNED_KEY else False
        logging.info(
            "[split debug] bucket key=%r elements=%d target=%s exists=%s",
            origin_key, n_elem, out_path, on_disk,
        )


def _has_attr(elem, key: str) -> bool:
    return key in elem.attributes


def _get_attr(elem, key: str) -> Optional[str]:
    attrs = elem.attributes
    if key in attrs:
        return attrs[key]
    return None


def _strip_attrs(elem, keys) -> None:
    attrs = elem.attributes
    for key in keys:
        if key in attrs:
            del attrs[key]


def _make_ids_positive(out_map) -> int:
    """Give canonical positive ids to elements that still have a negative id.

    Newly drawn/pasted elements in the merged map have no ``original_id`` and
    keep their JOSM negative ids through the split; renumbering them (mirrors
    ``positive_ids.py``) keeps the reconstructed files in canonical lanelet2
    form. References are by object, so renumbering a point does not break
    ways/relations that contain it. Positive ids - restored ``original_id``s
    and allocated anchor-duplicate ids (recorded in peer metadata) - are left
    untouched. Returns the number of renumbered elements.
    """
    changed = 0
    for layer in layers_in_order(out_map):
        negatives = [elem for elem in layer if elem.id < 0]
        for elem in negatives:
            elem.id = layer.uniqueId()
            changed += 1
    return changed


def _output_path_for(origin_key: str, output_dir: Path, merged_stem: str, output_mode: str = "staging") -> Path:
    """Map a file_origin value (or the unassigned bucket) to an output path."""
    if output_mode == "in-place" and origin_key != UNASSIGNED_KEY:
        return Path(origin_key)
    if origin_key == UNASSIGNED_KEY:
        return output_dir / f"{merged_stem}_unassigned_ll2.osm"
    return output_dir / os.path.basename(origin_key)


def _collect_output_paths(buckets, output_dir: Path, merged_stem: str, output_mode: str) -> List[str]:
    paths = []
    for origin_key in buckets.keys():
        p = _output_path_for(origin_key, output_dir, merged_stem, output_mode)
        if origin_key != UNASSIGNED_KEY or output_mode == "staging":
            paths.append(str(p.resolve()))
    return paths


def _validate_in_place_write(target_paths: List[str], dry_run: bool = False) -> None:
    """Raise SystemExit if git repo is dirty for any target; backup non-git targets unless dry_run."""
    existing = [p for p in target_paths if os.path.isfile(p)]
    if not existing:
        return
    repo = find_git_repo(existing[0])
    if repo:
        dirty, dirty_rels = paths_dirty_in_git(repo, existing)
        if dirty:
            msg = "Refusing in-place split: git working tree has uncommitted changes for:\n"
            msg += "\n".join("  " + r for r in dirty_rels)
            raise SystemExit(msg)
        return
    if dry_run:
        return
    backup_dir = backup_files(existing, str(resolve_backup_root()))
    logging.info("Not a git repo: backed up %d file(s) to %s", len(existing), backup_dir)


def _apply_anchor_split(merged_map, buckets) -> int:
    """Duplicate cross-file boundary nodes into each involved file.

    For every anchor (a node referenced by ways from >= 2 files), the file
    that owns the node (file_origin) keeps it as peer A; every other involved
    file gets a freshly-id'd duplicate node carrying the symmetric
    merge-anchor metadata, and that file's ways are re-pointed to its
    duplicate. The metadata lets ``merge`` fuse the duplicates back into one
    shared node later.

    Mutates ``buckets`` (appends duplicate points, re-points per-file ways).
    Must run after grouping and before id-restore/strip, while file_origin /
    original_id tags are present and ids are still the merged global ids.
    Returns the number of anchors processed.
    """
    anchors = detect_anchor_points(merged_map)
    if not anchors:
        return 0

    # Per-file allocator for duplicate node ids (unique among that file's nodes).
    used_ids: Dict[str, set] = {}

    def used_for(file_origin: str) -> set:
        if file_origin not in used_ids:
            ids = set()
            point_bucket = buckets.get(file_origin, [[]])[0]
            for pt in point_bucket:
                oid = attr_get(pt, ORIGINAL_ID_TAG)
                if oid is not None:
                    try:
                        ids.add(int(oid))
                    except ValueError:
                        pass
            used_ids[file_origin] = ids
        return used_ids[file_origin]

    def alloc(file_origin: str) -> int:
        ids = used_for(file_origin)
        new_id = (max(ids) + 1) if ids else 1
        ids.add(new_id)
        return new_id

    processed = 0
    for point, by_fo in anchors:
        winner_fo = attr_get(point, FILE_ORIGIN_TAG)
        winner_oid = attr_get(point, ORIGINAL_ID_TAG)
        if winner_fo is None or winner_oid is None or winner_fo not in buckets:
            continue
        other_fos = [fo for fo in by_fo if fo is not None and fo != winner_fo]
        if not other_fos:
            continue
        winner_ways = by_fo.get(winner_fo, [])
        way_a_oid = attr_get(winner_ways[0], ORIGINAL_ID_TAG) if winner_ways else ""

        first_meta = None
        for other_fo in other_fos:
            if other_fo not in buckets:
                continue
            other_ways = by_fo[other_fo]
            way_b_oid = attr_get(other_ways[0], ORIGINAL_ID_TAG) if other_ways else ""

            # Reuse a previously-persisted duplicate id (keeps ids stable across
            # repeated split/merge cycles) when the winner already records it.
            dup_id = None
            recorded_peer = attr_get(point, PEER_B_TAG)
            if recorded_peer and basename(recorded_peer) == basename(other_fo):
                recorded_id = attr_get(point, NODE_ID_B_TAG)
                if recorded_id is not None:
                    try:
                        dup_id = int(recorded_id)
                    except ValueError:
                        dup_id = None
            if dup_id is None:
                dup_id = alloc(other_fo)

            meta = build_anchor_metadata(
                winner_fo, winner_oid, way_a_oid, other_fo, dup_id, way_b_oid
            )
            if first_meta is None:
                first_meta = meta

            dup = lanelet2.core.Point3d(dup_id, point.x, point.y, point.z)
            attr_set_many(dup, meta)
            for way in other_ways:
                for i in range(len(way)):
                    if way[i].id == point.id:
                        way[i] = dup
            buckets[other_fo][0].append(dup)

        if first_meta is not None:
            attr_set_many(point, first_meta)
            processed += 1

    return processed


def split_merged_map(
    merged_map: "lanelet2.core.LaneletMap",
    output_dir: Path,
    projector: "lanelet2.projection.UtmProjector",
    merged_stem: str = "merged",
    handle_anchors: bool = True,
    output_mode: str = "staging",
    dry_run: bool = False,
) -> Dict[str, str]:
    """Split a merged map into per-origin maps and write them to ``output_dir``.

    Returns a mapping of output path -> file_origin key.

    output_mode: ``staging`` (default) writes under output_dir; ``in-place``
    writes to each element's file_origin path (with git-dirty guard / backup
    fallback). When ``dry_run`` is True, only logs/resolves paths without
    writing.
    """
    # Pass 1: group element references by file_origin WITHOUT mutating the map.
    # Mutating ids/attributes while iterating the shared merged map corrupts its
    # internal index and cross-contaminates files, so grouping is read-only.
    buckets: "OrderedDict[str, List[list]]" = OrderedDict()
    tagged = 0
    untagged = 0
    for layer_idx, layer in enumerate(layers_in_order(merged_map)):
        for elem in list(layer):
            origin = _get_attr(elem, FILE_ORIGIN_TAG)
            if origin is not None:
                tagged += 1
            else:
                untagged += 1
            key = origin if origin is not None else UNASSIGNED_KEY
            if key not in buckets:
                buckets[key] = [[] for _ in range(NUM_LAYERS)]
            buckets[key][layer_idx].append(elem)

    logging.info(
        "[split debug] after grouping: tagged=%d untagged=%d distinct_origins=%d",
        tagged, untagged, len(buckets),
    )
    if untagged and untagged == tagged + untagged:
        logging.warning(
            "[split debug] no %r tags found on any element; was this file produced by merge_osm_files.py?",
            FILE_ORIGIN_TAG,
        )

    # Pass 1b: duplicate cross-file boundary nodes (merge anchors). Runs while
    # tags/ids are still intact and before any restore/strip mutation.
    if handle_anchors:
        num_anchors = _apply_anchor_split(merged_map, buckets)
        if num_anchors:
            logging.info(f"Duplicated {num_anchors} merge-anchor boundary node(s) across files")

    # Pass 2: restore original ids and strip every merge-introduced tag.
    for layers_lists in buckets.values():
        for layer_idx in range(NUM_LAYERS):
            for elem in layers_lists[layer_idx]:
                original_id = _get_attr(elem, ORIGINAL_ID_TAG)
                if original_id is not None:
                    elem.id = int(original_id)
                _strip_attrs(elem, MERGE_ARTIFICIAL_TAGS)

    # Pass 3: build a fresh map per origin and write it out.
    output_dir.mkdir(parents=True, exist_ok=True)
    _debug_split_buckets(buckets, output_mode, output_dir, merged_stem)
    target_paths = _collect_output_paths(buckets, output_dir, merged_stem, output_mode)
    logging.info("[split debug] in-place target_paths=%d: %s", len(target_paths), target_paths)
    if output_mode == "in-place":
        _validate_in_place_write(target_paths, dry_run=dry_run)

    written: Dict[str, str] = OrderedDict()
    for origin_key, layers_lists in buckets.items():
        out_path = _output_path_for(origin_key, output_dir, merged_stem, output_mode)
        if dry_run:
            logging.info("Would write %s (from %s)", out_path, origin_key)
            if origin_key != UNASSIGNED_KEY or output_mode == "staging":
                print("SPLIT_TARGET=%s" % out_path)
            written[str(out_path)] = origin_key
            continue
        out_map = lanelet2.core.LaneletMap()
        for layer_idx in range(NUM_LAYERS):
            for elem in layers_lists[layer_idx]:
                out_map.add(elem)
        renumbered = _make_ids_positive(out_map)
        if renumbered:
            logging.info(f"Renumbered {renumbered} new element(s) to positive ids for {origin_key}")
        if output_mode == "in-place" and origin_key != UNASSIGNED_KEY:
            out_path.parent.mkdir(parents=True, exist_ok=True)
        lanelet2.io.write(str(out_path), out_map, projector)
        written[str(out_path)] = origin_key
        logging.info(f"Wrote {out_path} (from {origin_key})")
    return written


def setup_logging():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Split a merged Lanelet2 OSM map back into its original per-file maps."
    )
    parser.add_argument(
        "--merged-file",
        type=str,
        required=True,
        help="Path to the merged .osm file produced by merge_osm_files.py",
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Output directory for the reconstructed per-file OSM maps",
    )
    parser.add_argument("--lat", type=float, default=49.0, help="Latitude for UTM projection (must match merge)")
    parser.add_argument("--lon", type=float, default=8.0, help="Longitude for UTM projection (must match merge)")
    parser.add_argument(
        "--no-anchors",
        dest="handle_anchors",
        action="store_false",
        help="Do not duplicate cross-file boundary nodes / write merge-anchor metadata",
    )
    parser.set_defaults(handle_anchors=True)
    parser.add_argument(
        "--output-mode",
        choices=("staging", "in-place"),
        default="staging",
        help="staging: write to --output dir; in-place: overwrite file_origin source paths",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List output paths only; with in-place, skip backup/write",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    setup_logging()

    merged_file = Path(args.merged_file).resolve()
    output_dir = Path(args.output).resolve()
    if not merged_file.is_file():
        raise SystemExit(f"Merged file not found: {merged_file}")

    projector = lanelet2.projection.UtmProjector(lanelet2.io.Origin(args.lat, args.lon))

    logging.info(f"Loading merged map {merged_file}")
    merged_map, loading_errors = lanelet2.io.loadRobust(str(merged_file), projector)
    for err in loading_errors:
        logging.warning(str(err))

    merged_stem = merged_file.stem.replace("_merged_ll2", "").replace("_merged", "")
    written = split_merged_map(
        merged_map, output_dir, projector,
        merged_stem=merged_stem, handle_anchors=args.handle_anchors,
        output_mode=args.output_mode, dry_run=args.dry_run,
    )

    if args.dry_run:
        logging.info("Dry run: would write %d files", len(written))
    else:
        logging.info(f"Split into {len(written)} files")


if __name__ == "__main__":
    main()
