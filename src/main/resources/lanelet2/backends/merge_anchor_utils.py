#!/usr/bin/env python3
"""Shared tags and helpers for the lossless merge/split and the merge-anchor tool.

This is the foundational module (no dependency on the merge/split scripts) that
owns:

- the merge-introduced tag schema (file_origin / original_id / git metadata),
- the merge-anchor tag schema (merge_anchor + peerA/B, node_idA/B, way_idA/B),
- small attribute helpers for lanelet2 primitives,
- anchor detection (split-side) and anchor fusion (merge-side).

Merge anchors
=============
After a merged map is connected in JOSM (a single boundary node becomes shared
by ways that originate from different files), splitting it back naively would
break the file that no longer owns the node. Instead, the split duplicates the
shared node into every involved file and records, on each copy, how to fuse them
again:

    merge_anchor = yes
    peerA = <file_origin path of the canonical/owning file>
    node_idA = <id of the node in file A>
    way_idA = <id of a way in file A using the node>
    peerB = <file_origin path of the peer file>
    node_idB = <id of the duplicated node in file B>
    way_idB = <id of a way in file B using the node>

Peer A is always the file that keeps the canonical node (its file_origin equals
the shared node's file_origin); peer B is the other file. For a node shared by
more than two files, one duplicate is created per extra file, each paired with
peer A. On merge these tags are used to fuse the duplicates back into a single
shared node, restoring connectivity across the boundary.

Standalone module. Requires: lanelet2.
"""

import logging
import os

import lanelet2

# --- Tags added by the lossless merge (stripped again on split) -------------
ORIGINAL_ID_TAG = "original_id"
FILE_ORIGIN_TAG = "file_origin"
COMMIT_INFO_TAGS = ("last_modified", "committer", "commit_message", "all_committers")
MERGE_ARTIFICIAL_TAGS = (ORIGINAL_ID_TAG, FILE_ORIGIN_TAG) + COMMIT_INFO_TAGS

# --- Merge-anchor tags (kept on split output; deliberately not lossless) ----
MERGE_ANCHOR_TAG = "merge_anchor"
PEER_A_TAG = "peerA"
NODE_ID_A_TAG = "node_idA"
WAY_ID_A_TAG = "way_idA"
PEER_B_TAG = "peerB"
NODE_ID_B_TAG = "node_idB"
WAY_ID_B_TAG = "way_idB"
ANCHOR_TAGS = (
    MERGE_ANCHOR_TAG,
    PEER_A_TAG, NODE_ID_A_TAG, WAY_ID_A_TAG,
    PEER_B_TAG, NODE_ID_B_TAG, WAY_ID_B_TAG,
)

NUM_LAYERS = 6


def layers_in_order(ll2map):
    """Return the six primitive layers in a deterministic order.

    The OSM writer sorts output by id, so this order does not affect the written
    file; it only fixes iteration order during merge/split.
    """
    return (
        ll2map.pointLayer,
        ll2map.lineStringLayer,
        ll2map.polygonLayer,
        ll2map.areaLayer,
        ll2map.laneletLayer,
        ll2map.regulatoryElementLayer,
    )


# --- Attribute helpers (lanelet2 AttributeMap raises on missing key) ---------
def attr_has(elem, key):
    return key in elem.attributes


def attr_get(elem, key, default=None):
    attrs = elem.attributes
    return attrs[key] if key in attrs else default


def attr_del(elem, key):
    attrs = elem.attributes
    if key in attrs:
        del attrs[key]


def attr_set_many(elem, mapping):
    for key, value in mapping.items():
        elem.attributes[key] = value


def basename(path):
    return os.path.basename(path) if path else path


def ways_using_point(merged_map, point):
    """Ways (linestrings, then polygons) that reference the given point."""
    ways = list(merged_map.lineStringLayer.findUsages(point))
    try:
        ways += list(merged_map.polygonLayer.findUsages(point))
    except Exception:  # noqa: BLE001 - polygon layer may not support findUsages
        pass
    return ways


