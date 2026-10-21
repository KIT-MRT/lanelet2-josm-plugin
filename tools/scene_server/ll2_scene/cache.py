"""Disk cache for decoded frames.

Decoding a frame (7 parquet files, sentinel filter, per-point deskew, pose
interpolation) costs ~1.3 s; putting it in the viewer's ENU costs a further
0.12 s. Only the first part is worth caching, and it is cached **in the
scene's own metric frame** so the cache survives a change of ENU anchor --
which happens as soon as JOSM connects and the scene server adopts its
anchor instead. Re-projecting a cached frame is the cheap 0.12 s.

Nothing is ever written into the dataset: it is read-only as far as this tool
is concerned. The cache lives under `$XDG_CACHE_HOME/josm-lanelet2/
scene-server` (i.e. `~/.cache/...`), overridable with `--cache-dir`, and is
kept under a size limit by deleting the least recently used frames.
"""

import json
import os
import shutil
import time
from pathlib import Path

# Bumped when the cached payload's meaning changes, so old files are ignored
# rather than silently misread.
FORMAT_VERSION = 1
DEFAULT_LIMIT_GB = 10.0


def default_cache_dir():
    base = os.environ.get("XDG_CACHE_HOME", "").strip()
    root = Path(base).expanduser() if base else Path.home() / ".cache"
    return root / "josm-lanelet2" / "scene-server"


class FrameDiskCache:
    """Frames of one scene, as encoded point blobs in the scene frame."""

    def __init__(self, scene_id, directory=None, limit_gb=DEFAULT_LIMIT_GB, log=print):
        self.enabled = directory is not None
        self.log = log
        self.limit_bytes = int(max(0.0, limit_gb) * 1e9)
        self.dir = None
        self.hits = 0
        self.misses = 0
        if not self.enabled:
            return
        self.root = Path(directory).expanduser()   # what the caller asked for
        self.dir = self.root / ("%s-v%d" % (scene_id, FORMAT_VERSION))
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            (self.dir / "about.json").write_text(json.dumps(
                {"scene": scene_id, "format": FORMAT_VERSION,
                 "note": "decoded LiDAR frames in the scene's metric frame; safe to delete"},
                indent=2))
        except OSError as exc:
            self.log("scene: cache off, %s is not writable (%s)" % (self.dir, exc))
            self.enabled = False
            self.dir = None

    def _path(self, index, kind="frame"):
        return self.dir / ("%s-%06d.bin" % (kind, index))

    def get(self, index, kind="frame"):
        if not self.enabled:
            return None
        path = self._path(index, kind)
        try:
            blob = path.read_bytes()
        except OSError:
            self.misses += 1
            return None
        # Touch it so the eviction order is "least recently used", not oldest.
        try:
            os.utime(path, None)
        except OSError:
            pass
        self.hits += 1
        return blob

    def put(self, index, blob, kind="frame"):
        if not self.enabled:
            return
        path = self._path(index, kind)
        tmp = path.with_suffix(".part")
        try:
            tmp.write_bytes(blob)
            tmp.replace(path)  # atomic, so a killed server leaves no half frame
        except OSError as exc:
            self.log("scene: could not cache %s %d (%s)" % (kind, index, exc))
            try:
                tmp.unlink()
            except OSError:
                pass
            return
        self._evict()

    def _evict(self):
        """Drop the least recently used frames once over the size limit."""
        if not self.limit_bytes:
            return
        try:
            files = [(p.stat().st_atime, p.stat().st_size, p)
                     for p in self.dir.parent.glob("*/*-[0-9]*.bin")]
        except OSError:
            return
        total = sum(size for _, size, _ in files)
        if total <= self.limit_bytes:
            return
        for _, size, path in sorted(files):
            try:
                path.unlink()
            except OSError:
                continue
            total -= size
            if total <= self.limit_bytes:
                break
        self.log("scene: cache trimmed to %.1f GB" % (total / 1e9))

    def size_bytes(self):
        if not self.enabled:
            return 0
        try:
            return sum(p.stat().st_size for p in self.dir.glob("*-[0-9]*.bin"))
        except OSError:
            return 0

    def describe(self):
        if not self.enabled:
            return "cache off"
        return "cache %s (%.1f GB used, limit %.0f GB)" % (
            self.dir, self.size_bytes() / 1e9, self.limit_bytes / 1e9)

    def clear(self):
        if self.enabled and self.dir:
            shutil.rmtree(self.dir, ignore_errors=True)
            self.dir.mkdir(parents=True, exist_ok=True)
