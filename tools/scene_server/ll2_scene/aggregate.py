"""The whole scene as one point cloud, with the traffic taken out.

Stacking every frame of a scene gives ~163 M points for a 22 s drive, which is
both too much to draw and full of smear: `kitscenes` deskews each sweep
against the *ego* pose, so the car's own motion during the ~100 ms sweep is
compensated, but another vehicle driving past is a rigid body moving on its
own and lands in a different place in every frame. Aggregating leaves a chain
of car-shaped blobs down the road.

Two things fix that, and they cost almost nothing on top of the downsampling
that has to happen anyway:

* **Voxel downsampling.** Points are binned to `voxel` metres and replaced by
  the centroid of the bin, which takes 163 M points to a few million.
* **Voxel persistence.** A static surface is hit in frame after frame as the
  car approaches; a moving vehicle occupies a voxel for an instant and is
  gone. Keeping only voxels seen in at least `min_run` **consecutive** frames
  removes the traffic without any labels, in every direction, and at the
  resolution the cloud is being binned to anyway.

Consecutive, not merely often: counting distinct frames looks right on a short
clip and leaks badly on a long one, because three *different* cars crossing
the same patch of road over 22 s also add up to three hits. Measured over the
example scene's 220 frames, 1.2 M voxels -- 44 % of everything a hit count
keeps -- were hit three times or more without ever being seen in three
consecutive frames, and they are the car-shaped blobs strung down the road.

What this also removes is wind-blown foliage: leaves move further than a
voxel between frames, so a canopy fails the same test. Admitting it back with
an "or seen in many frames" clause re-admits a comparable amount of traffic
(measured: 161 k traffic-height voxels against 216 k canopy-height ones), so
there is no clean escape; `min_run=1` keeps everything.

Points returned are in the scene's metric frame, like the per-frame cache --
the viewer's ENU anchor is applied later and can change.
"""

import math
import multiprocessing
import os
import struct
import time
from collections import deque
from concurrent.futures import ProcessPoolExecutor

import numpy as np

from .points import INTENSITY_FULL_SCALE, decode_points, encode_points

# Bumped when the cached payload gains or changes a field, so an aggregate
# built by an older version is rebuilt instead of silently lacking it.
AGGREGATE_FORMAT = 3

# Voxel keys are packed into one int64 so numpy can sort them: 22 bits for x
# and y (+-419 km at 0.2 m, far beyond any scene); z gets the 20 bits left
# below the sign bit, +-105 km at 0.2 m -- the range check below covers 22
# bits, but no scene comes near the difference.
_BITS = 22
_HALF = 1 << (_BITS - 1)
_MASK = (1 << _BITS) - 1


def voxel_keys(xyz, voxel):
    """(N,3) metres -> (N,) int64 voxel ids."""
    v = np.floor(np.asarray(xyz, dtype=np.float64) / voxel).astype(np.int64)
    v += _HALF
    if np.any((v < 0) | (v > _MASK)):
        raise ValueError("points are too far from the origin for %g m voxels" % voxel)
    return v[:, 0] | (v[:, 1] << _BITS) | (v[:, 2] << (2 * _BITS))


def merge_sorted(sorted_keys, new_keys):
    """Merge two sorted, disjoint key arrays without sorting again.

    `np.unique(concatenate(...))` sorts the whole thing every time, which is
    most of the cost of building an aggregate once the voxel set is millions
    long. Both inputs are already sorted, so the merge is a placement:
    4.6x faster, same answer.

    Returns (merged, mask_of_old, positions_of_new) so the per-voxel state
    can be moved across without searching for it.
    """
    at_new = np.searchsorted(sorted_keys, new_keys) + np.arange(len(new_keys))
    merged = np.empty(len(sorted_keys) + len(new_keys), sorted_keys.dtype)
    keep_old = np.ones(len(merged), bool)
    keep_old[at_new] = False
    merged[at_new] = new_keys
    merged[keep_old] = sorted_keys
    return merged, keep_old, at_new


