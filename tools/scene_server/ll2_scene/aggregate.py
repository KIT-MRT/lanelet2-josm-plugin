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
  car approaches; a moving vehicle occupies a voxel in one or two frames and
  is gone. Keeping only voxels seen in at least `min_frames` distinct frames
  removes the traffic without any labels, in every direction, and at the
  resolution the cloud is being binned to anyway.

Measured on 40 frames of the example scene at 0.2 m: 803 k occupied voxels,
30 % of them seen in a single frame; keeping those seen three times drops 4 %
of the points, and what it drops is the moving traffic (plus some wind-blown
foliage, which no persistence test can keep).

Points returned are in the scene's metric frame, like the per-frame cache --
the viewer's ENU anchor is applied later and can change.
"""

import math
import time

import numpy as np

from .points import INTENSITY_FULL_SCALE, decode_points, encode_points

# Voxel keys are packed into one int64 so numpy can sort them: 22 bits an axis
# is +-419 km at 0.2 m, far beyond any scene.
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


class SceneAggregator:
    """Builds (and caches) the static aggregate of a whole scene."""

    def __init__(self, source, disk_cache=None, voxel=0.15, max_range_m=60.0,
                 min_frames=3, ego_box=(3.6, 1.5, 2.6), step=1, log=print):
        self.source = source
        self.disk = disk_cache
        self.voxel = float(voxel)
        self.max_range_m = float(max_range_m)
        self.min_frames = int(min_frames)
        self.ego_box = ego_box
        self.step = max(1, int(step))
        self.log = log
        self.progress = {"state": "idle", "done": 0, "total": 0, "points": 0}

    # -- one frame ---------------------------------------------------------
    def _frame_points(self, index):
        """Scene-frame points of one frame, ego and far returns removed."""
        blob = self.disk.get(index) if self.disk else None
        if blob is not None:
            xyz, intensity, _sensor, _origin = decode_points(blob)
            intensity = (intensity.astype(np.float32) * (INTENSITY_FULL_SCALE / 255.0)
                         if intensity is not None else np.zeros(len(xyz), np.float32))
        else:
            xyz, intensity, sensor = self.source.frame_points_scene(index)
            if self.disk:
                self.disk.put(index, encode_points(xyz, intensity, sensor))
        if not len(xyz):
            return xyz, intensity
        rot, pos = self.source.pose_at(float(self.source.timestamps[index]))
        local = (xyz - pos) @ rot  # back into the ego frame
        # Returns off our own bodywork are static in the ego frame and smear
        # into a tube along the path in the world frame.
        on_ego = ((np.abs(local[:, 0]) < self.ego_box[0])
                  & (np.abs(local[:, 1]) < self.ego_box[1])
                  & (np.abs(local[:, 2]) < self.ego_box[2]))
        near = (local[:, 0] ** 2 + local[:, 1] ** 2) < self.max_range_m ** 2
        keep = near & ~on_ego
        return xyz[keep], intensity[keep]

    def frames(self):
        return range(0, self.source.frame_count, self.step)

    # -- the two passes ----------------------------------------------------
    def _count_frames_per_voxel(self):
        """How many distinct frames put a point in each voxel."""
        keys = np.zeros(0, np.int64)
        counts = np.zeros(0, np.int32)
        batch_keys, batch_counts = [], []
        for n, index in enumerate(self.frames()):
            xyz, _ = self._frame_points(index)
            if len(xyz):
                seen = np.unique(voxel_keys(xyz, self.voxel))
                batch_keys.append(seen)
                batch_counts.append(np.ones(len(seen), np.int32))
            # Merge now and then so the running set stays small.
            if len(batch_keys) >= 32:
                keys, counts = self._fold(keys, counts, batch_keys, batch_counts)
                batch_keys, batch_counts = [], []
            self.progress.update(state="scanning", done=n + 1)
        keys, counts = self._fold(keys, counts, batch_keys, batch_counts)
        return keys, counts

    @staticmethod
    def _fold(keys, counts, batch_keys, batch_counts):
        if not batch_keys:
            return keys, counts
        merged_keys = np.concatenate(batch_keys)
        merged_counts = np.concatenate(batch_counts)
        uniq, inverse = np.unique(merged_keys, return_inverse=True)
        return merge_counts(keys, counts, uniq,
                            np.bincount(inverse, weights=merged_counts).astype(np.int32))

    def _accumulate(self, kept_keys):
        """Centroid and mean intensity of every kept voxel."""
        n = len(kept_keys)
        sums = np.zeros((n, 3), np.float64)
        weight = np.zeros(n, np.float64)
        intensity = np.zeros(n, np.float64)
        for i, index in enumerate(self.frames()):
            xyz, inten = self._frame_points(index)
            if not len(xyz):
                continue
            key = voxel_keys(xyz, self.voxel)
            slot = np.searchsorted(kept_keys, key)
            np.clip(slot, 0, max(n - 1, 0), out=slot)
            hit = kept_keys[slot] == key
            slot = slot[hit]
            np.add.at(sums, slot, xyz[hit])
            np.add.at(weight, slot, 1.0)
            np.add.at(intensity, slot, inten[hit].astype(np.float64))
            self.progress.update(state="gathering", done=i + 1)
        good = weight > 0
        centroid = sums[good] / weight[good, None]
        return centroid, (intensity[good] / weight[good]).astype(np.float32)

    def build(self):
        """Run both passes. Returns (xyz (N,3), intensity (N,), stats dict)."""
        total = len(self.frames())
        self.progress.update(state="scanning", done=0, total=total, points=0)
        t0 = time.perf_counter()
        keys, counts = self._count_frames_per_voxel()
        t_scan = time.perf_counter() - t0
        occupied = len(keys)
        keep = counts >= self.min_frames
        kept_keys = keys[keep]
        self.log("scene: aggregate scanned %d frames, %d voxels, %d static (%.0f%%), %.0f s"
                 % (total, occupied, len(kept_keys),
                    100.0 * len(kept_keys) / max(occupied, 1), t_scan))
        self.progress.update(state="gathering", done=0)
        xyz, intensity = self._accumulate(kept_keys)
        stats = {
            "frames": total,
            "voxels": int(occupied),
            "static_voxels": int(len(kept_keys)),
            "points": int(len(xyz)),
            "voxel_m": self.voxel,
            "min_frames": self.min_frames,
            "max_range_m": self.max_range_m,
            "seconds": round(time.perf_counter() - t0, 1),
        }
        self.progress.update(state="ready", points=int(len(xyz)))
        self.log("scene: aggregate %d points in %.0f s (%s)"
                 % (len(xyz), stats["seconds"], describe(stats)))
        return xyz, intensity, stats


    # -- disk cache --------------------------------------------------------
    def cache_path(self):
        """Where this exact aggregate lives; the parameters are in the name."""
        if not (self.disk and self.disk.enabled):
            return None
        name = ("aggregate-v%.3f-f%d-r%.0f-s%d.npz"
                % (self.voxel, self.min_frames, self.max_range_m, self.step))
        return self.disk.dir / name

    def load_cached(self):
        path = self.cache_path()
        if not path or not path.is_file():
            return None
        try:
            with np.load(path, allow_pickle=False) as data:
                xyz = data["xyz"].astype(np.float64)
                intensity = data["intensity"]
                stats = {k: v.item() for k, v in data.items() if k.startswith("stat_")}
            stats = {k[len("stat_"):]: v for k, v in stats.items()}
            self.progress.update(state="ready", points=int(len(xyz)),
                                 done=len(self.frames()), total=len(self.frames()))
            return xyz, intensity, stats
        except Exception as exc:
            self.log("scene: ignoring unreadable aggregate cache (%r)" % exc)
            return None

    def save_cached(self, xyz, intensity, stats):
        path = self.cache_path()
        if not path:
            return
        try:
            payload = {"xyz": xyz.astype(np.float32), "intensity": intensity}
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
    return ("%.2f m voxels, seen in >=%d frames, within %.0f m"
            % (stats["voxel_m"], stats["min_frames"], stats["max_range_m"]))


def chunk_by_tile(xyz, intensity, tile_m=128.0, max_points=400_000):
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
    bounds = np.flatnonzero(np.diff(key)) + 1
    chunks = []
    for part_xyz, part_i in zip(np.split(xyz, bounds), np.split(intensity, bounds)):
        for start in range(0, len(part_xyz), max_points):
            piece = part_xyz[start:start + max_points]
            chunks.append({
                "xyz": piece,
                "intensity": part_i[start:start + max_points],
                "centre": piece.mean(axis=0).tolist(),
                "radius": float(np.linalg.norm(piece - piece.mean(axis=0), axis=1).max())
                if len(piece) else 0.0,
                "count": int(len(piece)),
            })
    return chunks