def detect_anchor_points(merged_map):
    """Find boundary nodes shared across files.

    Returns a list of ``(point, by_file_origin)`` where ``by_file_origin`` maps
    each file_origin to the list of ways (in that file) using the point. A point
    qualifies when ways from >= 2 distinct file_origins reference it. Nodes
    tagged ``merge_anchor=yes`` but not shared are reported and skipped (they
    cannot be paired).
    """
    anchors = []
    for point in list(merged_map.pointLayer):
        by_fo = {}
        for way in ways_using_point(merged_map, point):
            fo = attr_get(way, FILE_ORIGIN_TAG)
            by_fo.setdefault(fo, []).append(way)
        distinct = [fo for fo in by_fo if fo is not None]
        if len(distinct) >= 2:
            anchors.append((point, by_fo))
        elif attr_get(point, MERGE_ANCHOR_TAG) == "yes":
            logging.warning(
                "Node id=%s tagged %s=yes but not shared across files; cannot pair, skipping",
                point.id, MERGE_ANCHOR_TAG,
            )
    return anchors


def build_anchor_metadata(peer_a, node_id_a, way_id_a, peer_b, node_id_b, way_id_b):
    """Return the symmetric merge-anchor tag dict for a peer pair."""
    return {
        MERGE_ANCHOR_TAG: "yes",
        PEER_A_TAG: peer_a, NODE_ID_A_TAG: str(node_id_a), WAY_ID_A_TAG: str(way_id_a),
        PEER_B_TAG: peer_b, NODE_ID_B_TAG: str(node_id_b), WAY_ID_B_TAG: str(way_id_b),
    }


def fuse_anchors(merged_map):
    """Fuse merge-anchor duplicate nodes back into single shared nodes.

    Uses the peerA/peerB + node_idA/node_idB tags to pair a canonical node (A)
    with its duplicate(s) (B). All ways referencing a duplicate are re-pointed to
    the canonical node, and the orphaned duplicates are dropped from a rebuilt
    map. Peers are matched by (basename(file_origin), original_id) so the match
    survives files being moved between merge and split locations.

    Returns ``(map, num_fused)``. When there are no anchors the input map is
    returned unchanged.
    """
    anchor_nodes = [p for p in list(merged_map.pointLayer)
                    if attr_get(p, MERGE_ANCHOR_TAG) == "yes"]
    if not anchor_nodes:
        return merged_map, 0

    index = {}
    for p in anchor_nodes:
        key = (basename(attr_get(p, FILE_ORIGIN_TAG)), attr_get(p, ORIGINAL_ID_TAG))
        index[key] = p

    orphan_ids = set()
    fused_pairs = set()
    fused = 0
    for p in anchor_nodes:
        a_key = (basename(attr_get(p, PEER_A_TAG)), attr_get(p, NODE_ID_A_TAG))
        b_key = (basename(attr_get(p, PEER_B_TAG)), attr_get(p, NODE_ID_B_TAG))
        canonical = index.get(a_key)
        duplicate = index.get(b_key)
        if canonical is None or duplicate is None or canonical.id == duplicate.id:
            continue
        pair = tuple(sorted((canonical.id, duplicate.id)))
        if pair in fused_pairs:
            continue
        fused_pairs.add(pair)
        for way in ways_using_point(merged_map, duplicate):
            for i in range(len(way)):
                if way[i].id == duplicate.id:
                    way[i] = canonical
        orphan_ids.add(duplicate.id)
        fused += 1

    if not orphan_ids:
        return merged_map, 0

    clean = lanelet2.core.LaneletMap()
    for layer_idx, layer in enumerate(layers_in_order(merged_map)):
        for elem in list(layer):
            if layer_idx == 0 and elem.id in orphan_ids:
                continue
            clean.add(elem)
    return clean, fused