def merge_counts(keys_a, counts_a, keys_b, counts_b):
    """Sum two (sorted keys, counts) pairs into one."""
    if not len(keys_a):
        return keys_b, counts_b
    if not len(keys_b):
        return keys_a, counts_a
    keys = np.concatenate([keys_a, keys_b])
    counts = np.concatenate([counts_a, counts_b])
    uniq, inverse = np.unique(keys, return_inverse=True)
    return uniq, np.bincount(inverse, weights=counts).astype(np.int32)


# One aggregator per worker process, built once by the pool initializer.
_WORKER = {}


def _init_worker(scene_dir, cache_dir, limit_gb, kitscenes_path, voxel, max_range_m, ego_box,
                 kept_keys):
    from .cache import FrameDiskCache
    from .scene import SceneSource, read_origin
    # Any anchor will do -- workers never convert to the viewer's frame -- and
    # giving one spares each worker parsing the scene's map to pick it.
    source = SceneSource(scene_dir, kitscenes_path=kitscenes_path, anchor=read_origin(scene_dir))
    # The user's cache limit, not the default: trimming covers every scene.
    disk = (FrameDiskCache(source.scene_id, cache_dir, limit_gb, log=lambda _m: None)
            if cache_dir else None)
    _WORKER["aggregator"] = SceneAggregator(
        source, disk_cache=disk, voxel=voxel, max_range_m=max_range_m,
        ego_box=ego_box, log=lambda _m: None)
    _WORKER["kept"] = kept_keys


def _worker_seen_keys(index):
    return _WORKER["aggregator"]._seen_keys(index)


def _worker_block(frames):
    return _WORKER["aggregator"]._accumulate_block(_WORKER["kept"], frames)


def aggregate_cache_name(voxel, min_run, max_range_m, step):
    """File name of a cached aggregate. The rule is in the name, so a different
    one builds a new file instead of serving an aggregate filtered some other
    way -- and a city can tell which scenes are ready without opening them."""
    return ("aggregate-v%.3f-c%d-r%.0f-s%d-f%d.npz"
            % (voxel, min_run, max_range_m, step, AGGREGATE_FORMAT))


class SceneAggregator:
    """Builds (and caches) the static aggregate of a whole scene."""

    def __init__(self, source, disk_cache=None, voxel=0.15, max_range_m=60.0,
                 min_run=3, ego_box=(3.6, 1.5, 2.6), step=1, workers=1, log=print):
        self.source = source
        self.disk = disk_cache
        self.voxel = float(voxel)
        self.max_range_m = float(max_range_m)
        self.min_run = int(min_run)
        self.ego_box = ego_box
        self.step = max(1, int(step))
        # Per-frame work is independent, so it is handed to processes (threads
        # buy little: the decode spends its time in Python). Only the run
        # bookkeeping in pass one is sequential, and it consumes the results
        # in order.
        self.workers = max(1, int(workers))
        self.log = log
        self.progress = {"state": "idle", "done": 0, "total": 0, "points": 0}

    # -- one frame ---------------------------------------------------------
    def _frame_rgb(self, index, xyz):
        """Camera colour per point of a frame, from the cache when it is there."""
        cached = self.disk.get(index, "rgb") if self.disk else None
        if cached is not None and len(cached) == len(xyz) * 3:
            return np.frombuffer(cached, np.uint8).reshape(-1, 3)
        if not getattr(self.source, "frame_rgb", None):
            return None
        try:
            rgb = self.source.frame_rgb(index, xyz)
        except Exception:
            return None
        if self.disk and rgb is not None:
            self.disk.put(index, rgb.tobytes(), "rgb")
        return rgb

    def _frame_classes(self, index, xyz):
        """Vistas class per point of the *whole* frame, cached like the frame server does.

        Same entries, same content as `SceneHttpServer._labels_for`: the two
        used to store classes of differently filtered points under one name,
        so each threw the other's away and neither ever hit the cache.
        """
        cached = self.disk.get(index, "class") if self.disk else None
        if cached is not None and len(cached) == len(xyz):
            return np.frombuffer(cached, np.uint8)
        instances = None
        try:
            if getattr(self.source, "frame_labels", None):
                classes, instances = self.source.frame_labels(index, xyz)
            elif getattr(self.source, "frame_classes", None):
                classes = self.source.frame_classes(index, xyz)
            else:
                return None
        except Exception:
            return None
        if self.disk and classes is not None:
            self.disk.put(index, classes.tobytes(), "class")
            if instances is not None:
                self.disk.put(index, instances.tobytes(), "inst")
        return classes

    def _frame_points(self, index):
        """Scene-frame points of one frame, ego and far returns removed."""
        xyz, intensity, keep = self._frame_full(index)
        return xyz[keep], intensity[keep]

    def _frame_full(self, index):
        """The whole frame (xyz, intensity) and which points to aggregate."""
        blob = self.disk.get(index) if self.disk else None
        decoded = None
        if blob is not None:
            try:
                decoded = decode_points(blob)
            except (ValueError, struct.error):
                # A damaged cache entry is a miss, not an error forever.
                self.disk.drop(index)
        if decoded is not None:
            xyz, intensity, _sensor, _origin, _classes, _rgb, _inst = decoded
            intensity = (intensity.astype(np.float32) * (INTENSITY_FULL_SCALE / 255.0)
                         if intensity is not None else np.zeros(len(xyz), np.float32))
        else:
            xyz, intensity, sensor = self.source.frame_points_scene(index)
            if self.disk:
                self.disk.put(index, encode_points(xyz, intensity, sensor))
        if not len(xyz):
            return xyz, intensity, np.zeros(0, bool)
        rot, pos = self.source.pose_at(float(self.source.timestamps[index]))
        local = (xyz - pos) @ rot  # back into the ego frame
        # Returns off our own bodywork are static in the ego frame and smear
        # into a tube along the path in the world frame.
        on_ego = ((np.abs(local[:, 0]) < self.ego_box[0])
                  & (np.abs(local[:, 1]) < self.ego_box[1])
                  & (np.abs(local[:, 2]) < self.ego_box[2]))
        near = (local[:, 0] ** 2 + local[:, 1] ** 2) < self.max_range_m ** 2
        keep = near & ~on_ego
        return xyz, intensity, keep

    def frames(self):
        return range(0, self.source.frame_count, self.step)

    # -- running the per-frame work in parallel ----------------------------
    def _worker_setup(self):
        """Arguments a worker needs to rebuild this aggregator, or None.

        Workers construct their own `SceneSource` from paths: it holds
        lanelet2 handles and an open dataset, none of which travel through a
        pickle. A stub source (the tests) has no directory, so it stays
        in-process.
        """
        scene_dir = getattr(self.source, "dir", None)
        if self.workers <= 1 or scene_dir is None:
            return None
        root = getattr(self.disk, "root", None) if self.disk else None
        limit_gb = getattr(self.disk, "limit_bytes", 0) / 1e9 if self.disk else 0
        return (str(scene_dir), str(root) if root else None, limit_gb,
                getattr(self.source, "kitscenes_path", None),
                self.voxel, self.max_range_m, tuple(self.ego_box))

    def _pool(self, setup, kept_keys=None):
        # spawn, not fork: the server forking this has HTTP and decode threads
        # running, and a forked child can inherit one of their locks held.
        # Workers rebuild everything from paths anyway.
        return ProcessPoolExecutor(max_workers=self.workers, initializer=_init_worker,
                                   initargs=setup + (kept_keys,),
                                   mp_context=multiprocessing.get_context("spawn"))

    def _scan_frames(self):
        """(index, voxel keys of that frame) in order, computed ahead in parallel."""
        setup = self._worker_setup()
        frames = list(self.frames())
        if setup is None:
            for index in frames:
                yield index, self._seen_keys(index)
            return
        with self._pool(setup) as pool:
            pending = deque()
            upcoming = iter(frames)
            # Only a few frames ahead: each result is several megabytes.
            for _ in range(self.workers * 2):
                nxt = next(upcoming, None)
                if nxt is None:
                    break
                pending.append((nxt, pool.submit(_worker_seen_keys, nxt)))
            while pending:
                index, future = pending.popleft()
                yield index, future.result()
                nxt = next(upcoming, None)
                if nxt is not None:
                    pending.append((nxt, pool.submit(_worker_seen_keys, nxt)))

    def _seen_keys(self, index):
        xyz, _ = self._frame_points(index)
        if not len(xyz):
            return np.zeros(0, np.int64)
        return np.unique(voxel_keys(xyz, self.voxel))

    # -- the two passes ----------------------------------------------------
    def _runs_per_voxel(self):
        """Longest run of consecutive frames that hit each voxel.

        Frame by frame, because a run is a temporal thing: a voxel the
        current frame misses has its run broken, which is the whole point.
        Every index operation below is plain fancy indexing -- the old keys
        are unique, so they map one-to-one into the merged array and no
        scatter-add is needed.
        """
        keys = np.zeros(0, np.int64)
        current = np.zeros(0, np.int32)
        longest = np.zeros(0, np.int32)
        for n, (index, seen) in enumerate(self._scan_frames()):
            if not len(keys):
                keys = seen
                current = np.ones(len(seen), np.int32)
                longest = current.copy()
                self.progress.update(state="scanning", done=n + 1)
                continue
            at = np.searchsorted(keys, seen)
            probe = np.minimum(at, len(keys) - 1)
            found = keys[probe] == seen
            hit = np.zeros(len(keys), bool)
            hit[at[found]] = True
            # A frame that misses a voxel ends its run -- that is the whole
            # point of counting consecutive frames rather than hits.
            current = np.where(hit, current + 1, 0)
            longest = np.maximum(longest, current)
            fresh = seen[~found]
            if len(fresh):
                keys, keep_old, at_new = merge_sorted(keys, fresh)
                grown = np.ones(len(keys), np.int32)
                grown[keep_old] = current
                current = grown
                grown = np.ones(len(keys), np.int32)
                grown[keep_old] = longest
                longest = grown
            self.progress.update(state="scanning", done=n + 1)
        return keys, longest

    def _accumulate(self, kept_keys):
        """Centroid, mean intensity, majority class and mean colour per voxel."""
        setup = self._worker_setup()
        frames = list(self.frames())
        if setup is None:
            parts = [self._accumulate_block(kept_keys, frames)]
        else:
            # Blocks rather than frames: a partial sum is the size of the
            # whole voxel set, so it is worth sending back only once.
            size = max(1, math.ceil(len(frames) / self.workers))
            blocks = [frames[i:i + size] for i in range(0, len(frames), size)]
            with self._pool(setup, kept_keys) as pool:
                parts = []
                for done, part in enumerate(pool.map(_worker_block, blocks)):
                    parts.append(part)
                    self.progress.update(state="gathering",
                                         done=min(len(frames), (done + 1) * size))
        return self._combine(parts, len(kept_keys))

    def _combine(self, parts, n):
        from .mvd import UNLABELLED
        sums = np.zeros((n, 3), np.float64)
        weight = np.zeros(n, np.float64)
        intensity = np.zeros(n, np.float64)
        rgb_sums = np.zeros((n, 3), np.float64)
        rgb_weight = np.zeros(n, np.float64)
        votes_key = np.zeros(0, np.int64)
        votes_count = np.zeros(0, np.int32)
        for part in parts:
            sums += part["sums"]
            weight += part["weight"]
            intensity += part["intensity"]
            rgb_sums += part["rgb_sums"]
            rgb_weight += part["rgb_weight"]
            votes_key, votes_count = merge_counts(votes_key, votes_count,
                                                  part["votes_key"], part["votes_count"])
        good = weight > 0
        centroid = sums[good] / weight[good, None]
        classes = self._majority(votes_key, votes_count, n)[good]
        colour = None
        if rgb_weight.any():
            safe = np.where(rgb_weight > 0, rgb_weight, 1.0)
            averaged = np.where((rgb_weight > 0)[:, None], rgb_sums / safe[:, None], 90.0)
            colour = np.clip(averaged[good] + 0.5, 0, 255).astype(np.uint8)
        return (centroid, (intensity[good] / weight[good]).astype(np.float32),
                classes, colour)

    def _accumulate_block(self, kept_keys, frames):
        """Partial sums over some frames; `_combine` adds them up."""
        from .mvd import UNLABELLED
        n = len(kept_keys)
        sums = np.zeros((n, 3), np.float64)
        weight = np.zeros(n, np.float64)
        intensity = np.zeros(n, np.float64)
        rgb_sums = np.zeros((n, 3), np.float64)
        rgb_weight = np.zeros(n, np.float64)
        # (voxel, class) pairs of labelled points only -- a few per cent of
        # them, so this stays small enough to hold and count in one go.
        votes_key = np.zeros(0, np.int64)
        votes_count = np.zeros(0, np.int32)
        batch = []
        for i, index in enumerate(frames):
            full, full_inten, keep = self._frame_full(index)
            xyz, inten = full[keep], full_inten[keep]
            if not len(xyz):
                continue
            key = voxel_keys(xyz, self.voxel)
            slot = np.searchsorted(kept_keys, key)
            np.clip(slot, 0, max(n - 1, 0), out=slot)
            hit = kept_keys[slot] == key
            slot_hit = slot[hit]
            np.add.at(sums, slot_hit, xyz[hit])
            np.add.at(weight, slot_hit, 1.0)
            np.add.at(intensity, slot_hit, inten[hit].astype(np.float64))
            # Colours and classes of the whole frame, as the frame server
            # caches them, then cut down to the points aggregated.
            rgb = self._frame_rgb(index, full)
            if rgb is not None:
                rgb = rgb[keep]
                # 90 is the neutral grey of a point no camera saw.
                seen = (rgb[hit] != 90).any(axis=1)
                if seen.any():
                    np.add.at(rgb_sums, slot_hit[seen], rgb[hit][seen].astype(np.float64))
                    np.add.at(rgb_weight, slot_hit[seen], 1.0)
            classes = self._frame_classes(index, full)
            if classes is not None:
                classes = classes[keep]
                labelled = classes[hit] != UNLABELLED
                if labelled.any():
                    batch.append(slot_hit[labelled].astype(np.int64) * 256
                                 + classes[hit][labelled].astype(np.int64))
            if len(batch) >= 32:
                votes_key, votes_count = self._fold_votes(votes_key, votes_count, batch)
                batch = []
            self.progress.update(state="gathering", done=i + 1)
        votes_key, votes_count = self._fold_votes(votes_key, votes_count, batch)
        return {"sums": sums, "weight": weight, "intensity": intensity,
                "rgb_sums": rgb_sums, "rgb_weight": rgb_weight,
                "votes_key": votes_key, "votes_count": votes_count}

    @staticmethod
    def _fold_votes(keys, counts, batch):
        if not batch:
            return keys, counts
        merged = np.concatenate(batch)
        uniq, inverse = np.unique(merged, return_inverse=True)
        return merge_counts(keys, counts, uniq,
                            np.bincount(inverse, minlength=len(uniq)).astype(np.int32))

    @staticmethod
    def _majority(votes_key, votes_count, n):
        """The class with most votes per voxel; UNLABELLED where there are none."""
        from .mvd import UNLABELLED
        out = np.full(n, UNLABELLED, np.uint8)
        if not len(votes_key):
            return out
        slots = (votes_key >> 8).astype(np.int64)
        labels = (votes_key & 0xFF).astype(np.uint8)
        # Sorting by (slot, count) puts each voxel's winner last.
        order = np.lexsort((votes_count, slots))
        slots, labels = slots[order], labels[order]
        out[slots] = labels
        return out

    def build(self):
        """Run both passes. Returns (xyz (N,3), intensity (N,), stats dict)."""
        total = len(self.frames())
        self.progress.update(state="scanning", done=0, total=total, points=0)
        t0 = time.perf_counter()
        keys, longest = self._runs_per_voxel()
        t_scan = time.perf_counter() - t0
        occupied = len(keys)
        kept_keys = keys[longest >= self.min_run]
        self.log("scene: aggregate scanned %d frames, %d voxels, %d static (%.0f%%), %.0f s"
                 % (total, occupied, len(kept_keys),
                    100.0 * len(kept_keys) / max(occupied, 1), t_scan))
        self.progress.update(state="gathering", done=0)
        xyz, intensity, classes, colour = self._accumulate(kept_keys)
        stats = {
            "frames": total,
            "voxels": int(occupied),
            "static_voxels": int(len(kept_keys)),
            "points": int(len(xyz)),
            "voxel_m": self.voxel,
            "min_run": self.min_run,
            "max_range_m": self.max_range_m,
            "seconds": round(time.perf_counter() - t0, 1),
        }
        self.progress.update(state="ready", points=int(len(xyz)))
        self.log("scene: aggregate %d points in %.0f s (%s)"
                 % (len(xyz), stats["seconds"], describe(stats)))
        return xyz, intensity, classes, colour, stats


    # -- disk cache --------------------------------------------------------
    def cache_path(self):
        """Where this exact aggregate lives; the parameters are in the name."""
        if not (self.disk and self.disk.enabled):
            return None
        return self.disk.dir / aggregate_cache_name(self.voxel, self.min_run,
                                                    self.max_range_m, self.step)

    def load_cached(self):
        path = self.cache_path()
        if not path or not path.is_file():
            return None
        try:
            with np.load(path, allow_pickle=False) as data:
                xyz = data["xyz"].astype(np.float64)
                intensity = data["intensity"]
                classes = data["classes"] if "classes" in data else None
                colour = data["rgb"] if "rgb" in data else None
                stats = {k: v.item() for k, v in data.items() if k.startswith("stat_")}
            stats = {k[len("stat_"):]: v for k, v in stats.items()}
            self.progress.update(state="ready", points=int(len(xyz)),
                                 done=len(self.frames()), total=len(self.frames()))
            return xyz, intensity, classes, colour, stats
        except Exception as exc:
            self.log("scene: ignoring unreadable aggregate cache (%r)" % exc)
            return None

    def save_cached(self, xyz, intensity, classes, colour, stats):
        path = self.cache_path()
        if not path:
            return
        try:
            payload = {"xyz": xyz.astype(np.float32), "intensity": intensity}
            if classes is not None:
                payload["classes"] = np.asarray(classes, np.uint8)
            if colour is not None:
                payload["rgb"] = np.asarray(colour, np.uint8)
            payload.update({"stat_" + k: np.array(v) for k, v in stats.items()})
            # np.savez appends ".npz" to a *name* but not to an open file, and
            # the rename has to find exactly what was written.
            tmp = path.with_name(path.name + ".part")
            with open(tmp, "wb") as handle:
                np.savez(handle, **payload)
            tmp.replace(path)
        except OSError as exc:
            self.log("scene: could not cache the aggregate (%r)" % exc)


def describe(stats):
    return ("%.2f m voxels, seen in >=%d consecutive frames, within %.0f m"
            % (stats["voxel_m"], stats["min_run"], stats["max_range_m"]))


def chunk_by_tile(xyz, intensity, classes=None, colour=None, tile_m=128.0,
                  max_points=400_000):
    """Split an aggregate into spatial chunks the browser can stream and cull.

    Tiles keep what is drawn together close together, so the viewer can drop
    far chunks; the point cap stops one dense tile becoming a 40 MB download.
    """
    if not len(xyz):
        return []
    tile = np.floor(xyz[:, :2] / tile_m).astype(np.int64)
    key = (tile[:, 0] + (1 << 20)) | ((tile[:, 1] + (1 << 20)) << 21)
    order = np.argsort(key, kind="stable")
    key = key[order]
    xyz = xyz[order]
    intensity = intensity[order]
    classes = classes[order] if classes is not None else None
    colour = colour[order] if colour is not None else None
    bounds = np.flatnonzero(np.diff(key)) + 1
    split_classes = np.split(classes, bounds) if classes is not None else None
    split_colour = np.split(colour, bounds) if colour is not None else None
    chunks = []
    for index, (part_xyz, part_i) in enumerate(zip(np.split(xyz, bounds),
                                                   np.split(intensity, bounds))):
        part_c = split_classes[index] if split_classes is not None else None
        part_rgb = split_colour[index] if split_colour is not None else None
        for start in range(0, len(part_xyz), max_points):
            piece = part_xyz[start:start + max_points]
            chunks.append({
                "xyz": piece,
                "intensity": part_i[start:start + max_points],
                "classes": part_c[start:start + max_points] if part_c is not None else None,
                "rgb": part_rgb[start:start + max_points] if part_rgb is not None else None,
                "centre": piece.mean(axis=0).tolist(),
                "radius": float(np.linalg.norm(piece - piece.mean(axis=0), axis=1).max())
                if len(piece) else 0.0,
                "count": int(len(piece)),
            })
    return chunks
